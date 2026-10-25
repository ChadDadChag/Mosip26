"""Live sanity check of the reference pipeline on webcam 0 (needs a person at the camera).

    python -I models/tools/sanity_webcam.py [--camera 0] [--seconds 10]

A window shows the camera (NOT mirrored: what the camera sees) with the face box, the 12 eye
points, EAR, yaw/pitch/roll, p_v2[1], p_v1se[1], livenessScore, faceCount and ms per frame.
It guides you through these presentations; press SPACE to start each, S to skip, Q to quit.
A beep marks the start and the end of every recording.

  A1  your real face, eyes open, looking straight     A4  turn your head to YOUR left
  A2  5 slow, firm blinks                             A5  turn your head to YOUR right
  A3  eyes closed from the first beep to the second
  B   a printed photo of a face                       C   a face photo full-screen on a phone
  D   a face video playing on a phone or laptop
For B, C and D keep your own face out of the camera's view (the overlay should say "faces 1").

Every analysed frame is printed with the running 10-frame median of livenessScore. At the end
a per-presentation summary is printed and saved to .cache/sanity_webcam_summary.json, with
checks: per eye, blink minimum / open median and closed-hold median / open median must be at
most 0.55 (Person 3's BlinkDetector needs < 0.6 closed and > 0.85 reopened), and the turn to
the subject's own left must give positive yaw. No images are stored. This is a sanity check,
not the ISO 30107-3 evaluation.
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
EAR_RATIO_GOAL = 0.55

STEPS = [  # (label, what to present, how, seconds; None = --seconds)
    ("A1", "YOUR REAL FACE", "eyes OPEN, look straight at the camera", 5),
    ("A2", "YOUR REAL FACE", "blink 5 times, slowly and firmly (close fully each time)", 8),
    ("A3", "YOUR REAL FACE", "close your eyes at the beep, open them at the second beep", 3),
    ("A4", "YOUR REAL FACE", "turn your head slowly to YOUR LEFT, as far as comfortable", 4),
    ("A5", "YOUR REAL FACE", "turn your head slowly to YOUR RIGHT, as far as comfortable", 4),
    ("B", "PRINTED PHOTO of a face", "hold the paper up; keep YOUR face out of view (S = no printer)", None),
    ("C", "PHONE showing a face PHOTO full-screen", "hold it up; keep YOUR face out of view", None),
    ("D", "PHONE/LAPTOP playing a face VIDEO", "hold it up; keep YOUR face out of view", None),
]


def beep(freq, ms):
    if sys.platform == "win32":
        import winsound
        winsound.Beep(freq, ms)


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


def summarize(step, rows, seconds):
    face = [r for r in rows if r["faces"] == 1]
    scores = [r["score"] for r in face if r["score"] is not None]
    meds = [r["med10"] for r in rows if r["med10"] is not None and r["n10"] >= 10]
    yaws = [r["yaw"] for r in face if r["yaw"] is not None]
    out = {
        "step": step, "frames": len(rows), "framesOneFace": len(face),
        "framesMultiFace": sum(1 for r in rows if r["faces"] > 1),
        "framesWithoutLandmarks": sum(1 for r in face if r["earL"] is None),
        "scoreMedian": median(scores), "scoreMin": min(scores) if scores else None,
        "scoreMax": max(scores) if scores else None,
        "median10Min": min(meds) if meds else None, "median10Max": max(meds) if meds else None,
        "pV2Median": median([r["p2"] for r in face if r["p2"] is not None]),
        "pV1seMedian": median([r["p1"] for r in face if r["p1"] is not None]),
        "yawMedian": median(yaws), "yawMin": min(yaws) if yaws else None, "yawMax": max(yaws) if yaws else None,
        "pitchMedian": median([r["pitch"] for r in face if r["pitch"] is not None]),
        "msMedian": median([r["ms"] for r in rows]),
    }
    for eye in ("earL", "earR"):
        ears = [r[eye] for r in face if r[eye] is not None]
        # "hold": the middle of the recording, away from the reaction time after each beep
        hold = [r[eye] for r in face if r[eye] is not None and 0.75 <= r["t"] <= seconds - 0.25]
        out[eye + "Median"], out[eye + "Min"], out[eye + "HoldMedian"] = median(ears), min(ears) if ears else None, median(hold)
    return out


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
    beep(880, 150)
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
    beep(660, 300)
    return rows, False


def quality_frames(cap, pipe, n):
    """Quality metrics of n frames with exactly one face (for the blur-threshold study). Waits for SPACE."""
    if not wait_for_start(cap, pipe, "Q", "YOUR REAL FACE", f"look at the camera normally for {n} frames"):
        return
    beep(880, 150)
    lap, lap15, lap3 = [], [], []
    while len(lap) < n:
        ok, frame = cap.read()
        if not ok:
            continue
        res = pipe.analyze(frame)
        if res["faceCount"] != 1:
            continue
        q = res["quality"]
        box = res["faceBox"]
        # the same frame Gaussian-blurred in memory (blur study); nothing is stored
        b15 = rp.quality(cv2.GaussianBlur(frame, (0, 0), 1.5), box)["lapVar"]
        b3 = rp.quality(cv2.GaussianBlur(frame, (0, 0), 3.0), box)["lapVar"]
        lap.append(q["lapVar"])
        lap15.append(b15)
        lap3.append(b3)
        print(f"Q {len(lap):2d} lapVar {q['lapVar']:8.2f} sigma1.5 {b15:7.2f} sigma3 {b3:6.2f} luma {q['lumaMean']:6.1f} "
              f"faceW {q['faceWidthPx']:6.1f}px area {q['areaFrac']:.3f} issues {q['issues']} "
              f"moire {res['replay']['moire']:.3f} glare {res['replay']['glare']:.4f}", flush=True)
        img = draw(frame, res, None)
        put(img, f"RECORDING quality frame {len(lap)}/{n}", 5, (0, 0, 255))
        cv2.imshow(WINDOW, img)
        cv2.waitKey(1)
    beep(660, 300)
    cap.release()
    cv2.destroyAllWindows()
    for label, values in (("sharp", lap), ("sigma1.5", lap15), ("sigma3", lap3)):
        print(f"QUALITY lapVar {label:<8} over {n} frames: min {min(values):.2f} median {median(values):.2f} "
              f"max {max(values):.2f}", flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--seconds", type=float, default=10.0, help="recording length for B, C and D")
    ap.add_argument("--quality-frames", type=int, default=0,
                    help="instead of the guided steps: print the quality metrics of N one-face frames, then exit")
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
    if args.quality_frames > 0:
        quality_frames(cap, pipe, args.quality_frames)
        return

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
        summaries.append(summarize(label, rows, seconds or args.seconds))
        if quit_all:
            break
    cap.release()
    cv2.destroyAllWindows()

    by = {s["step"]: s for s in summaries if not s.get("skipped")}
    checks = {}
    for eye in ("earL", "earR"):
        open_med = by.get("A1", {}).get(eye + "Median")
        if not open_med:
            continue
        blink_min = by.get("A2", {}).get(eye + "Min")
        hold = by.get("A3", {}).get(eye + "HoldMedian")
        if blink_min is not None:
            checks[f"{eye} blinkMin/open = {blink_min / open_med:.2f} <= {EAR_RATIO_GOAL}"] = blink_min / open_med <= EAR_RATIO_GOAL
        if hold is not None:
            checks[f"{eye} closedHold/open = {hold / open_med:.2f} <= {EAR_RATIO_GOAL}"] = hold / open_med <= EAR_RATIO_GOAL
    if by.get("A4", {}).get("yawMedian") is not None:
        checks["turnToOwnLeftGivesPositiveYaw"] = by["A4"]["yawMedian"] > 0
    if by.get("A5", {}).get("yawMedian") is not None:
        checks["turnToOwnRightGivesNegativeYaw"] = by["A5"]["yawMedian"] < 0
    report = {"camera": args.camera, "frameSize": [frame.shape[1], frame.shape[0]], "steps": summaries, "checks": checks}
    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    SUMMARY.write_text(json.dumps(report, indent=1), encoding="utf-8")
    print("SUMMARY " + json.dumps(report, indent=1), flush=True)


if __name__ == "__main__":
    main()
