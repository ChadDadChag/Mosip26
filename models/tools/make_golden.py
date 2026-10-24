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
     portrait (and its mirror): no quality issues
     crew (and its mirror): faceCount >= 2; primary-face quality issues exactly [FACE_TOO_SMALL]
                            (a ~51 px face in a 640 px group photo is correctly too small)
   livenessScore is printed, never asserted: a digital photo may legitimately score as a spoof.
4. binding.json (face binding, F1; also alone with --binding): the SFace feature of every face in the portrait,
   its mirror and the crew photo, the cosines between them and with OpenCV's FaceRecognizerSF. Asserted: cosine
   with cv2 >= 0.9999 per face; the portrait matches its mirror; every other pair stays below the threshold.
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


def blur_study():
    """lapVar of the primary face box, on the sharp frame and on the frame Gaussian-blurred with sigma 1.5
    and 3 (cv2.GaussianBlur, kernel size derived from sigma). The box always comes from the sharp frame."""
    import cv2

    pipe = rp.Pipeline()
    for fx in FIXTURES:
        bgr = rp.imread_bgr(GOLDEN / (fx["name"] + ".png"))
        for flipped in (False, True):
            img = np.ascontiguousarray(bgr[:, ::-1]) if flipped else bgr
            box = rp.count_faces(pipe.yunet.detect(img))[1]["box"]
            values = [rp.quality(img, box)["lapVar"]]
            for sigma in (1.5, 3.0):
                values.append(rp.quality(cv2.GaussianBlur(img, (0, 0), sigma), box)["lapVar"])
            print(f"{fx['name'] + (' (mirrored)' if flipped else ''):<30} face w {box[2]:6.1f}px  "
                  f"lapVar sharp {values[0]:8.2f}  sigma1.5 {values[1]:7.2f}  sigma3 {values[2]:7.2f}")


def binding(failures):
    """golden/binding.json for the Java FaceMatcher parity tests (F1): every face YuNet finds in the portrait, its
    mirror and the crew photo, with its box, keypoints and SFace feature; the cosines between them; and the
    cosine of each feature with OpenCV's own FaceRecognizerSF (cv2), given the same detection. The features come
    from the public-domain fixture images only."""
    import cv2

    pipe = rp.Pipeline()
    rec = cv2.FaceRecognizerSF_create(str(rp.MODELS / rp.SFACE_FILE), "")
    faces = []
    for fx in FIXTURES:
        bgr = rp.imread_bgr(GOLDEN / (fx["name"] + ".png"))
        for flipped in (False, True):
            if fx["name"] == "crew_multi" and flipped:
                continue
            img = np.ascontiguousarray(bgr[:, ::-1]) if flipped else bgr
            for k, f in enumerate(pipe.yunet.detect(img)):
                feat, aligned = rp.sface_embed(pipe.sface, img, f)
                row = np.array([f["box"] + [v for p in f["kps"] for v in p] + [f["score"]]], dtype=np.float32)
                feat_cv = rec.feature(rec.alignCrop(img, row)).ravel()
                ident = ("portrait" if fx["name"] == "portrait_frontal" else f"crew{k}") + ("_flipped" if flipped else "")
                faces.append({"id": ident, "image": fx["name"] + ".png", "flipped": flipped, "box": f["box"],
                              "score": f["score"], "kps": f["kps"], "feature": [float(v) for v in feat],
                              "featureNorm": float(np.linalg.norm(feat)),
                              "alignedStats": rp.tensor_stats(rp.sface_input(aligned)),
                              "cosineWithOpenCv": rp.sface_cosine(feat, feat_cv)})
    cos = {}
    for i, a in enumerate(faces):
        for b in faces[i + 1:]:
            cos[a["id"] + "~" + b["id"]] = rp.sface_cosine(a["feature"], b["feature"])
    meta = {"model": rp.SFACE_FILE, "sha256": file_hash(rp.MODELS / rp.SFACE_FILE, "sha256"),
            "threshold": rp.SFACE_COSINE_THRESHOLD, "reference": "OpenCV 5.0.0 FaceRecognizerSF "
            "(modules/objdetect/src/face_recognize.cpp) via reference_pipeline.sface_*",
            "license": "Images public domain (PD-USGov-NASA); features derived from them"}
    out = GOLDEN / "binding.json"
    out.write_text(rp.to_json({"meta": meta, "faces": faces, "cosines": cos}), encoding="utf-8")
    print(f"{out.name}: {len(faces)} faces")
    for f in faces:
        check(f["cosineWithOpenCv"] >= 0.9999, f"{f['id']} feature cosine with cv2 >= 0.9999 "
              f"(got {f['cosineWithOpenCv']:.7f})", failures)
    t = rp.SFACE_COSINE_THRESHOLD
    check(cos["portrait~portrait_flipped"] >= t, f"portrait vs its mirror matches (cosine "
          f"{cos['portrait~portrait_flipped']:.4f} >= {t})", failures)
    for key, v in cos.items():
        if key != "portrait~portrait_flipped":
            check(v < t, f"{key} does not match (cosine {v:.4f} < {t})", failures)


def check(cond, msg, failures):
    print(("  ok    " if cond else "  FAIL  ") + msg)
    if not cond:
        failures.append(msg)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rebuild-images", action="store_true", help="re-create the fixture PNGs from the originals")
    ap.add_argument("--images-only", action="store_true", help="stop after creating missing fixture images")
    ap.add_argument("--blur-study", action="store_true",
                    help="print lapVar of each primary face, sharp and Gaussian-blurred (sigma 1.5 and 3), then exit")
    ap.add_argument("--binding", action="store_true", help="write only golden/binding.json (face binding, F1)")
    args = ap.parse_args()
    if args.blur_study:
        blur_study()
        return
    if args.binding:
        failures = []
        binding(failures)
        if failures:
            sys.exit(f"{len(failures)} binding assertion(s) failed")
        return
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
    binding(failures)
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
        tag = " mirrored" if flipped else ""
        p_q = results[("portrait_frontal", flipped)]["quality"]
        check(p_q is not None and p_q["issues"] == [], f"portrait{tag} has no quality issues (got {p_q and p_q['issues']})", failures)
        c = results[("crew_multi", flipped)]
        check(c["faceCount"] >= 2, f"crew{tag} faceCount >= 2 (got {c['faceCount']})", failures)
        # The crew primary face is ~51 px wide in a 640 px group photo: FACE_TOO_SMALL is correct and the only issue.
        check(c["quality"]["issues"] == ["FACE_TOO_SMALL"],
              f"crew{tag} primary quality issues == ['FACE_TOO_SMALL'] (got {c['quality']['issues']})", failures)
    for (name, flipped), res in results.items():
        print(f"  info  {name}{' mirrored' if flipped else ''} livenessScore = {res['livenessScore']}")
    if failures:
        sys.exit(f"{len(failures)} golden assertion(s) failed")


if __name__ == "__main__":
    main()
