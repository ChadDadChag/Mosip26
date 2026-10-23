"""Convert MediaPipe's legacy face_landmark.tflite (468 points) to ONNX and prove the conversion.

tf2onnx needs protobuf~=3.20 and numpy<2, while onnxruntime 1.31.0 (the runtime we ship)
needs protobuf>=4.25.8, so the work is split across the two venvs in three steps:

  1. main env     python -I models/tools/convert_facemesh.py inputs
       Writes .cache/facemesh_check_inputs.npz: 3 random NHWC inputs in [0,1] (seeded) plus
       2 face crops built with reference_pipeline's ROI method from the golden images.
  2. convert env  python -I models/tools/convert_facemesh.py convert
       Runs `python -m tf2onnx.convert --tflite face_landmark.tflite --output
       face_landmark_468.onnx --opset 13` (input kept NHWC), then runs tf.lite.Interpreter on
       the check inputs and saves its outputs to .cache/facemesh_check_tflite.npz.
  3. main env     python -I models/tools/convert_facemesh.py verify
       Runs the ONNX file in onnxruntime on the same inputs and fails unless the landmark
       max |diff| < 0.01 and the face-flag logit max |diff| < 1e-3.
"""
import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parent
MODELS = TOOLS.parent
CACHE = TOOLS / ".cache"
TFLITE = CACHE / "face_landmark.tflite"
ONNX = MODELS / "face_landmark_468.onnx"
INPUTS = CACHE / "facemesh_check_inputs.npz"
TFLITE_OUT = CACHE / "facemesh_check_tflite.npz"
GOLDEN = MODELS.parent / "liveness-analyzer" / "src" / "test" / "resources" / "golden"
CROP_IMAGES = ["portrait_frontal.jpg", "crew_multi.jpg"]
LANDMARK_TOL = 0.01
FLAG_TOL = 1e-3


def step_inputs():
    sys.path.insert(0, str(TOOLS))
    import reference_pipeline as rp

    rng = np.random.default_rng(468)
    arrays = {f"random_{i}": rng.uniform(0.0, 1.0, size=(1, 192, 192, 3)).astype(np.float32) for i in range(3)}
    detector = rp.YuNet(MODELS / rp.YUNET_FILE)
    for name in CROP_IMAGES:
        bgr = rp.imread_bgr(GOLDEN / name)
        faces = detector.detect(bgr)
        if not faces:
            sys.exit(f"no face found in {name}")
        primary = max(faces, key=lambda f: f["box"][2] * f["box"][3])
        roi = rp.mesh_roi(primary)
        arrays[f"face_{Path(name).stem}"] = rp.mesh_input(bgr, roi)
    np.savez(INPUTS, **arrays)
    print(f"wrote {INPUTS.name}: " + ", ".join(f"{k}{v.shape}" for k, v in arrays.items()))


def step_convert():
    import tensorflow as tf

    cmd = [sys.executable, "-I", "-m", "tf2onnx.convert", "--tflite", str(TFLITE),
           "--output", str(ONNX), "--opset", "13"]
    print("running:", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=str(TOOLS))

    interp = tf.lite.Interpreter(model_path=str(TFLITE))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    outs = interp.get_output_details()
    print(f"tflite input  {inp['name']} {list(inp['shape'])} {np.dtype(inp['dtype']).name}")
    for o in outs:
        print(f"tflite output {o['name']} {list(o['shape'])} {np.dtype(o['dtype']).name}")
    results = {}
    with np.load(INPUTS) as data:
        for key in data.files:
            interp.set_tensor(inp["index"], data[key])
            interp.invoke()
            for o in outs:
                results[f"{key}|{o['name']}"] = interp.get_tensor(o["index"]).copy()
    np.savez(TFLITE_OUT, **results)
    print(f"wrote {TFLITE_OUT.name} ({len(results)} arrays)")


def step_verify():
    import onnxruntime as ort

    sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    print(f"onnx input  {inp.name} {inp.shape} {inp.type}")
    for o in sess.get_outputs():
        print(f"onnx output {o.name} {o.shape} {o.type}")
    ref = np.load(TFLITE_OUT)
    # Pair outputs by element count: 1404 = landmarks, 1 = face flag logit.
    by_size = {}
    for key in ref.files:
        by_size.setdefault(ref[key].size, set()).add(key.split("|", 1)[1])
    lm_tfl, flag_tfl = next(iter(by_size[1404])), next(iter(by_size[1]))
    lm_onnx = next(o.name for o in sess.get_outputs() if np.prod(o.shape[1:]) == 1404)
    flag_onnx = next(o.name for o in sess.get_outputs() if np.prod(o.shape[1:]) == 1)
    print(f"pairing: landmarks {lm_tfl} <-> {lm_onnx}; flag {flag_tfl} <-> {flag_onnx}")

    worst_lm = worst_flag = 0.0
    with np.load(INPUTS) as data:
        for key in data.files:
            got_lm, got_flag = sess.run([lm_onnx, flag_onnx], {inp.name: data[key]})
            d_lm = float(np.abs(got_lm.ravel() - ref[f"{key}|{lm_tfl}"].ravel()).max())
            d_flag = float(np.abs(got_flag.ravel() - ref[f"{key}|{flag_tfl}"].ravel()).max())
            flag = 1.0 / (1.0 + np.exp(-float(got_flag.ravel()[0])))
            print(f"  {key:<24} landmarks max|diff| {d_lm:.3e}  flag-logit |diff| {d_flag:.3e}  sigmoid(flag) {flag:.4f}")
            worst_lm, worst_flag = max(worst_lm, d_lm), max(worst_flag, d_flag)
    ok = worst_lm < LANDMARK_TOL and worst_flag < FLAG_TOL
    print(f"worst landmark diff {worst_lm:.3e} (< {LANDMARK_TOL}), worst flag diff {worst_flag:.3e} (< {FLAG_TOL}): "
          f"{'ok' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("step", choices=["inputs", "convert", "verify"])
    step = ap.parse_args().step
    {"inputs": step_inputs, "convert": step_convert, "verify": step_verify}[step]()


if __name__ == "__main__":
    main()
