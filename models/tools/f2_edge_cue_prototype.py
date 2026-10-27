"""F2 prototype: the bezel/edge screen cue of docs/part2-f2-screen-cue-design.md section 3.1, evaluated offline.

Development tool only; results and decision: docs/part2-f2-screen-cue-design.md section 6.1 (does not ship).

    python -I models/tools/f2_edge_cue_prototype.py [--eval-data DIR]

RESULT (2026-10-26, single subject s01, provisional): the cue does NOT separate the close-up "fill" screen attacks
(screen_photo_close *_fill, the moire-weak case) from bona fide at zero bona fide cost, so it was not ported to Java.
This script reproduces the section 6.1 tables (aggregates only).

This is NOT part of the executable spec (reference_pipeline.py): no Java code mirrors it.

The cue, per frame with exactly one face (Hough-free, +, -, *, / and comparisons only):
  1. The V1SE 4.0x region (reference_pipeline.get_new_box on pad_box), resampled to 128 x 128 grey
     (sample_affine + to_gray), 3x3 Sobel on the interior.
  2. Strong pixels: gx^2 + gy^2 >= EDGE_MAG^2, centre outside the face box. Two orientation families:
     V (|gx| >= 2|gy|, lines within +-26.6 deg of vertical) and H (|gy| >= 2|gx|).
  3. Non-maximum suppression across the edge (>= the previous pixel, > the next one).
  4. Votes into sheared projections: slopes -0.4..0.4 in 0.1 steps, position p = across - slope x along (relative
     to the crop centre), 2-px bins; strength = votes of a 2-bin window. A line = strength >= EDGE_MIN_VOTES; greedy,
     at most 4 per family, 3 bins apart.
  5. Each line in frame coordinates at the face centre (V: x at the face centre row; H: y at the face centre
     column), and its side of the face: L, R (V) or T, B (H).
Co-motion between consecutive single-face frames (any other face count resets): d_face = the face centre's
displacement; a line is matched to the nearest previous line of the same family and side, slope within 0.1 and
position within 20 px. With d_ref = d_face.x (V) or .y (H): |d_ref| < 2 px gives no verdict; |d_line - d_ref| <=
|d_ref| / 2 is COMOVE (rigid with the face); |d_line| <= |d_ref| / 2 is STATIC (background). A side keeps its last
verdict while it has a line. edgeScore = the number of sides (0..4) with a COMOVE line.
Session statistic: the rolling median of edgeScore over the last 10 single-face frames (at least 3), as for moire.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

import reference_pipeline as rp

EDGE_SIZE = 128
EDGE_SCALE = 4.0
EDGE_RATIO = 2.0
EDGE_SLOPES = tuple(k / 10.0 for k in range(-4, 5))
EDGE_BIN = 2.0
EDGE_SHIFT = 90.0
EDGE_BINS = 90
EDGE_MAX_LINES = 4
EDGE_SUPPRESS_BINS = 3
MOVE_MIN = 2.0
WINDOW, MIN_FRAMES = 10, 3
# Detector settings evaluated: (Sobel magnitude, minimum votes). The first is the design note's (a clean step of
# >= 24 grey levels, half the crop side); the others are more sensitive, to rule out a threshold artefact.
SETTINGS = ((96.0, 64), (64.0, 48), (48.0, 38), (32.0, 32))


def _family_lines(major, minor, mask, pos, other, min_votes):
    am, an = np.abs(major), np.abs(minor)
    fam = mask & (am >= EDGE_RATIO * an)
    prev = np.zeros_like(am)
    nxt = np.zeros_like(am)
    prev[:, 1:] = am[:, :-1]
    nxt[:, :-1] = am[:, 1:]
    fam &= (am >= prev) & (am > nxt)
    p0, o0 = pos[fam], other[fam]
    votes = np.zeros((len(EDGE_SLOPES), EDGE_BINS + 1))
    sums = np.zeros_like(votes)
    for si, s in enumerate(EDGE_SLOPES):
        p = p0 - s * o0
        k = np.floor((p + EDGE_SHIFT) / EDGE_BIN).astype(int)
        np.add.at(votes[si], k, 1.0)
        np.add.at(sums[si], k, p)
    strength = votes[:, :-1] + votes[:, 1:]
    psum = sums[:, :-1] + sums[:, 1:]
    lines = []
    for _ in range(EDGE_MAX_LINES):
        si, k = np.unravel_index(int(np.argmax(strength)), strength.shape)
        n = strength[si, k]
        if n < min_votes:
            break
        lines.append((EDGE_SLOPES[si], psum[si, k] / n, int(n)))
        strength[:, max(0, k - EDGE_SUPPRESS_BINS):k + EDGE_SUPPRESS_BINS + 1] = -1
    return lines


def edge_lines(bgr, box, mag, min_votes):
    """The lines of one frame (steps 1 to 5 above) and the face centre."""
    H, W = bgr.shape[:2]
    l, t, r, b = rp.get_new_box(W, H, rp.pad_box(box), EDGE_SCALE)
    cw, ch = r - l + 1, b - t + 1
    N = EDGE_SIZE
    g = rp.to_gray(rp.sample_affine(bgr, rp.crop_matrix(l, t, cw, ch, N, N), N, N)).astype(np.float64)
    gx = (g[:-2, 2:] + 2 * g[1:-1, 2:] + g[2:, 2:]) - (g[:-2, :-2] + 2 * g[1:-1, :-2] + g[2:, :-2])
    gy = (g[2:, :-2] + 2 * g[2:, 1:-1] + g[2:, 2:]) - (g[:-2, :-2] + 2 * g[:-2, 1:-1] + g[:-2, 2:])
    sx, sy = cw / N, ch / N
    x, y, w, h = box
    jj, ii = np.meshgrid(np.arange(1, N - 1), np.arange(1, N - 1))
    u, v = jj + 0.5, ii + 0.5
    inside = (u >= (x - l) / sx) & (u < (x + w - l) / sx) & (v >= (y - t) / sy) & (v < (y + h - t) / sy)
    mask = (gx * gx + gy * gy >= mag * mag) & ~inside
    c = N / 2.0
    cxf, cyf = x + w / 2.0, y + h / 2.0
    ufc, vfc = (cxf - l) / sx, (cyf - t) / sy
    out = []
    for s, p, n in _family_lines(gx, gy, mask, u - c, v - c, min_votes):
        X = l + sx * (c + p + s * (vfc - c))
        out.append({"fam": "V", "side": "L" if X < cxf else "R", "pos": X, "slope": s, "votes": n})
    for s, p, n in _family_lines(gy.T, gx.T, mask.T, (v - c).T, (u - c).T, min_votes):
        Y = t + sy * (c + p + s * (ufc - c))
        out.append({"fam": "H", "side": "T" if Y < cyf else "B", "pos": Y, "slope": s, "votes": n})
    return {"lines": out, "face": (cxf, cyf)}


def _match(line, prev):
    best = None
    for q in prev:
        if q["fam"] != line["fam"] or q["side"] != line["side"] or abs(q["slope"] - line["slope"]) > 0.1 + 1e-9:
            continue
        d = abs(q["pos"] - line["pos"])
        if d <= 20.0 and (best is None or d < abs(best["pos"] - line["pos"])):
            best = q
    return best


def session(per_frame):
    """per_frame: edge_lines results in order, None for a frame without exactly one face.
    Returns (rolling-median trace, edgeScore per single-face frame)."""
    buf, verdict, prev, prev_face = [], {}, None, None
    trace, scores = [], []
    for p in per_frame:
        if p is None:
            buf, verdict, prev, prev_face = [], {}, None, None
            continue
        present = {ln["side"] for ln in p["lines"]}
        verdict = {s: v for s, v in verdict.items() if s in present}
        if prev is not None:
            dfx, dfy = p["face"][0] - prev_face[0], p["face"][1] - prev_face[1]
            for ln in p["lines"]:
                q = _match(ln, prev)
                if q is None:
                    continue
                d_ref = dfx if ln["fam"] == "V" else dfy
                if abs(d_ref) < MOVE_MIN:
                    continue
                d_line = ln["pos"] - q["pos"]
                if abs(d_line - d_ref) <= abs(d_ref) / 2:
                    verdict[ln["side"]] = "COMOVE"
                elif abs(d_line) <= abs(d_ref) / 2:
                    verdict[ln["side"]] = "STATIC"
        score = sum(1 for s in present if verdict.get(s) == "COMOVE")
        scores.append(score)
        buf = (buf + [score])[-WINDOW:]
        trace.append(float(np.median(buf)) if len(buf) >= MIN_FRAMES else None)
        prev, prev_face = p["lines"], p["face"]
    return trace, scores


def _group(species, condition):
    if species == "screen_photo_close":
        return "screen_photo_close fill" if condition.endswith("_fill") else "screen_photo_close small"
    return species + (" " + condition if species == "bona_fide" else "")


def _takes(eval_dir):
    for meta in sorted(eval_dir.glob("bona_fide/s*/*/meta.json")) + sorted(eval_dir.glob("attack/*/s*/*/meta.json")):
        yield meta.parent, json.loads(meta.read_text(encoding="utf-8"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--eval-data", default=str(rp.MODELS.parent / "eval-data"))
    args = ap.parse_args()
    eval_dir = Path(args.eval_data)
    moire = {}
    scatter = eval_dir / "reports" / "pad-eval-20261024-cap" / "scatter.csv"
    if scatter.exists():
        with open(scatter, newline="") as f:
            for r in csv.DictReader(f):
                moire[(r["species"], int(r["take"]))] = float(r["median_moire"])
    yunet = rp.YuNet(rp.MODELS / rp.YUNET_FILE)
    takes = []
    for d, meta in _takes(eval_dir):
        frames = []
        for jpg in sorted(d.glob("frame_*.jpg")):
            bgr = rp.imread_bgr(str(jpg))
            n, primary = rp.count_faces(yunet.detect(bgr))
            frames.append((bgr, primary["box"] if n == 1 else None))
        takes.append((meta["label"], meta["condition"], meta["take"], frames))
    print(f"{len(takes)} takes, {sum(len(t[3]) for t in takes)} frames (aggregates only)")
    for mag, votes in SETTINGS:
        stats = {}
        for species, cond, take, frames in takes:
            per = [None if box is None else edge_lines(bgr, box, mag, votes) for bgr, box in frames]
            trace, _ = session(per)
            vals = [v for v in trace if v is not None] or [0.0]
            g = _group(species, cond)
            stats.setdefault(g, []).append((max(vals), float(np.median(vals)), moire.get((species, take))))
        bf_max = max(mx for g, rows in stats.items() if g.startswith("bona_fide") for mx, _, _ in rows)
        tau = bf_max + 0.5
        print(f"\nSobel >= {mag:.0f} (step {mag / 4:.0f} grey levels), line >= {votes} votes. Bona fide max rolling "
              f"median {bf_max:.1f}, so the 0-bona-fide-cost threshold is {tau:.1f}.")
        print("| group | takes | session max | session median | trips at threshold | moire (median per take) |")
        print("|---|---|---|---|---|---|")
        for g in sorted(stats):
            rows = stats[g]
            mx = [r[0] for r in rows]
            md = [r[1] for r in rows]
            mo = [r[2] for r in rows if r[2] is not None]
            trips = sum(1 for m in mx if m >= tau)
            mo_txt = f"{min(mo):.2f}-{max(mo):.2f}" if mo else "n/a"
            print(f"| {g} | {len(rows)} | {min(mx):.1f}-{max(mx):.1f} | {min(md):.1f}-{max(md):.1f} | "
                  f"{trips}/{len(rows)} | {mo_txt} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
