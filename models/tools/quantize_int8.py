"""INT8 evaluation (P5-5 B): static QDQ quantisation of the mesh and both MiniFASNets, then fp32 vs int8 deltas.

    python -I models/tools/quantize_int8.py [--out models/tools/.cache/int8] [--method minmax|percentile|entropy]
                                            [--activations s8|u8] [--eval-data eval-data] [--json report.json]
    python -I models/tools/quantize_int8.py --compare-only [--out <dir>] [--eval-data eval-data] [--eval-stride 3]

MEASUREMENT ONLY: nothing here is shipped, and the int8 files stay under the gitignored .cache/.

Calibration (public data only, never the team's recordings):
  * the 49 public-domain portraits of pitch_calibration_urls.txt (.cache/pitch-calibration/, fetched by
    calibrate_pitch.py; each SHA-256 is checked) and the two golden images, each also mirrored;
  * every face YuNet (fp32) finds, cropped EXACTLY as the analyzer does: mesh_input(mesh_roi(face)) for the mesh,
    pad_input(get_new_box(pad_box(box), scale)) for MiniFASNet V2 (2.7) and V1SE (4.0).
Quantisation: quant_pre_process, then quantize_static with QuantFormat.QDQ, per-channel weights, S8S8 (ONNX Runtime's
recommended first choice; --activations u8 for U8S8). YuNet stays fp32: opencv_zoo publishes an int8 YuNet only for
2023mar, not for the 2026may model the bundle uses. Results and the decision: models/README.md, "INT8 evaluation".

The output folder holds a full bundle layout (yunet copied, the three int8 models under the bundle's file names), so
ModelBundleTool can sign it as a LOCAL test bundle with the dev key and the Java tools can load it.

Comparison (printed, aggregates only): the reference pipeline with the fp32 models vs the int8 folder on the golden
images (and mirrors), the calibration portraits, and, with --eval-data, every Nth frame of the local recordings
(eval-data is used to MEASURE only, never to calibrate; no per-frame values are written).
"""
import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import reference_pipeline as rp  # noqa: E402

CALIBRATION_LIST = TOOLS / "pitch_calibration_urls.txt"
CALIBRATION_CACHE = TOOLS / ".cache" / "pitch-calibration"
GOLDEN = TOOLS.parent.parent / "liveness-analyzer" / "src" / "test" / "resources" / "golden"
DEFAULT_OUT = TOOLS / ".cache" / "int8"
QUANTISED = (rp.MESH_FILE, rp.PAD_V2_FILE, rp.PAD_V1SE_FILE)


def calibration_images():
    """(name, bgr) for each checked calibration portrait and golden image, plus each one mirrored."""
    images = []
    for line in CALIBRATION_LIST.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        sha = line.split("\t")[1]
        local = CALIBRATION_CACHE / (sha[:16] + ".jpg")
        if not local.exists():
            sys.exit(f"missing {local}: run calibrate_pitch.py first")
        if hashlib.sha256(local.read_bytes()).hexdigest() != sha:
            sys.exit(f"SHA-256 mismatch for {local}")
        images.append((local.name, rp.imread_bgr(local)))
    for png in sorted(GOLDEN.glob("*.png")):
        images.append((png.name, rp.imread_bgr(png)))
    return images + [(name + ":mirrored", np.ascontiguousarray(bgr[:, ::-1])) for name, bgr in images]


def calibration_tensors(images):
    """Model input tensors for every detected face, cropped as the analyzer crops them."""
    yunet = rp.YuNet(TOOLS.parent / rp.YUNET_FILE)
    mesh, v2, v1se = [], [], []
    for _, bgr in images:
        h, w = bgr.shape[:2]
        for face in yunet.detect(bgr):
            mesh.append(rp.mesh_input(bgr, rp.mesh_roi(face)))
            ibox = rp.pad_box(face["box"])
            v2.append(rp.pad_input(bgr, rp.get_new_box(w, h, ibox, rp.PAD_V2_SCALE)))
            v1se.append(rp.pad_input(bgr, rp.get_new_box(w, h, ibox, rp.PAD_V1SE_SCALE)))
    return {rp.MESH_FILE: (rp.MESH_INPUT, mesh), rp.PAD_V2_FILE: ("input", v2), rp.PAD_V1SE_FILE: ("input", v1se)}


def quantise(out, method, activations):
    from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
                                          quantize_static)
    from onnxruntime.quantization.shape_inference import quant_pre_process

    class Reader(CalibrationDataReader):
        def __init__(self, name, tensors):
            self.feeds = iter([{name: t} for t in tensors])

        def get_next(self):
            return next(self.feeds, None)

    methods = {"minmax": CalibrationMethod.MinMax, "percentile": CalibrationMethod.Percentile,
               "entropy": CalibrationMethod.Entropy}
    out.mkdir(parents=True, exist_ok=True)
    work = out / "work"
    work.mkdir(exist_ok=True)
    images = calibration_images()
    tensors = calibration_tensors(images)
    print(f"calibration: {len(images)} images (incl. mirrors), {len(tensors[rp.MESH_FILE][1])} faces")
    shutil.copyfile(TOOLS.parent / rp.YUNET_FILE, out / rp.YUNET_FILE)
    for name in QUANTISED:
        src, pre = TOOLS.parent / name, work / (name + ".pre.onnx")
        try:
            quant_pre_process(str(src), str(pre))
        except Exception as e:  # symbolic shape inference does not know every converted op
            print(f"  {name}: quant_pre_process with symbolic shapes failed ({e}); retrying without")
            quant_pre_process(str(src), str(pre), skip_symbolic_shape=True)
        input_name, feeds = tensors[name]
        t0 = time.perf_counter()
        quantize_static(str(pre), str(out / name), Reader(input_name, feeds), quant_format=QuantFormat.QDQ,
                        per_channel=True, weight_type=QuantType.QInt8,
                        activation_type=QuantType.QUInt8 if activations == "u8" else QuantType.QInt8,
                        calibrate_method=methods[method])
        print(f"  {name}: {src.stat().st_size} -> {(out / name).stat().st_size} bytes "
              f"({time.perf_counter() - t0:.1f} s, {method}, {activations} activations)")
    shutil.rmtree(work)


# ---------------------------------------------------------------------------------------------
# fp32 vs int8
# ---------------------------------------------------------------------------------------------

def pair_deltas(a, b):
    """Deltas between two analyze() results of the same frame (None when either side has no value)."""
    d = {}
    if a["meshPoints"] is not None and b["meshPoints"] is not None:
        pa, pb = np.asarray(a["meshPoints"])[:, :2], np.asarray(b["meshPoints"])[:, :2]
        e = np.hypot(*(pa - pb).T)
        d["mesh_mean_px"], d["mesh_max_px"] = float(e.mean()), float(e.max())
        named = lambda r: np.asarray([pt for k, v in r["landmarks"].items() if v is not None  # noqa: E731
                                      for pt in (v if isinstance(v[0], list) else [v])])
        d["named_max_px"] = float(np.hypot(*(named(a) - named(b)).T).max())
        d["face_px"] = float(max(a["faceBox"][2], a["faceBox"][3]))
        d["ear"] = max(abs(a["earLeft"] - b["earLeft"]), abs(a["earRight"] - b["earRight"]))
        d["yaw"], d["pitch"], d["roll"] = (abs(a[k] - b[k]) for k in ("yawDeg", "pitchDeg", "rollDeg"))
    if a["livenessScore"] is not None and b["livenessScore"] is not None:
        d["score"] = abs(a["livenessScore"] - b["livenessScore"])
        d["v2"] = abs(a["pad"]["v2"]["softmax"][1] - b["pad"]["v2"]["softmax"][1])
        d["v1se"] = abs(a["pad"]["v1se"]["softmax"][1] - b["pad"]["v1se"]["softmax"][1])
        d["zone_change"] = zone(a["livenessScore"]) != zone(b["livenessScore"])
    d["mesh_flag_change"] = (a["meshPoints"] is None) != (b["meshPoints"] is None)
    return d


def zone(score):
    """Per-frame zone against the default RESIDENT thresholds (pass >= 0.85, reject < 0.30)."""
    return "pass" if score >= 0.85 else "reject" if score < 0.30 else "challenge"


def aggregate(rows):
    """mean / p95 / max per metric over the rows that have it, plus counts of the boolean metrics."""
    agg = {"frames": len(rows)}
    keys = sorted({k for r in rows for k in r if k != "face_px"})
    for k in keys:
        vals = [r[k] for r in rows if k in r]
        if vals and isinstance(vals[0], bool):
            agg[k] = sum(vals)
        elif vals:
            v = np.asarray(vals, dtype=np.float64)
            agg[k] = {"mean": float(v.mean()), "p95": float(np.percentile(v, 95)), "max": float(v.max())}
    return agg


def timing(pipe, frames, repeat=3):
    """Median per-model inference ms over the frames (mesh and PAD tensors only, as the analyzer runs them)."""
    out = {"mesh": [], "pad_v2": [], "pad_v1se": []}
    for _ in range(repeat):
        for bgr in frames:
            res = pipe.analyze(bgr)
            if res["primary"] is None:
                continue
            roi = rp.mesh_roi(res["primary"])
            t = rp.mesh_input(bgr, roi)
            t0 = time.perf_counter()
            pipe.mesh.run(t)
            out["mesh"].append((time.perf_counter() - t0) * 1000)
            h, w = bgr.shape[:2]
            for key, model in (("pad_v2", pipe.pad_v2), ("pad_v1se", pipe.pad_v1se)):
                t = rp.pad_input(bgr, rp.get_new_box(w, h, rp.pad_box(res["primary"]["box"]), model.scale))
                t0 = time.perf_counter()
                model.run(t)
                out[key].append((time.perf_counter() - t0) * 1000)
    return {k: float(np.median(v)) for k, v in out.items() if v}


def eval_frames(root, stride):
    """(group, bgr) for every stride-th frame of each take under eval-data (group = bona_fide or the species)."""
    for meta in sorted(root.rglob("meta.json")):
        m = json.loads(meta.read_text(encoding="utf-8"))
        group = "bona_fide" if m["kind"] == "bona_fide" else m["label"]
        for f in sorted(meta.parent.glob("frame_*.jpg"))[::stride]:
            yield group, rp.imread_bgr(f)


def compare(out, eval_root, stride):
    fp32, int8 = rp.Pipeline(TOOLS.parent), rp.Pipeline(out)
    report = {}
    golden = [(p.name, rp.imread_bgr(p)) for p in sorted(GOLDEN.glob("*.png"))]
    golden += [(n + ":mirrored", np.ascontiguousarray(b[:, ::-1])) for n, b in golden]
    per_golden = {}
    for name, bgr in golden:
        a, b = fp32.analyze(bgr), int8.analyze(bgr)
        per_golden[name] = pair_deltas(a, b)
        per_golden[name]["score_fp32"], per_golden[name]["score_int8"] = a["livenessScore"], b["livenessScore"]
    report["golden"] = per_golden
    portraits = calibration_images()
    portraits = [p for p in portraits if not p[0].endswith(".png") and not p[0].endswith(".png:mirrored")]
    report["portraits"] = aggregate([pair_deltas(fp32.analyze(b), int8.analyze(b)) for _, b in portraits])
    if eval_root is not None:
        groups = {}
        for group, bgr in eval_frames(eval_root, stride):
            groups.setdefault(group, []).append(pair_deltas(fp32.analyze(bgr), int8.analyze(bgr)))
        report["eval_data"] = {g: aggregate(rows) for g, rows in groups.items()}
        report["eval_data"]["all"] = aggregate([r for rows in groups.values() for r in rows])
    sample = [b for _, b in golden[:2]] + [b for _, b in portraits[:8]]
    report["desktop_ms"] = {"fp32": timing(fp32, sample), "int8": timing(int8, sample)}
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--method", choices=("minmax", "percentile", "entropy"), default="minmax")
    ap.add_argument("--activations", choices=("s8", "u8"), default="s8", help="S8S8 (default) or U8S8")
    ap.add_argument("--compare-only", action="store_true", help="skip quantisation, compare the existing --out")
    ap.add_argument("--eval-data", type=Path, default=None, help="local recordings to measure on (aggregates only)")
    ap.add_argument("--eval-stride", type=int, default=3)
    ap.add_argument("--json", type=Path, default=None, help="write the aggregate report here")
    args = ap.parse_args()
    if not args.compare_only:
        quantise(args.out, args.method, args.activations)
    report = compare(args.out, args.eval_data, args.eval_stride)
    report["method"], report["activations"] = args.method, args.activations
    text = json.dumps(report, indent=1)
    print(text)
    if args.json:
        args.json.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
