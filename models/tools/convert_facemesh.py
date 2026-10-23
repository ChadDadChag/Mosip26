"""Convert a MediaPipe face-mesh TFLite model to ONNX and prove the conversion.

tf2onnx needs protobuf~=3.20 and numpy<2, while onnxruntime 1.31.0 (the runtime we ship)
needs protobuf>=4.25.8, so the work is split across the two venvs in three steps:

  1. main env     python -I models/tools/convert_facemesh.py inputs  [--model v2]
       Writes .cache/<model>_check_inputs.npz: 3 random NHWC inputs in [0,1] (seeded) plus
       2 face crops built with reference_pipeline's ROI method from the golden images.
  2. convert env  python -I models/tools/convert_facemesh.py convert [--model v2]
       Runs `python -m tf2onnx.convert --tflite <tflite> --output <onnx> --opset 13` (input
       kept NHWC), then runs tf.lite.Interpreter on the check inputs and saves every output to
       .cache/<model>_check_tflite.npz.
  3. main env     python -I models/tools/convert_facemesh.py verify  [--model v2]
       Runs the ONNX file in onnxruntime on the same inputs and compares every output by name:
       the landmark tensor must agree within 0.01, every other output within 1e-3.

Model: FaceMesh V2 (face_landmarks_detector.tflite from the face_landmarker.task bundle,
478 points, 256x256). The legacy 468-point face_landmark.tflite was converted the same way
in Phase 1 and replaced because its eyelids never close (closed/open EAR about 0.7).
"""
import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parent
MODELS = TOOLS.parent
CACHE = TOOLS / ".cache"
GOLDEN = MODELS.parent / "liveness-analyzer" / "src" / "test" / "resources" / "golden"
CROP_IMAGES = ["portrait_frontal.png", "crew_multi.png"]
LANDMARK_TOL = 0.01
OTHER_TOL = 1e-3

MESHES = {
    "v2": dict(tflite="face_landmarks_detector.tflite", onnx="face_mesh_v2.onnx", size=256, landmarks="Identity"),
}


def paths(model):
    m = MESHES[model]
    return (m, CACHE / m["tflite"], MODELS / m["onnx"],
            CACHE / f"{model}_check_inputs.npz", CACHE / f"{model}_check_tflite.npz")


def step_inputs(model):
    sys.path.insert(0, str(TOOLS))
    import reference_pipeline as rp

    m, _, _, inputs, _ = paths(model)
    size = m["size"]
    rng = np.random.default_rng(468)
    arrays = {f"random_{i}": rng.uniform(0.0, 1.0, size=(1, size, size, 3)).astype(np.float32) for i in range(3)}
    detector = rp.YuNet(MODELS / rp.YUNET_FILE)
    for name in CROP_IMAGES:
        bgr = rp.imread_bgr(GOLDEN / name)
        primary = rp.count_faces(detector.detect(bgr))[1]
        if primary is None:
            sys.exit(f"no face found in {name}")
        arrays[f"face_{Path(name).stem}"] = rp.mesh_input(bgr, rp.mesh_roi(primary), size)
    np.savez(inputs, **arrays)
    print(f"wrote {inputs.name}: " + ", ".join(f"{k}{v.shape}" for k, v in arrays.items()))


def step_convert(model):
    import tensorflow as tf

    _, tflite, onnx_path, inputs, tflite_out = paths(model)
    cmd = [sys.executable, "-I", "-m", "tf2onnx.convert", "--tflite", str(tflite),
           "--output", str(onnx_path), "--opset", "13"]
    print("running:", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=str(TOOLS))

    interp = tf.lite.Interpreter(model_path=str(tflite))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    outs = interp.get_output_details()
    print(f"tflite input  {inp['name']} {list(inp['shape'])} {np.dtype(inp['dtype']).name}")
    for o in outs:
        print(f"tflite output {o['name']} {list(o['shape'])} {np.dtype(o['dtype']).name}")
    results = {}
    with np.load(inputs) as data:
        for key in data.files:
            interp.set_tensor(inp["index"], data[key])
            interp.invoke()
            for o in outs:
                results[f"{key}|{o['name']}"] = interp.get_tensor(o["index"]).copy()
    np.savez(tflite_out, **results)
    print(f"wrote {tflite_out.name} ({len(results)} arrays)")


def step_verify(model):
    import onnxruntime as ort

    m, _, onnx_path, inputs, tflite_out = paths(model)
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    print(f"onnx input  {inp.name} {inp.shape} {inp.type}")
    for o in sess.get_outputs():
        print(f"onnx output {o.name} {o.shape} {o.type}")
    ref = np.load(tflite_out)
    names = [o.name for o in sess.get_outputs()]
    worst = {n: 0.0 for n in names}
    with np.load(inputs) as data:
        for key in data.files:
            got = dict(zip(names, sess.run(names, {inp.name: data[key]})))
            diffs = {n: float(np.abs(got[n].ravel() - ref[f"{key}|{n}"].ravel()).max()) for n in names}
            others = "  ".join(f"{n} {got[n].ravel()[0]:+.4f}" for n in names if n != m["landmarks"])
            print(f"  {key:<24} max|diff| " + "  ".join(f"{n} {d:.3e}" for n, d in diffs.items()) + f"   values: {others}")
            for n, d in diffs.items():
                worst[n] = max(worst[n], d)
    ok = all(d < (LANDMARK_TOL if n == m["landmarks"] else OTHER_TOL) for n, d in worst.items())
    print("worst: " + ", ".join(f"{n} {d:.3e} (< {LANDMARK_TOL if n == m['landmarks'] else OTHER_TOL})"
                                for n, d in worst.items()) + f": {'ok' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("step", choices=["inputs", "convert", "verify"])
    ap.add_argument("--model", choices=sorted(MESHES), default="v2")
    args = ap.parse_args()
    {"inputs": step_inputs, "convert": step_convert, "verify": step_verify}[args.step](args.model)


if __name__ == "__main__":
    main()
