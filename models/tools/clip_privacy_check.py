"""Privacy check for regression clips before they are committed (F16):

    python -I models/tools/clip_privacy_check.py CLIP.mjpeg [CLIP.mjpeg ...] --sheets DIR

A committed clip may show only the public-domain NASA portrait (print or phone) and at most a hand: no real person,
no face reflected in a screen, nobody in the background. For each clip this script
  * reads every frame (the SBI /stream framing of MjpegRecorder / ClipPacker),
  * runs YuNet on each frame and counts ALL detections above the score threshold (not only faces of at least 20% of
    the largest one, as faceCount does), so a small face in the background is counted too,
  * writes contact sheets (every frame, numbered, 8 per row) to DIR for a person to look at.
The detector is only a first filter: the contact sheets must be viewed, frame by frame, before committing. DIR must
be outside the repository (a scratch folder), and the sheets deleted afterwards.
Prints per clip: frames, size, frames with 0 / 1 / 2+ detections, and the frames with 2+.
"""
import argparse
import re
import sys
from pathlib import Path

import cv2
import numpy as np

import reference_pipeline as rp

THUMB_W, THUMB_H, COLS, PER_SHEET = 200, 150, 8, 48
HEADER = re.compile(rb"--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: (\d+)\r\n\r\n")


def read_mjpeg(path):
    data = Path(path).read_bytes()
    frames, pos = [], 0
    while True:
        m = HEADER.match(data, pos)
        if m is None:
            break
        n = int(m.group(1))
        frames.append(data[m.end():m.end() + n])
        pos = m.end() + n + 4
    if pos != len(data):
        raise ValueError(f"{path}: unexpected bytes at offset {pos} (not the MjpegRecorder framing)")
    return frames


def sheets(name, images, counts, out_dir):
    out = []
    for start in range(0, len(images), PER_SHEET):
        chunk = images[start:start + PER_SHEET]
        rows = (len(chunk) + COLS - 1) // COLS
        sheet = np.zeros((rows * THUMB_H, COLS * THUMB_W, 3), np.uint8)
        for i, img in enumerate(chunk):
            t = cv2.resize(img, (THUMB_W, THUMB_H), interpolation=cv2.INTER_AREA)
            n = start + i
            colour = (0, 0, 255) if counts[n] >= 2 else (0, 255, 0)
            cv2.putText(t, f"{n} f{counts[n]}", (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, colour, 1)
            r, c = divmod(i, COLS)
            sheet[r * THUMB_H:(r + 1) * THUMB_H, c * THUMB_W:(c + 1) * THUMB_W] = t
        p = Path(out_dir) / f"{name}_sheet{start // PER_SHEET + 1}.png"
        cv2.imwrite(str(p), sheet)
        out.append(p)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("clips", nargs="+")
    ap.add_argument("--sheets", required=True, help="folder for the contact sheets (outside the repository)")
    args = ap.parse_args()
    out_dir = Path(args.sheets).resolve()
    if (rp.MODELS.parent.resolve() in out_dir.parents) or out_dir == rp.MODELS.parent.resolve():
        sys.exit("--sheets must be outside the repository")
    out_dir.mkdir(parents=True, exist_ok=True)
    yunet = rp.YuNet(rp.MODELS / rp.YUNET_FILE)
    worst = 0
    for clip in args.clips:
        images, counts = [], []
        for jpeg in read_mjpeg(clip):
            bgr = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
            images.append(bgr)
            counts.append(len(yunet.detect(bgr)))
        hist = {k: sum(1 for c in counts if (c >= 2 if k == "2+" else c == k)) for k in (0, 1, "2+")}
        many = [i for i, c in enumerate(counts) if c >= 2]
        worst = max(worst, max(counts) if counts else 0)
        size = Path(clip).stat().st_size
        h, w = images[0].shape[:2] if images else (0, 0)
        print(f"{Path(clip).name}: {len(images)} frames {w}x{h}, {size} bytes; detections per frame: 0 -> {hist[0]}, "
              f"1 -> {hist[1]}, 2+ -> {hist['2+']}" + (f" (frames {many})" if many else ""))
        for p in sheets(Path(clip).stem.split("__")[0], images, counts, out_dir):
            print(f"  sheet {p}")
    print("2+ detections in some frame: LOOK CLOSELY" if worst >= 2 else "at most 1 detection per frame "
          "(still view every sheet)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
