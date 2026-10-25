"""F2 prototype: the banding screen cue of docs/part2-f2-screen-cue-design.md section 3.2, evaluated offline.

Development tool only; results and decision: docs/part2-f2-screen-cue-design.md section 6.2.

    python -I models/tools/f2_banding_prototype.py [--eval-data DIR]

Per frame with exactly one face, for two regions: the V1SE 4.0x region (get_new_box on pad_box) and the face box's
central 60% columns. Row profile = the mean BT.601 luma of each frame row of the region, in native frame rows (no
resampling, so the JPEG 8-row block grid stays at exactly 1/8 cycle per row and can be excluded).
  1. Shading removed (least-squares quadratic), Hann window, |DFT|^2 zero-padded to 512 points.
  2. Whitened power: each bin divided by the median of its +-8 neighbours (a band is a narrow peak above the scene's
     1/f slope). The band of interest is periods 6 to 60 rows: with a rolling-shutter row time of about 50-70 us,
     PWM at about 300-2,000 Hz. The +-2 bins around the JPEG frequencies k/8 are left out.
  3. bandScore = the largest whitened power in the band.
Drift (the temporal check): over the last 10 single-face frames, per band bin, the mean whitened power M(f) and the
phase coherence C(f) = |sum X_t(f) e^{-i 2 pi f y0_t}| / sum |X_t(f)| in frame-row coordinates (y0_t = the region's
first row). A screen's band keeps its power and drifts in phase (C low); scene structure keeps its phase (C high).
driftScore = max over the band of M(f) (1 - C(f)).
Session statistics: the rolling median of bandScore (region, face) over the last 10 single-face frames (at least 3),
as for moire; driftScore is already windowed. Any other face count resets.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

import reference_pipeline as rp

NFFT = 512
P_MIN, P_MAX = 6.0, 60.0
LOCAL = 8
WINDOW, MIN_FRAMES = 10, 3
FREQS = np.fft.rfftfreq(NFFT)
BAND = (FREQS >= 1.0 / P_MAX) & (FREQS <= 1.0 / P_MIN)
for _k in (1, 2, 3, 4):
    BAND &= ~(np.abs(FREQS - _k / 8.0) <= 2.0 / NFFT)


def luma(bgr):
    b = bgr.astype(np.float64)
    return 0.299 * b[:, :, 2] + 0.587 * b[:, :, 1] + 0.114 * b[:, :, 0]


def profile_spectrum(profile):
    """(complex spectrum, whitened power) of one row profile."""
    n = len(profile)
    x = np.arange(n, dtype=np.float64)
    p = (profile - np.polyval(np.polyfit(x, profile, 2), x)) * np.hanning(n)
    X = np.fft.rfft(p, NFFT)
    s = np.abs(X) ** 2
    pad = np.pad(s, LOCAL, mode="edge")
    windows = np.lib.stride_tricks.sliding_window_view(pad, 2 * LOCAL + 1)
    return X, s / (np.median(windows, axis=1) + 1e-12)


def regions(gray, box):
    H, W = gray.shape
    l, t, r, b = rp.get_new_box(W, H, rp.pad_box(box), 4.0)
    x, y, w, h = box
    fx0, fx1 = int(max(0, x + 0.2 * w)), int(min(W, x + 0.8 * w))
    fy0, fy1 = int(max(0, y)), int(min(H, y + h))
    return {"region": (gray[t:b + 1, l:r + 1].mean(axis=1), t),
            "face": (gray[fy0:fy1, fx0:fx1].mean(axis=1), fy0)}


def frame_features(bgr, box):
    out = {}
    for name, (prof, y0) in regions(luma(bgr), box).items():
        if len(prof) < 2 * P_MAX:
            out[name] = None
            continue
        X, wp = profile_spectrum(prof)
        out[name] = {"X": X, "y0": y0, "white": wp, "bandScore": float(wp[BAND].max())}
    return out


def drift(window):
    """driftScore over a window of region features."""
    M = np.mean([w["white"] for w in window], axis=0)
    rot = [w["X"] * np.exp(-2j * np.pi * FREQS * w["y0"]) for w in window]
    C = np.abs(np.sum(rot, axis=0)) / (np.sum(np.abs(rot), axis=0) + 1e-12)
    return float((M * (1.0 - C))[BAND].max())


def session(per_frame):
    """Per take: lists of rolling medians (region, face) and windowed drift scores."""
    buf = {"region": [], "face": []}
    win = []
    stats = {"region": [], "face": [], "drift": []}
    for f in per_frame:
        if f is None:
            buf, win = {"region": [], "face": []}, []
            continue
        for name in ("region", "face"):
            if f[name] is not None:
                buf[name] = (buf[name] + [f[name]["bandScore"]])[-WINDOW:]
            if len(buf[name]) >= MIN_FRAMES:
                stats[name].append(float(np.median(buf[name])))
        if f["region"] is not None:
            win = (win + [f["region"]])[-WINDOW:]
            if len(win) >= MIN_FRAMES:
                stats["drift"].append(drift(win))
    return stats


def _group(species, condition):
    if species == "screen_photo_close":
        return "screen_photo_close fill" if condition.endswith("_fill") else "screen_photo_close small"
    return species + (" " + condition if species == "bona_fide" else "")


def jpeg_test(eval_dir, threshold):
    """Does faint banding survive the recordings' JPEG q95 4:4:4? Adds a horizontal sinusoidal band (amplitude A grey
    levels, period P rows, random phase) to every frame of the bona fide close_whole and the *_fill takes, then
    measures the region bandScore directly and after a JPEG q95 4:4:4 round trip (OpenCV's libjpeg). Prints the share
    of frames whose bandScore exceeds `threshold` (the bona fide maximum of the session statistic)."""
    import cv2
    yunet = rp.YuNet(rp.MODELS / rp.YUNET_FILE)
    frames = []
    for meta_path in sorted(eval_dir.glob("bona_fide/s*/*/meta.json")) + sorted(
            eval_dir.glob("attack/screen_photo_close/s*/*/meta.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta["condition"] not in ("close_whole", "selfie_fill", "nasa_fill"):
            continue
        for jpg in sorted(meta_path.parent.glob("frame_*.jpg"))[::6]:
            bgr = rp.imread_bgr(str(jpg))
            n, primary = rp.count_faces(yunet.detect(bgr))
            if n == 1:
                frames.append((bgr, primary["box"]))
    rng = np.random.default_rng(7)
    params = [cv2.IMWRITE_JPEG_QUALITY, 95, cv2.IMWRITE_JPEG_SAMPLING_FACTOR, cv2.IMWRITE_JPEG_SAMPLING_FACTOR_444]
    print(f"\nJPEG test on {len(frames)} frames (close_whole and *_fill, every 6th): share of frames with region "
          f"bandScore > {threshold:.2f}, without / with the JPEG q95 4:4:4 round trip")
    print("| amplitude (grey levels) | period 7 rows | period 12 rows | period 20 rows | period 40 rows |")
    print("|---|---|---|---|---|")
    for amp in (0.0, 0.5, 1.0, 2.0, 4.0):
        cells = []
        for period in (7.0, 12.0, 20.0, 40.0):
            raw = enc = 0
            for bgr, box in frames:
                rows = np.arange(bgr.shape[0], dtype=np.float64)
                band = amp * np.sin(2 * np.pi * rows / period + rng.uniform(0, 2 * np.pi))
                img = np.clip(bgr.astype(np.float64) + band[:, None, None], 0, 255)
                raw += frame_features(img, box)["region"]["bandScore"] > threshold
                ok, buf = cv2.imencode(".jpg", np.round(img).astype(np.uint8), params)
                dec = cv2.imdecode(buf, cv2.IMREAD_COLOR)
                enc += frame_features(dec, box)["region"]["bandScore"] > threshold
            cells.append(f"{raw / len(frames):.0%} / {enc / len(frames):.0%}")
        print(f"| {amp:.1f} | " + " | ".join(cells) + " |")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--eval-data", default=str(rp.MODELS.parent / "eval-data"))
    ap.add_argument("--jpeg-test", type=float, metavar="THRESHOLD",
                    help="only run the synthetic JPEG test at this bandScore threshold")
    args = ap.parse_args()
    eval_dir = Path(args.eval_data)
    if args.jpeg_test is not None:
        jpeg_test(eval_dir, args.jpeg_test)
        return 0
    moire = {}
    scatter = eval_dir / "reports" / "pad-eval-20261024-cap" / "scatter.csv"
    if scatter.exists():
        with open(scatter, newline="") as f:
            for r in csv.DictReader(f):
                moire[(r["species"], int(r["take"]))] = float(r["median_moire"])
    yunet = rp.YuNet(rp.MODELS / rp.YUNET_FILE)
    metas = sorted(eval_dir.glob("bona_fide/s*/*/meta.json")) + sorted(eval_dir.glob("attack/*/s*/*/meta.json"))
    stats = {}
    nframes = 0
    for meta_path in metas:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        per = []
        for jpg in sorted(meta_path.parent.glob("frame_*.jpg")):
            bgr = rp.imread_bgr(str(jpg))
            n, primary = rp.count_faces(yunet.detect(bgr))
            per.append(frame_features(bgr, primary["box"]) if n == 1 else None)
            nframes += 1
        s = session(per)
        g = _group(meta["label"], meta["condition"])
        row = {k: (max(v), float(np.median(v))) if v else (0.0, 0.0) for k, v in s.items()}
        row["moire"] = moire.get((meta["label"], meta["take"]))
        stats.setdefault(g, []).append(row)
    print(f"{len(metas)} takes, {nframes} frames (aggregates only)")
    for key, title in (("region", "bandScore, 4.0x region"), ("face", "bandScore, face"),
                       ("drift", "driftScore (power x phase drift), 4.0x region")):
        bf_max = max(r[key][0] for g, rows in stats.items() if g.startswith("bona_fide") for r in rows)
        print(f"\n{title}: bona fide max {bf_max:.2f}; a threshold above it has no bona fide cost.")
        print("| group | takes | session max | session median | above bona fide max | moire (median per take) |")
        print("|---|---|---|---|---|---|")
        for g in sorted(stats):
            rows = stats[g]
            mx = [r[key][0] for r in rows]
            md = [r[key][1] for r in rows]
            mo = [r["moire"] for r in rows if r["moire"] is not None]
            above = sum(1 for m in mx if m > bf_max)
            mo_txt = f"{min(mo):.2f}-{max(mo):.2f}" if mo else "n/a"
            print(f"| {g} | {len(rows)} | {min(mx):.2f}-{max(mx):.2f} | {min(md):.2f}-{max(md):.2f} | "
                  f"{above}/{len(rows)} | {mo_txt} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
