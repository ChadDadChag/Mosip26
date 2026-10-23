"""Recompute PITCH_BIAS_DEG: the median raw mesh pitch over frontal official NASA portraits.

    python -I models/tools/calibrate_pitch.py

Reads pitch_calibration_urls.txt, downloads each thumbnail into .cache/pitch-calibration/
(checking its SHA-256), runs reference_pipeline on it and keeps faces with exactly one
detection and |yaw| <= 15, |roll| <= 15. Prints N, the median, the quartiles and the median
rounded to 0.5 deg, which is the value of reference_pipeline.PITCH_BIAS_DEG.

The bias exists because the mesh reports neutral, camera-facing heads about 9-10 deg "down".
The pose formula itself is unbiased: it gives -0.7 deg on MediaPipe's canonical face model.
"""
import hashlib
import statistics
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import reference_pipeline as rp  # noqa: E402

LIST = TOOLS / "pitch_calibration_urls.txt"
CACHE = TOOLS / ".cache" / "pitch-calibration"


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    pipe = rp.Pipeline()
    pitches = []
    for line in LIST.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        url, sha, page = line.split("\t")
        local = CACHE / (sha[:16] + ".jpg")
        if not local.exists():
            req = urllib.request.Request(url, headers={"User-Agent": "mosip-liveness-calibration/1.0"})
            local.write_bytes(urllib.request.urlopen(req, timeout=60).read())
            time.sleep(0.6)  # stay well inside Wikimedia's rate limits
        if hashlib.sha256(local.read_bytes()).hexdigest() != sha:
            local.unlink()
            sys.exit(f"SHA-256 mismatch for {url}")
        res = pipe.analyze(rp.imread_bgr(local))
        if res["faceCount"] != 1 or res["pitchRaw"] is None or abs(res["yawDeg"]) > 15 or abs(res["rollDeg"]) > 15:
            print(f"  excluded  {page}")
            continue
        pitches.append(res["pitchRaw"])
    q1, med, q3 = np.percentile(pitches, [25, 50, 75])
    print(f"N={len(pitches)} median {statistics.median(pitches):.2f} Q1 {q1:.2f} Q3 {q3:.2f} IQR {q3 - q1:.2f} "
          f"-> PITCH_BIAS_DEG = {round(statistics.median(pitches) * 2) / 2:.1f} (current {rp.PITCH_BIAS_DEG})")


if __name__ == "__main__":
    main()
