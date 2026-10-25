"""Export the two Silent-Face MiniFASNet models from the upstream .pth weights to ONNX.

    python -I models/tools/fetch_models.py
    python -I models/tools/export_minifasnet.py [--yakhyo]

Builds MiniFASNetV2 and MiniFASNetV1SE the way upstream's AntiSpoofPredict does for an
80x80 input (conv6_kernel=(5,5), num_classes=3, embedding_size=128), loads the weights with
strict=True (stripping the DataParallel "module." prefix), and exports with the TorchScript
exporter at opset 13:
    input  "input"  float32[1,3,80,80]  BGR, raw 0..255, no normalisation
    output "output" float32[1,3]        logits; softmax index 1 = real

Then compares torch and onnxruntime on 20 random 0..255 inputs and fails if the largest
logit difference is 1e-3 or more. With --yakhyo, also reports how far softmax[1] is from
yakhyo/face-anti-spoofing's release ONNX files on the same inputs (report only).
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

TOOLS = Path(__file__).resolve().parent
MODELS = TOOLS.parent
CACHE = TOOLS / ".cache"
sys.path.insert(0, str(TOOLS / "third_party" / "silent_face"))
from MiniFASNet import MiniFASNetV1SE, MiniFASNetV2  # noqa: E402  (vendored, unmodified)

EXPORTS = [
    dict(label="V2 (scale 2.7)", build=MiniFASNetV2, weights="2.7_80x80_MiniFASNetV2.pth",
         onnx="minifasnet_v2_s2.7.onnx", yakhyo="yakhyo_MiniFASNetV2.onnx"),
    dict(label="V1SE (scale 4.0)", build=MiniFASNetV1SE, weights="4_0_0_80x80_MiniFASNetV1SE.pth",
         onnx="minifasnet_v1se_s4.0.onnx", yakhyo="yakhyo_MiniFASNetV1SE.onnx"),
]
OPSET = 13
CHECK_INPUTS = 20
TOLERANCE = 1e-3


def load_model(build, weights_path):
    model = build(embedding_size=128, conv6_kernel=(5, 5), num_classes=3)
    state = torch.load(weights_path, map_location="cpu", weights_only=True)
    state = {(k[7:] if k.startswith("module.") else k): v for k, v in state.items()}
    model.load_state_dict(state, strict=True)
    return model.eval()


def export(model, out_path):
    dummy = torch.zeros(1, 3, 80, 80, dtype=torch.float32)
    torch.onnx.export(model, (dummy,), str(out_path), input_names=["input"], output_names=["output"],
                      opset_version=OPSET, do_constant_folding=True, dynamo=False)


def softmax(x):
    e = np.exp(x - x.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--yakhyo", action="store_true", help="cross-check against yakhyo's ONNX files (fetch with --extras)")
    args = ap.parse_args()

    rng = np.random.default_rng(20261023)
    inputs = rng.uniform(0.0, 255.0, size=(CHECK_INPUTS, 1, 3, 80, 80)).astype(np.float32)
    failed = False
    for spec in EXPORTS:
        model = load_model(spec["build"], CACHE / spec["weights"])
        out_path = MODELS / spec["onnx"]
        export(model, out_path)

        sess = ort.InferenceSession(str(out_path), providers=["CPUExecutionProvider"])
        with torch.no_grad():
            ref = np.concatenate([model(torch.from_numpy(x)).numpy() for x in inputs])
        got = np.concatenate([sess.run(["output"], {"input": x})[0] for x in inputs])
        diff = float(np.abs(ref - got).max())
        ok = diff < TOLERANCE
        failed |= not ok
        print(f"{spec['label']}: wrote {out_path.name} ({out_path.stat().st_size} bytes); "
              f"torch vs ORT max |logit diff| over {CHECK_INPUTS} inputs = {diff:.3e} "
              f"[{'ok' if ok else 'FAIL'} < {TOLERANCE}]")
        print(f"    softmax[1] range on random inputs: {softmax(got)[:, 1].min():.4f} .. {softmax(got)[:, 1].max():.4f}")

        if args.yakhyo:
            ypath = CACHE / spec["yakhyo"]
            ysess = ort.InferenceSession(str(ypath), providers=["CPUExecutionProvider"])
            yin, yout = ysess.get_inputs()[0], ysess.get_outputs()[0]
            ylogits = np.concatenate([ysess.run([yout.name], {yin.name: x})[0] for x in inputs])
            ydiff = float(np.abs(softmax(ylogits)[:, 1] - softmax(got)[:, 1]).max())
            print(f"    yakhyo {ypath.name}: input {yin.name}{yin.shape} output {yout.name}{yout.shape}; "
                  f"max |softmax[1] diff| = {ydiff:.3e}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
