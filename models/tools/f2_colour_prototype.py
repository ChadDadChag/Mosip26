"""F2 prototype: the colour / white-point screen cue of docs/part2-f2-screen-cue-design.md section 3.3, evaluated
offline.

Development tool only; results and decision: docs/part2-f2-screen-cue-design.md section 6.3. A colour rule fitted on
one subject is a fairness risk (section 3.3): even a separation here would not be a reason to ship it.

    python -I models/tools/f2_colour_prototype.py [--eval-data DIR]

Per frame with exactly one face, from the 8-bit BGR pixels (no skin model, no mesh):
  * blueFace   = mean b / (r + g + b) over the face box's central 60% x 60% (screens have a cold white point);
  * blueWhite  = mean b / (r + g + b) over the brightest 5% (luma) of the V1SE 4.0x region: the scene's white
                 point, which on a screen is the panel's white;
  * satRegion  = the 95th percentile of the saturation (max - min) / max over the 4.0x region (a screen's narrow
                 primaries give more saturated colours);
  * clipRegion = the share of 4.0x-region pixels with one channel >= 250 and another <= 200 (single-channel clipping,
                 typical of re-captured emissive colour).
Session statistic: the rolling median of each over the last 10 single-face frames (at least 3), as for moire. For
blueFace, blueWhite and satRegion the screen side is high; the threshold with no bona fide cost is above the bona fide
maximum, and the table also gives the other direction (below the bona fide minimum) in case the cue is inverted.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

import reference_pipeline as rp

WINDOW, MIN_FRAMES = 10, 3
FEATURES = ("blueFace", "blueWhite", "satRegion", "clipRegion")


def frame_features(bgr, box):
    H, W = bgr.shape[:2]
    img = bgr.astype(np.float64)
    l, t, r, b = rp.get_new_box(W, H, rp.pad_box(box), 4.0)
    reg = img[t:b + 1, l:r + 1].reshape(-1, 3)
    x, y, w, h = box
    fx0, fx1 = int(max(0, x + 0.2 * w)), int(min(W, x + 0.8 * w))
    fy0, fy1 = int(max(0, y + 0.2 * h)), int(min(H, y + 0.8 * h))
    face = img[fy0:fy1, fx0:fx1].reshape(-1, 3)
    s_face = face.sum(axis=1) + 1e-9
    luma = 0.299 * reg[:, 2] + 0.587 * reg[:, 1] + 0.114 * reg[:, 0]
    top = reg[luma >= np.percentile(luma, 95)]
    mx, mn = reg.max(axis=1), reg.min(axis=1)
    return {"blueFace": float(np.mean(face[:, 0] / s_face)),
            "blueWhite": float(np.mean(top[:, 0] / (top.sum(axis=1) + 1e-9))),
            "satRegion": float(np.percentile((mx - mn) / (mx + 1e-9), 95)),
            "clipRegion": float(np.mean((mx >= 250) & (mn <= 200)))}


def session(per_frame):
    buf = {k: [] for k in FEATURES}
    out = {k: [] for k in FEATURES}
    for f in per_frame:
        if f is None:
            buf = {k: [] for k in FEATURES}
            continue
        for k in FEATURES:
            buf[k] = (buf[k] + [f[k]])[-WINDOW:]
            if len(buf[k]) >= MIN_FRAMES:
                out[k].append(float(np.median(buf[k])))
    return out


def _group(species, condition):
    if species == "screen_photo_close":
        return "screen_photo_close fill" if condition.endswith("_fill") else "screen_photo_close small"
    return species + (" " + condition if species == "bona_fide" else "")


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
    metas = sorted(eval_dir.glob("bona_fide/s*/*/meta.json")) + sorted(eval_dir.glob("attack/*/s*/*/meta.json"))
    stats, nframes = {}, 0
    for meta_path in metas:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        per = []
        for jpg in sorted(meta_path.parent.glob("frame_*.jpg")):
            bgr = rp.imread_bgr(str(jpg))
            n, primary = rp.count_faces(yunet.detect(bgr))
            per.append(frame_features(bgr, primary["box"]) if n == 1 else None)
            nframes += 1
        s = session(per)
        row = {k: (min(v), max(v), float(np.median(v))) for k, v in s.items()}
        row["moire"] = moire.get((meta["label"], meta["take"]))
        stats.setdefault(_group(meta["label"], meta["condition"]), []).append(row)
    print(f"{len(metas)} takes, {nframes} frames (aggregates only)")
    for k in FEATURES:
        bf = [r[k] for g, rows in stats.items() if g.startswith("bona_fide") for r in rows]
        bf_max, bf_min = max(x[1] for x in bf), min(x[0] for x in bf)
        print(f"\n{k}: bona fide session range {bf_min:.4f}-{bf_max:.4f}. 'Above' = takes whose rolling median stays "
              f"above the bona fide maximum for the whole take; 'below' likewise under the minimum.")
        print("| group | takes | session min-max | session median | above | below | moire (median per take) |")
        print("|---|---|---|---|---|---|---|")
        for g in sorted(stats):
            rows = stats[g]
            mo = [r["moire"] for r in rows if r["moire"] is not None]
            mo_txt = f"{min(mo):.2f}-{max(mo):.2f}" if mo else "n/a"
            above = sum(1 for r in rows if r[k][0] > bf_max)
            below = sum(1 for r in rows if r[k][1] < bf_min)
            print(f"| {g} | {len(rows)} | {min(r[k][0] for r in rows):.4f}-{max(r[k][1] for r in rows):.4f} | "
                  f"{min(r[k][2] for r in rows):.4f}-{max(r[k][2] for r in rows):.4f} | {above}/{len(rows)} | "
                  f"{below}/{len(rows)} | {mo_txt} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
