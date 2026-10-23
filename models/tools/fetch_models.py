"""Download the upstream sources of the Part 2 models into models/tools/.cache/.

Every source is pinned by URL (at an immutable commit where the host offers one),
byte size and SHA-256. A download that fails either check is deleted and the
script exits non-zero; files already in the cache are re-verified, not re-fetched.
YuNet ships unmodified, so once verified it is also copied to models/yunet_2026may.onnx.

    python -I models/tools/fetch_models.py            # the three model sources
    python -I models/tools/fetch_models.py --extras   # + yakhyo ONNX cross-check files

Standard library only, so it runs in either venv.
"""
import argparse
import hashlib
import shutil
import sys
import urllib.request
from pathlib import Path

CACHE = Path(__file__).resolve().parent / ".cache"
MODELS = CACHE.parent.parent

OPENCV_ZOO_COMMIT = "26cc381e4d2594bb9f47a26eb8fd96c94a13660d"
SILENT_FACE_COMMIT = "b6d5f04ad78778917853b25c778acef6d5626d15"  # master, 2020-08-05

SOURCES = [
    dict(name="face_detection_yunet_2026may.onnx",
         url=f"https://github.com/opencv/opencv_zoo/raw/{OPENCV_ZOO_COMMIT}"
             "/models/face_detection_yunet/face_detection_yunet_2026may.onnx",
         size=229738,
         sha256="ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0",
         install_as="yunet_2026may.onnx"),
    dict(name="2.7_80x80_MiniFASNetV2.pth",
         url=f"https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/{SILENT_FACE_COMMIT}"
             "/resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.pth",
         size=1849453,
         sha256="a5eb02e1843f19b5386b953cc4c9f011c3f985d0ee2bb9819eea9a142099bec0"),
    dict(name="4_0_0_80x80_MiniFASNetV1SE.pth",
         url=f"https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/{SILENT_FACE_COMMIT}"
             "/resources/anti_spoof_models/4_0_0_80x80_MiniFASNetV1SE.pth",
         size=1856130,
         sha256="84ee1d37d96894d5e82de5a57df044ef80a58be2b218b5ed7cdfd875ec2f5990"),
    dict(name="face_landmark.tflite",
         url="https://storage.googleapis.com/mediapipe-assets/face_landmark.tflite",
         size=1242398,
         sha256="1055cb9d4a9ca8b8c688902a3a5194311138ba256bcc94e336d8373a5f30c814"),
]

# Optional third-party ONNX exports, used only to cross-check our own export. Never committed.
EXTRAS = [
    dict(name="yakhyo_MiniFASNetV2.onnx",
         url="https://github.com/yakhyo/face-anti-spoofing/releases/download/weights/MiniFASNetV2.onnx",
         size=1743581,
         sha256="b32929adc2d9c34b9486f8c4c7bc97c1b69bc0ea9befefc380e4faae4e463907"),
    dict(name="yakhyo_MiniFASNetV1SE.onnx",
         url="https://github.com/yakhyo/face-anti-spoofing/releases/download/weights/MiniFASNetV1SE.onnx",
         size=1742335,
         sha256="ebab7f90c7833fbccd46d3a555410e78d969db5438e169b6524be444862b3676"),
]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check(path, src):
    """Return a list of problems with the cached file (empty when it matches its pins)."""
    problems = []
    size = path.stat().st_size
    digest = sha256_of(path)
    if src["size"] is not None and size != src["size"]:
        problems.append(f"size {size} != pinned {src['size']}")
    if src["sha256"] is None:
        print(f"  UNPINNED {src['name']}: size={size} sha256={digest}  <- pin this in fetch_models.py")
    elif digest != src["sha256"]:
        problems.append(f"sha256 {digest} != pinned {src['sha256']}")
    return problems


def fetch(src):
    dest = CACHE / src["name"]
    if not dest.exists():
        part = dest.with_suffix(dest.suffix + ".part")
        print(f"  downloading {src['url']}")
        req = urllib.request.Request(src["url"], headers={"User-Agent": "mosip-liveness-fetch"})
        with urllib.request.urlopen(req, timeout=120) as resp, open(part, "wb") as out:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        part.replace(dest)
    problems = check(dest, src)
    if problems:
        dest.unlink()
        print(f"  FAIL {src['name']}: " + "; ".join(problems))
        return False
    print(f"  ok   {src['name']}")
    if src.get("install_as"):
        target = MODELS / src["install_as"]
        if not target.exists() or sha256_of(target) != src["sha256"]:
            shutil.copyfile(dest, target)
            print(f"       installed as models/{src['install_as']}")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--extras", action="store_true", help="also fetch the yakhyo cross-check ONNX files")
    args = ap.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    sources = SOURCES + (EXTRAS if args.extras else [])
    ok = all([fetch(s) for s in sources])
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
