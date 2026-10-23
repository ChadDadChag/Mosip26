"""Build the golden fixtures that the Java parity tests compare against.

    python -I models/tools/make_golden.py [--rebuild-images]

Fixtures live in liveness-analyzer/src/test/resources/golden/. Both images are US-government
works (PD-USGov-NASA) from Wikimedia Commons; never put team faces here.

1. Images: only when a fixture PNG is missing (or --rebuild-images), download the original,
   check its Commons SHA-1, downscale so the longest side is 640 px (cv2.INTER_AREA, a
   realistic camera size) and write a lossless PNG, which cv2 and Java ImageIO decode to
   identical pixels. An existing PNG is never rewritten otherwise: the committed file IS the
   fixture.
2. For each image and its horizontal mirror (decoded pixels flipped in memory; only the JSON is
   stored, Java flips the pixels itself) run reference_pipeline.Pipeline and write
   <name>.json / <name>_flipped.json. One warm-up run first, so timingsMs are steady-state.
3. Assert, failing loudly:
     portrait: faceCount == 1, |yaw| < 10, |pitch| < 10 (bias-corrected), EAR of each eye in
               [0.18, 0.40], meshFlag >= 0.5
     portrait mirrored: |yaw + yaw'| <= 3, |roll + roll'| <= 3, |pitch - pitch'| <= 3
     crew (and its mirror): faceCount >= 2
   livenessScore is printed, never asserted: a digital photo may legitimately score as a spoof.
"""
import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import reference_pipeline as rp  # noqa: E402

GOLDEN = TOOLS.parent.parent / "liveness-analyzer" / "src" / "test" / "resources" / "golden"
SRC_CACHE = TOOLS / ".cache" / "golden-src"
LONGEST_SIDE = 640
FLIP_TOLERANCE_DEG = 3.0

FIXTURES = [
    dict(name="portrait_frontal",
         page="https://commons.wikimedia.org/wiki/File:AMERICAN_AND_SOVIET_ASTRONAUTS_-_COOPER_-_MERCURY_9_-_GEMINI_5_-_NARA_-_17447491.jpg",
         url="https://upload.wikimedia.org/wikipedia/commons/c/c4/"
             "AMERICAN_AND_SOVIET_ASTRONAUTS_-_COOPER_-_MERCURY_9_-_GEMINI_5_-_NARA_-_17447491.jpg",
         sha1="a5115a92e5906076fe67a9c5e0b31a657972691a"),
    dict(name="crew_multi",
         page="https://commons.wikimedia.org/wiki/File:Expedition_17_crew_portrait_B.jpg",
         url="https://upload.wikimedia.org/wikipedia/commons/3/37/Expedition_17_crew_portrait_B.jpg",
         sha1="da481a0a52a9e5af49a04af0a74768137631749c"),
]


def file_hash(path, algo):
    h = hashlib.new(algo)
    h.update(Path(path).read_bytes())
    return h.hexdigest()


def build_image(fx):
    import cv2

    SRC_CACHE.mkdir(parents=True, exist_ok=True)
    src = SRC_CACHE / (fx["name"] + "_original.jpg")
    if not src.exists():
        print(f"  downloading {fx['url']}")
        req = urllib.request.Request(fx["url"], headers={"User-Agent": "mosip-liveness-golden/1.0 (github ChadDadChag/Mosip26)"})
        src.write_bytes(urllib.request.urlopen(req, timeout=300).read())
    if file_hash(src, "sha1") != fx["sha1"]:
        src.unlink()
        sys.exit(f"{fx['name']}: original does not match the Commons SHA-1 {fx['sha1']}")
    img = cv2.imread(str(src), cv2.IMREAD_COLOR)
    h, w = img.shape[:2]
    k = LONGEST_SIDE / max(w, h)
    small = cv2.resize(img, (rp.round_half_up(w * k), rp.round_half_up(h * k)), interpolation=cv2.INTER_AREA)
    out = GOLDEN / (fx["name"] + ".png")
    cv2.imwrite(str(out), small, [cv2.IMWRITE_PNG_COMPRESSION, 9])
    print(f"  wrote {out.name} {small.shape[1]}x{small.shape[0]} from {w}x{h}")


def model_hashes():
    return {f: file_hash(rp.MODELS / f, "sha256")
            for f in (rp.YUNET_FILE, rp.MESH_FILE, rp.PAD_V2_FILE, rp.PAD_V1SE_FILE)}


def analyze(pipe, bgr, fx, flipped, models):
    pipe.analyze(bgr)  # warm-up
    res = pipe.analyze(bgr)
    meta = {"fixture": fx["name"] + ".png", "flipped": flipped, "source": fx["page"],
            "license": "Public domain (PD-USGov-NASA)", "models": models,
            "note": "flipped = decoded pixels mirrored horizontally in memory (not re-encoded)"}
    return {"meta": meta, **res}


def check(cond, msg, failures):
    print(("  ok    " if cond else "  FAIL  ") + msg)
    if not cond:
        failures.append(msg)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rebuild-images", action="store_true", help="re-create the fixture PNGs from the originals")
    ap.add_argument("--images-only", action="store_true", help="stop after creating missing fixture images")
    args = ap.parse_args()
    GOLDEN.mkdir(parents=True, exist_ok=True)
    for fx in FIXTURES:
        if args.rebuild_images or not (GOLDEN / (fx["name"] + ".png")).exists():
            build_image(fx)
    if args.images_only:
        return

    pipe = rp.Pipeline()
    models = model_hashes()
    failures = []
    results = {}
    for fx in FIXTURES:
        bgr = rp.imread_bgr(GOLDEN / (fx["name"] + ".png"))
        for flipped in (False, True):
            img = np.ascontiguousarray(bgr[:, ::-1]) if flipped else bgr
            res = analyze(pipe, img, fx, flipped, models)
            out = GOLDEN / (fx["name"] + ("_flipped" if flipped else "") + ".json")
            out.write_text(rp.to_json(res), encoding="utf-8")
            results[(fx["name"], flipped)] = res
            print(f"{out.name}: {rp.summary(res)}")

    print("assertions:")
    p, pf = results[("portrait_frontal", False)], results[("portrait_frontal", True)]
    check(p["faceCount"] == 1, f"portrait faceCount == 1 (got {p['faceCount']})", failures)
    check(p["meshFlag"] is not None and p["meshFlag"] >= 0.5, f"portrait meshFlag >= 0.5 (got {p['meshFlag']})", failures)
    if p["yawDeg"] is not None:
        check(abs(p["yawDeg"]) < 10, f"portrait |yaw| < 10 (got {p['yawDeg']:.2f})", failures)
        check(abs(p["pitchDeg"]) < 10, f"portrait |pitch| < 10 (got {p['pitchDeg']:.2f}, raw {p['pitchRaw']:.2f})", failures)
        for eye in ("earLeft", "earRight"):
            check(0.18 <= p[eye] <= 0.40, f"portrait {eye} in [0.18, 0.40] (got {p[eye]:.3f})", failures)
    if p["yawDeg"] is not None and pf["yawDeg"] is not None:
        check(abs(p["yawDeg"] + pf["yawDeg"]) <= FLIP_TOLERANCE_DEG,
              f"mirrored yaw changes sign: {p['yawDeg']:.2f} vs {pf['yawDeg']:.2f}", failures)
        check(abs(p["rollDeg"] + pf["rollDeg"]) <= FLIP_TOLERANCE_DEG,
              f"mirrored roll changes sign: {p['rollDeg']:.2f} vs {pf['rollDeg']:.2f}", failures)
        check(abs(p["pitchDeg"] - pf["pitchDeg"]) <= FLIP_TOLERANCE_DEG,
              f"mirrored pitch unchanged: {p['pitchDeg']:.2f} vs {pf['pitchDeg']:.2f}", failures)
    else:
        check(False, "portrait pose available for the mirror check", failures)
    for flipped in (False, True):
        c = results[("crew_multi", flipped)]
        check(c["faceCount"] >= 2, f"crew{' mirrored' if flipped else ''} faceCount >= 2 (got {c['faceCount']})", failures)
    for (name, flipped), res in results.items():
        print(f"  info  {name}{' mirrored' if flipped else ''} livenessScore = {res['livenessScore']}")
    if failures:
        sys.exit(f"{len(failures)} golden assertion(s) failed")


if __name__ == "__main__":
    main()
