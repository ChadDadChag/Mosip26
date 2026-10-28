"""Replays logged live sessions through the session print guards, offline (development only).

Reads SessionDemo logs (`--log-dir`, one JSON line per session with "frameFields", "frames" and "events"; numbers
and codes only, no images) and recomputes every frame's score with reference_pipeline.session_guards, the spec of
the analyzer's PAD latch and volatility cap. Then it replays Part 3's passive decision per attempt (each STARTED
event restarts the window): the first full window of 10 eligible frames (one face, no quality issue, a score,
|yaw| and |pitch| at most 25 deg) decides PASS (median >= 0.85), ATTACK (< 0.30) or CHALLENGE.

The score before the guards is rebuilt from the logged model outputs and moire cap flag, so logs recorded with or
without the guards replay the same way. Prints aggregates per attempt; the logs stay in eval-data/.

Recorded takes replay the same way from PadEvaluation's frames.csv (the same per-frame numbers; one take is one
attempt), filtered by subject and species. --vol sets the volatility cap's threshold (volatilityMinScore) for the
replay, for example 0.80 to compare with the default 0.85.

    python session_guard_replay.py eval-data/sessions/sessions-<time>.jsonl [...]
    python session_guard_replay.py --frames-csv <pad-eval>/frames.csv [--subject s02] [--species bona_fide] [--vol 0.80]
"""
import argparse
import csv
import json
import statistics
import sys

import reference_pipeline as rp

WINDOW, PASSIVE, REJECT, MAX_POSE = 10, 0.85, 0.30, 25.0


def frames_of(run):
    names = run["frameFields"]
    return [dict(zip(names, f)) for f in run["frames"]]


def before_guards(f):
    """The score after the moire cap only: the mean of the two models, capped at 0.60 when the cap applied."""
    if f["faces"] != 1 or f.get("v2Live") is None or f.get("v1seLive") is None:
        return None
    model = (f["v2Live"] + f["v1seLive"]) / 2.0
    return min(model, rp.MOIRE_CAP_SCORE) if f.get("moireCap") == 1 else model


def eligible(f, score):
    return (f["faces"] == 1 and not f["issues"] and score is not None and f["yaw"] is not None
            and f["pitch"] is not None and abs(f["yaw"]) <= MAX_POSE and abs(f["pitch"]) <= MAX_POSE)


def first_window(frames, scores):
    """Part 3's PassiveEvaluator: the first full window's verdict and median, or (None, None)."""
    window = []
    for f, s in zip(frames, scores):
        if f["faces"] != 1:
            window = []
        elif eligible(f, s):
            window = (window + [s])[-WINDOW:]
            if len(window) == WINDOW:
                m = statistics.median(window)
                return ("ATTACK" if m < REJECT else "PASS" if m >= PASSIVE else "CHALLENGE"), m
    return None, None


def replay(run, cfg):
    frames = frames_of(run)
    model = [None if f["faces"] != 1 or f.get("v2Live") is None else (f["v2Live"] + f["v1seLive"]) / 2.0
             for f in frames]
    before = [before_guards(f) for f in frames]
    guarded = rp.session_guards([{"faceCount": f["faces"], "modelScore": m, "score": b}
                                 for f, m, b in zip(frames, model, before)], cfg)
    after = [g["livenessScore"] for g in guarded]
    starts = [int(e.split("ms")[0]) for e in run["events"] if e.endswith(" STARTED")] or [0]
    ends = starts[1:] + [float("inf")]
    latch_t = next((f["t"] for f, g in zip(frames, guarded) if g["padLatch"]), None)
    rows = []
    for a, (s, e) in enumerate(zip(starts, ends), 1):
        idx = [i for i, f in enumerate(frames) if s <= f["t"] < e]
        fr = [frames[i] for i in idx]
        vb, mb = first_window(fr, [before[i] for i in idx])
        va, ma = first_window(fr, [after[i] for i in idx])
        rows.append((a, len(idx), vb, mb, va, ma, sum(1 for i in idx if guarded[i]["volatilityCap"]),
                     sum(1 for i in idx if guarded[i]["padLatch"])))
    return rows, latch_t


FRAME_FIELDS = ["t", "faces", "issues", "yaw", "pitch", "v2Live", "v1seLive", "moireCap"]


def _num(v):
    return None if v in ("", "NaN", "nan", None) else float(v)


def takes_from_frames_csv(path, subjects=None, species=None):
    """PadEvaluation's frames.csv as replayable runs, one per take (one attempt that starts at 0 ms). The run's
    "case" is the species and its "item" subject/take; "subject", "species" and "condition" are kept too."""
    runs = {}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if (subjects and r["subject"] not in subjects) or (species and r["species"] not in species):
                continue
            key = (r["species"], r["subject"], r["take"])
            run = runs.setdefault(key, {"case": r["species"], "item": "%s/%s" % (r["subject"], r["take"]),
                                        "subject": r["subject"], "species": r["species"],
                                        "condition": r["condition"], "frameFields": FRAME_FIELDS, "frames": [],
                                        "events": ["0ms STARTED"]})
            run["frames"].append([int(float(r["t_ms"])), int(r["faces"]), [i for i in r["issues"].split("|") if i],
                                  _num(r["yaw"]), _num(r["pitch"]), _num(r["v2"]), _num(r["v1se"]),
                                  1 if r["moire_cap"] == "1" else 0])
    return list(runs.values())


def logged_runs(path):
    """The runs of one SessionDemo log that carry frames."""
    with open(path, encoding="utf-8") as f:
        return [run for run in map(json.loads, f) if "frameFields" in run]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("logs", nargs="*")
    ap.add_argument("--frames-csv", help="PadEvaluation frames.csv: replay recorded takes instead of logs")
    ap.add_argument("--subject", action="append", help="with --frames-csv: only these subjects")
    ap.add_argument("--species", action="append", help="with --frames-csv: only these species")
    ap.add_argument("--vol", type=float, default=rp.VOLATILITY_MIN_SCORE, help="volatilityMinScore of the replay")
    ap.add_argument("--latch-off", action="store_true")
    ap.add_argument("--volatility-off", action="store_true")
    args = ap.parse_args()
    if not args.logs and not args.frames_csv:
        ap.error("give session logs or --frames-csv")
    cfg = rp.guards_cfg(latch=None if args.latch_off else rp.PAD_LATCH_THRESHOLD,
                        vol=None if args.volatility_off else args.vol)
    fmt = lambda v: "-" if v is None else "%.3f" % v  # noqa: E731
    runs = [run for path in args.logs for run in logged_runs(path)]
    if args.frames_csv:
        runs += takes_from_frames_csv(args.frames_csv, args.subject, args.species)
    for run in runs:
        rows, latch_t = replay(run, cfg)
        print("%s item %s: logged %s %s %s; latch %s" % (
            run["case"], run.get("item"), run.get("decision"), run.get("passPath"), run.get("failureCode"),
            "never" if latch_t is None else "at %d ms" % latch_t))
        for a, n, vb, mb, va, ma, vol, lat in rows:
            print("  attempt %d: %3d frames; first window before %-9s %s -> with guards %-9s %s;"
                  " volatility-capped %d, latched %d" % (a, n, vb or "none", fmt(mb), va or "none", fmt(ma),
                                                       vol, lat))
    return 0


if __name__ == "__main__":
    sys.exit(main())
