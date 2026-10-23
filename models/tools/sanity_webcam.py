"""Live sanity check of the reference pipeline on webcam 0 (needs a person at the camera).

    python -I models/tools/sanity_webcam.py [--camera 0] [--seconds 10]

A window shows the camera (NOT mirrored: what the camera sees) with the face box, the 12 eye
points, EAR, yaw/pitch/roll, p_v2[1], p_v1se[1], livenessScore, faceCount and ms per frame.
It guides you through these presentations; press SPACE to start each, S to skip, Q to quit:

  A1  your real face, looking straight          A3  turn your head to YOUR left and hold
  A2  your real face: a few normal blinks, then A4  turn your head to YOUR right and hold
      two slow ones (eyes fully shut ~0.5 s)
  B   a printed photo of a face                 C   a face photo full-screen on a phone
  D   a face video playing on a phone or laptop
For B, C and D keep your own face out of the camera's view (the overlay should say "faces 1").

Every analysed frame is printed with the running 10-frame median of livenessScore. At the end
a per-presentation summary is printed and saved to .cache/sanity_webcam_summary.json. No
images are stored. This is a sanity check, not the ISO 30107-3 evaluation.
"""
import argparse
import json
import statistics
import sys
import time
from collections import deque
from pathlib import Path

import cv2
import numpy as np

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import reference_pipeline as rp  # noqa: E402

SUMMARY = TOOLS / ".cache" / "sanity_webcam_summary.json"
WINDOW = "MOSIP liveness sanity check (not mirrored)"
IDLE_TIMEOUT_S = 15 * 60

STEPS = [  # (label, what to present, how, seconds; None = --seconds)
    ("A1", "YOUR REAL FACE", "look straight at the camera", 4),
    ("A2", "YOUR REAL FACE", "blink 2-3 times, then close eyes fully ~0.5 s, twice", 6),
    ("A3", "YOUR REAL FACE", "turn your head to YOUR LEFT and hold", 3),
    ("A4", "YOUR REAL FACE", "turn your head to YOUR RIGHT and hold", 3),
    ("B", "PRINTED PHOTO of a face", "hold the paper up; keep YOUR face out of view (S = no printer)", None),
    ("C", "PHONE showing a face PHOTO full-screen", "hold it up; keep YOUR face out of view", None),
    ("D", "PHONE/LAPTOP playing a face VIDEO", "hold it up; keep YOUR face out of view", None),
]


def median(values):
    return statistics.median(values) if values else None


def put(img, text, row, color=(255, 255, 255), scale=0.55):
    y = 22 + row * 22
    cv2.putText(img, text, (8, y), cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 0, 0), 3, cv2.LINE_AA)
    cv2.putText(img, text, (8, y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, 1, cv2.LINE_AA)


def fmt(v, spec="{:+.1f}"):
    return "--" if v is None else spec.format(v)


def draw(frame, res, med10):
    out = frame.copy()
    if res["faceBox"] is not None:
        x, y, w, h = res["faceBox"]
        cv2.rectangle(out, (int(x), int(y)), (int(x + w), int(y + h)), (0, 255, 0), 2)
    if res["landmarks"] is not None:
        for eye in ("leftEye", "rightEye"):
            for px, py in res["landmarks"][eye]:
                cv2.circle(out, (int(round(px)), int(round(py))), 2, (0, 255, 255), -1)
    pad = res["pad"]
    p2 = pad["v2"]["softmax"][1] if pad else None
    p1 = pad["v1se"]["softmax"][1] if pad else None
    put(out, f"faces {res['faceCount']}  ms {res['timingsMs']['total']:.0f}", 0)
    put(out, f"EAR L {fmt(res['earLeft'], '{:.3f}')}  R {fmt(res['earRight'], '{:.3f}')}", 1)
    put(out, f"yaw {fmt(res['yawDeg'])}  pitch {fmt(res['pitchDeg'])}  roll {fmt(res['rollDeg'])}", 2)
    put(out, f"v2 {fmt(p2, '{:.3f}')}  v1se {fmt(p1, '{:.3f}')}  score {fmt(res['livenessScore'], '{:.3f}')}"
             f"  med10 {fmt(med10, '{:.3f}')}", 3, (0, 255, 255))
    return out


def summarize(step, rows):
    face = [r for r in rows if r["faces"] == 1]
    scores = [r["score"] for r in face if r["score"] is not None]
    meds = [r["med10"] for r in rows if r["med10"] is not None and r["n10"] >= 10]
    ears = [min(r["earL"], r["earR"]) for r in face if r["earL"] is not None]
    yaws = [r["yaw"] for r in face if r["yaw"] is not None]
    return {
        "step": step, "frames": len(rows), "framesOneFace": len(face),
        "framesMultiFace": sum(1 for r in rows if r["faces"] > 1),
        "scoreMedian": median(scores), "scoreMin": min(scores) if scores else None,
        "scoreMax": max(scores) if scores else None,
        "median10Min": min(meds) if meds else None, "median10Max": max(meds) if meds else None,
        "pV2Median": median([r["p2"] for r in face if r["p2"] is not None]),
        "pV1seMedian": median([r["p1"] for r in face if r["p1"] is not None]),
        "earMin": min(ears) if ears else None, "earMedian": median(ears),
        "yawMedian": median(yaws), "yawMin": min(yaws) if yaws else None, "yawMax": max(yaws) if yaws else None,
        "pitchMedian": median([r["pitch"] for r in face if r["pitch"] is not None]),
        "msMedian": median([r["ms"] for r in rows]),
    }


def wait_for_start(cap, pipe, label, what, how):
    """Live preview with the prompt until SPACE (True), S (False) or Q / timeout (None)."""
    t0 = time.time()
    while time.time() - t0 < IDLE_TIMEOUT_S:
        ok, frame = cap.read()
        if not ok:
            continue
        res = pipe.analyze(frame)
        img = draw(frame, res, None)
        put(img, f"NEXT {label}: {what}", 5, (0, 200, 255))
        put(img, how, 6, (0, 200, 255), 0.5)
        put(img, "SPACE = start   S = skip   Q = quit", 7, (0, 200, 255))
        cv2.imshow(WINDOW, img)
        key = cv2.waitKey(1) & 0xFF
        if key == ord(" "):
            return True
        if key in (ord("s"), ord("S")):
            return False
        if key in (ord("q"), ord("Q")):
            return None
    return None


def record(cap, pipe, label, what, how, seconds):
    rows, window = [], deque(maxlen=10)
    t0 = time.time()
    while time.time() - t0 < seconds:
        ok, frame = cap.read()
        if not ok:
            continue
        res = pipe.analyze(frame)
        if res["faceCount"] == 1 and res["livenessScore"] is not None:
            window.append(res["livenessScore"])
        med10 = statistics.median(window) if window else None
        pad = res["pad"]
        row = {"t": time.time() - t0, "faces": res["faceCount"], "score": res["livenessScore"],
               "p2": pad["v2"]["softmax"][1] if pad else None, "p1": pad["v1se"]["softmax"][1] if pad else None,
               "earL": res["earLeft"], "earR": res["earRight"], "yaw": res["yawDeg"], "pitch": res["pitchDeg"],
               "roll": res["rollDeg"], "ms": res["timingsMs"]["total"], "med10": med10, "n10": len(window)}
        rows.append(row)
        print(f"{label:<3} t={row['t']:4.1f}s faces={row['faces']} score={fmt(row['score'], '{:.3f}')} "
              f"med10={fmt(med10, '{:.3f}')} v2={fmt(row['p2'], '{:.3f}')} v1se={fmt(row['p1'], '{:.3f}')} "
              f"yaw={fmt(row['yaw'])} pitch={fmt(row['pitch'])} roll={fmt(row['roll'])} "
              f"earL={fmt(row['earL'], '{:.3f}')} earR={fmt(row['earR'], '{:.3f}')} ms={row['ms']:.0f}", flush=True)
        img = draw(frame, res, med10)
        put(img, f"RECORDING {label}: {what}  {seconds - (time.time() - t0):4.1f}s", 5, (0, 0, 255))
        put(img, how, 6, (0, 0, 255), 0.5)
        cv2.imshow(WINDOW, img)
        if (cv2.waitKey(1) & 0xFF) in (ord("q"), ord("Q")):
            return rows, True
    return rows, False


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--seconds", type=float, default=10.0, help="recording length for B, C and D")
    args = ap.parse_args()

    cap = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW) if sys.platform == "win32" else cv2.VideoCapture(args.camera)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    ok, frame = cap.read()
    if not ok:
        sys.exit(f"cannot read from camera {args.camera}")
    print(f"camera {args.camera}: {frame.shape[1]}x{frame.shape[0]}", flush=True)
    pipe = rp.Pipeline()
    cv2.namedWindow(WINDOW, cv2.WINDOW_AUTOSIZE)

    summaries, quit_all = [], False
    for label, what, how, seconds in STEPS:
        start = wait_for_start(cap, pipe, label, what, how)
        if start is None:
            break
        if not start:
            summaries.append({"step": label, "skipped": True})
            print(f"{label}: skipped", flush=True)
            continue
        rows, quit_all = record(cap, pipe, label, what, how, seconds or args.seconds)
        summaries.append(summarize(label, rows))
        if quit_all:
            break
    cap.release()
    cv2.destroyAllWindows()

    by = {s["step"]: s for s in summaries}
    checks = {}
    if "A2" in by and not by["A2"].get("skipped") and by["A2"]["earMin"] is not None:
        checks["blinkEarMinBelow0.15"] = by["A2"]["earMin"] < 0.15
    if "A3" in by and not by["A3"].get("skipped") and by["A3"]["yawMedian"] is not None:
        checks["turnToOwnLeftGivesPositiveYaw"] = by["A3"]["yawMedian"] > 0
    if "A4" in by and not by["A4"].get("skipped") and by["A4"]["yawMedian"] is not None:
        checks["turnToOwnRightGivesNegativeYaw"] = by["A4"]["yawMedian"] < 0
    report = {"camera": args.camera, "frameSize": [frame.shape[1], frame.shape[0]], "steps": summaries, "checks": checks}
    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    SUMMARY.write_text(json.dumps(report, indent=1), encoding="utf-8")
    print("SUMMARY " + json.dumps(report, indent=1), flush=True)


if __name__ == "__main__":
    main()
