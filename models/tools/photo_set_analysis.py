"""Robustness and identity grouping for a folder of consented still photos (development only):

    python -I models/tools/photo_set_analysis.py --photos DIR --out eval-data/photo-set --sheets SCRATCH_DIR
                                                 [--same 0.55] [--eval-data eval-data]

The photos are personal data: the folder must be untracked, and nothing written here may be committed.
  * --out (under the gitignored eval-data/): photos.csv, one row per photo (index, file, faces, detections,
    detection score, mesh, pose, EAR, quality issues, inter-eye distance, luma, sharpness, cluster).
  * --sheets (OUTSIDE the repository): one contact sheet per proposed person (thumbnails with index and file name), for
    the owner of the photos to confirm or fix the grouping.
  * DIR/labels.csv (file,person_id,faces): the proposed grouping, for the owner to edit. An existing labels.csv is never
    overwritten; the proposal then goes to labels.proposed.csv.
  * stdout: aggregates only (photos are named by index).

Per photo: reference_pipeline.Pipeline.analyze (YuNet, FaceMesh, pose, EAR, quality) on the largest face; detections =
every YuNet face above the score threshold (faces = the faceCount rule). Grouping: the SFace feature of the largest face,
complete-linkage agglomerative clustering on cosine similarity: two groups merge only if EVERY pair across them is at
least --same (conservative: a false merge would turn impostor pairs into genuine ones). Photos without a face stay out.
Each group is also compared with the subjects recorded in eval-data (their bona fide takes) and the NASA portrait.
"""
import argparse
import csv
import math
import sys
from pathlib import Path

import cv2
import numpy as np

import reference_pipeline as rp

THUMB_H, SHEET_COLS = 220, 6


def analyse(pipe, bgr):
    res = pipe.analyze(bgr)
    faces = pipe.yunet.detect(bgr)
    row = {"faces": res["faceCount"], "detections": len(faces), "score": None, "mesh": False, "yaw": None,
           "pitch": None, "roll": None, "earL": None, "earR": None, "issues": "", "interEyePx": None, "luma": None,
           "lapVar": None, "areaFrac": None, "feature": None}
    p = res["primary"]
    if p is None:
        return row
    row["score"] = p["score"]
    (rx, ry), (lx, ly) = p["kps"][0], p["kps"][1]
    row["interEyePx"] = math.hypot(lx - rx, ly - ry)
    row["mesh"] = res["meshPoints"] is not None
    for k, j in (("yaw", "yawDeg"), ("pitch", "pitchDeg"), ("roll", "rollDeg"), ("earL", "earLeft"), ("earR", "earRight")):
        row[k] = res[j]
    q = res["quality"]
    row["issues"] = " ".join(q["issues"])
    row["luma"], row["lapVar"], row["areaFrac"] = q["lumaMean"], q["lapVar"], q["areaFrac"]
    row["feature"] = rp.sface_embed(pipe.sface, bgr, p)[0]
    return row


def complete_linkage(features, same):
    """Clusters (lists of indices into features), merging the most similar pair of clusters while every cross pair
    is >= same."""
    n = len(features)
    sim = np.array([[rp.sface_cosine(features[i], features[j]) for j in range(n)] for i in range(n)])
    clusters = [[i] for i in range(n)]
    while True:
        best, pair = None, None
        for a in range(len(clusters)):
            for b in range(a + 1, len(clusters)):
                link = min(sim[i, j] for i in clusters[a] for j in clusters[b])
                if link >= same and (best is None or link > best):
                    best, pair = link, (a, b)
        if pair is None:
            break
        a, b = pair
        clusters[a] = clusters[a] + clusters[b]
        del clusters[b]
    clusters.sort(key=lambda c: (-len(c), min(c)))
    return clusters, sim


def subject_features(pipe, eval_dir):
    """A few frames per recorded subject (bona fide takes, the middle frame of each), for the 'is it a recorded
    subject?' check."""
    out = {}
    for take in sorted((eval_dir / "bona_fide").glob("s*/*/")):
        frames = sorted(take.glob("frame_*.*"))
        if not frames:
            continue
        bgr = rp.imread_bgr(str(frames[len(frames) // 2]))
        n, primary = rp.count_faces(pipe.yunet.detect(bgr))
        if n == 1:
            out.setdefault(take.parent.name, []).append(rp.sface_embed(pipe.sface, bgr, primary)[0])
    return out


def sheet(images, captions, path):
    thumbs = []
    for img, cap in zip(images, captions):
        h, w = img.shape[:2]
        t = cv2.resize(img, (max(1, int(w * THUMB_H / h)), THUMB_H), interpolation=cv2.INTER_AREA)
        canvas = np.full((THUMB_H + 40, 260, 3), 255, np.uint8)
        tw = min(t.shape[1], 260)
        canvas[:THUMB_H, :tw] = t[:, :tw]
        for k, line in enumerate((cap[:34], cap[34:68])):
            cv2.putText(canvas, line, (4, THUMB_H + 16 + 16 * k), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (0, 0, 0), 1)
        thumbs.append(canvas)
    while len(thumbs) % SHEET_COLS:
        thumbs.append(np.full_like(thumbs[0], 255))
    rows = [np.hstack(thumbs[i:i + SHEET_COLS]) for i in range(0, len(thumbs), SHEET_COLS)]
    cv2.imwrite(str(path), np.vstack(rows))


def q(values, p):
    return float(np.percentile(values, p)) if values else float("nan")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--photos", required=True)
    ap.add_argument("--out", required=True, help="under the gitignored eval-data/")
    ap.add_argument("--sheets", required=True, help="outside the repository")
    ap.add_argument("--same", type=float, default=0.55)
    ap.add_argument("--eval-data", default=str(rp.MODELS.parent / "eval-data"))
    args = ap.parse_args()
    repo = rp.MODELS.parent.resolve()
    sheets = Path(args.sheets).resolve()
    if repo == sheets or repo in sheets.parents:
        sys.exit("--sheets must be outside the repository")
    out = Path(args.out).resolve()
    if not (repo / "eval-data" in out.parents or out == repo / "eval-data"):
        sys.exit("--out must be under eval-data/")
    photos = Path(args.photos)
    files = sorted(list(photos.glob("*.jpg")) + list(photos.glob("*.jpeg")) + list(photos.glob("*.png")))
    pipe = rp.Pipeline()
    rows, images = [], []
    for i, f in enumerate(files, 1):
        bgr = rp.imread_bgr(str(f))
        r = analyse(pipe, bgr)
        r["index"], r["file"] = i, f.name
        rows.append(r)
        images.append(bgr)
    n = len(rows)

    # A. Robustness aggregates.
    one = [r for r in rows if r["faces"] == 1]
    faced = [r for r in rows if r["faces"] >= 1]
    print(f"A. {n} photos")
    print(f"  exactly one face (faceCount): {len(one)} ({100 * len(one) / n:.0f}%); several: "
          f"{sum(1 for r in rows if r['faces'] > 1)}; none: {sum(1 for r in rows if r['faces'] == 0)}; "
          f"photos with 2+ detections of any size: {sum(1 for r in rows if r['detections'] > 1)}")
    print(f"  landmarks (mesh) on the largest face: {sum(1 for r in faced if r['mesh'])} of {len(faced)} with a face "
          f"({100 * sum(1 for r in faced if r['mesh']) / n:.0f}% of all photos)")
    for k in ("score", "yaw", "pitch", "roll", "earL", "earR", "interEyePx", "luma", "lapVar", "areaFrac"):
        v = [r[k] for r in faced if r[k] is not None]
        if k in ("yaw", "pitch", "roll"):
            v = [abs(x) for x in v]
        print(f"  {('|' + k + '|') if k in ('yaw', 'pitch', 'roll') else k:12s} n={len(v):2d} min {min(v):8.2f} p25 "
              f"{q(v, 25):8.2f} median {q(v, 50):8.2f} p75 {q(v, 75):8.2f} max {max(v):8.2f}")
    ears = [min(r["earL"], r["earR"]) for r in faced if r["earL"] is not None]
    print(f"  min(EAR L, R) < 0.20 (eyes possibly closed): {sum(1 for e in ears if e < 0.20)} of {len(ears)}")
    pose_big = [r["index"] for r in faced if r["yaw"] is not None and (abs(r["yaw"]) > 25 or abs(r["pitch"]) > 25)]
    print(f"  |yaw| or |pitch| > 25 deg (outside the PAD pose range): {len(pose_big)} photos {pose_big}")
    issues = {}
    for r in faced:
        for iss in r["issues"].split():
            issues.setdefault(iss, []).append(r["index"])
    for iss, idx in sorted(issues.items(), key=lambda kv: -len(kv[1])):
        print(f"  quality issue {iss}: {len(idx)} photos {idx}")
    fail = [r["index"] for r in rows if r["faces"] != 1 or not r["mesh"]]
    print(f"  failures (not exactly one face, or no landmarks): {fail}")
    for r in rows:
        if r["index"] not in fail:
            continue
        cond = []
        if r["faces"] == 0:
            cond.append("no face detected")
        if r["faces"] > 1:
            cond.append(f"{r['faces']} faces")
        if r["faces"] >= 1 and not r["mesh"]:
            cond.append("no landmarks")
        if r["interEyePx"] is not None and r["interEyePx"] < 30:
            cond.append(f"small face (inter-eye {r['interEyePx']:.0f} px)")
        if r["luma"] is not None and r["luma"] < 60:
            cond.append(f"dark (luma {r['luma']:.0f})")
        if r["yaw"] is not None and abs(r["yaw"]) > 25:
            cond.append(f"turned (yaw {r['yaw']:.0f})")
        h, w = images[r["index"] - 1].shape[:2]
        cond.append(f"{w}x{h} px")
        print(f"    photo {r['index']:2d}: " + ", ".join(cond))

    # B. Grouping.
    idx = [r["index"] for r in rows if r["feature"] is not None]
    feats = [rows[i - 1]["feature"] for i in idx]
    clusters, sim = complete_linkage(feats, args.same)
    person = {}
    for c, members in enumerate(clusters, 1):
        for m in members:
            person[idx[m]] = f"p{c:02d}"
    sizes = [len(c) for c in clusters]
    print(f"\nB. {len(idx)} photos with a face -> {len(clusters)} proposed people (complete linkage, cosine >= "
          f"{args.same}); sizes {sizes}; people in 2+ photos: {sum(1 for s in sizes if s > 1)}, in one: "
          f"{sum(1 for s in sizes if s == 1)}")
    near = []
    for a in range(len(clusters)):
        for b in range(a + 1, len(clusters)):
            m = max(sim[i, j] for i in clusters[a] for j in clusters[b])
            if m >= 0.40:
                near.append((m, f"p{a + 1:02d}", f"p{b + 1:02d}"))
    for m, a, b in sorted(near, reverse=True):
        print(f"  check: {a} and {b} have a cross pair at {m:.3f} (could be one person)")
    multi = [r["index"] for r in rows if r["faces"] > 1]
    print(f"  photos with several faces (largest face used, flagged in labels): {multi}")
    subj = subject_features(pipe, Path(args.eval_data))
    golden = rp.imread_bgr(str(rp.MODELS.parent / "liveness-analyzer/src/test/resources/golden/portrait_frontal.png"))
    nasa = rp.sface_embed(pipe.sface, golden, rp.count_faces(pipe.yunet.detect(golden))[1])[0]
    for c, members in enumerate(clusters, 1):
        best = max((rp.sface_cosine(feats[m], s), code) for m in members for code, fs in subj.items() for s in fs) \
            if subj else (float("nan"), "-")
        nas = max(rp.sface_cosine(feats[m], nasa) for m in members)
        if best[0] >= 0.40 or nas >= 0.40:
            print(f"  p{c:02d}: max similarity to recorded subject {best[1]} {best[0]:.3f}, to the NASA portrait "
                  f"{nas:.3f}: check whether it is that person")

    # Outputs (never in the repository except under eval-data/; sheets outside it).
    out.mkdir(parents=True, exist_ok=True)
    keys = ["index", "file", "faces", "detections", "score", "mesh", "yaw", "pitch", "roll", "earL", "earR", "issues",
            "interEyePx", "luma", "lapVar", "areaFrac"]
    with open(out / "photos.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(keys + ["person"])
        for r in rows:
            w.writerow([r[k] for k in keys] + [person.get(r["index"], "")])
    labels = photos / "labels.csv"
    target = labels if not labels.exists() else photos / "labels.proposed.csv"
    with open(target, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["file", "person_id", "faces"])
        for r in rows:
            w.writerow([r["file"], person.get(r["index"], ""), r["faces"]])
    sheets.mkdir(parents=True, exist_ok=True)
    for c, members in enumerate(clusters, 1):
        ims = [images[idx[m] - 1] for m in members]
        caps = [f"#{idx[m]} {rows[idx[m] - 1]['file']}" + (" MULTI" if rows[idx[m] - 1]["faces"] > 1 else "")
                for m in members]
        sheet(ims, caps, sheets / f"p{c:02d}.png")
    nofaces = [r for r in rows if r["feature"] is None]
    if nofaces:
        sheet([images[r["index"] - 1] for r in nofaces], [f"#{r['index']} {r['file']} NO FACE" for r in nofaces],
              sheets / "no-face.png")
    print(f"\nwrote {out / 'photos.csv'}, {target}, {len(clusters)} contact sheets in {sheets}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
