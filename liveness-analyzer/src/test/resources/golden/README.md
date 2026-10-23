# Golden fixtures for the Part 2 analyzer

`models/tools/make_golden.py` generates these files from `models/tools/reference_pipeline.py`,
which is the executable specification. Java parity tests compare against them.

**Never add team members' faces or any other personal images here.** Use only public-domain or
explicitly licensed images.

## Images

Both images are works of the US federal government (NASA). They are in the public domain in
the United States ({{PD-USGov-NASA}} on Wikimedia Commons).

| File | Subject | Source | Original |
|---|---|---|---|
| `portrait_frontal.png` (554×640) | Astronaut L. Gordon Cooper, official portrait, 10 Sep 1964. Frontal, neutral expression. US National Archives (NARA 17447491) | https://commons.wikimedia.org/wiki/File:AMERICAN_AND_SOVIET_ASTRONAUTS_-_COOPER_-_MERCURY_9_-_GEMINI_5_-_NARA_-_17447491.jpg | 4197×4845, Commons SHA-1 `a5115a92e5906076fe67a9c5e0b31a657972691a` |
| `crew_multi.png` (640×512) | Expedition 17 crew portrait (Chamitoff, Reisman, Volkov, Kononenko), 14 Feb 2008, NASA JSC. Four frontal faces | https://commons.wikimedia.org/wiki/File:Expedition_17_crew_portrait_B.jpg | 6763×5410, Commons SHA-1 `da481a0a52a9e5af49a04af0a74768137631749c` |

Preparation, in `make_golden.py`:
- Download the original and check its SHA-1.
- Downscale with OpenCV `INTER_AREA` so the longest side is 640 px, a realistic camera size.
- Save as a **lossless PNG**. PNG decodes to identical pixels in OpenCV and in Java ImageIO, so
  Java parity can be tight.

The committed PNG is the fixture. It is never regenerated unless `--rebuild-images` is passed.

## JSON files

- `<name>.json` comes from the image as decoded (BGR 8-bit, 3 channels).
- `<name>_flipped.json` comes from the decoded pixels mirrored horizontally in memory. There is
  no flipped image file: Java tests flip the decoded raster themselves.
- `meta.models` records the SHA-256 of each model used. Regenerate the fixtures if a model changes.

Main fields (frame pixels, continuous coordinates with pixel centre = i + 0.5, y down; `null` = NaN):

| Field | Meaning |
|---|---|
| `image` | decoded width and height |
| `yunet` | detector input geometry: `scale`, resized and padded W/H |
| `detections` | every face after NMS: `box` [x,y,w,h], `score`, `kps` (5 × [x,y]) |
| `faceCount`, `primary`, `faceBox` | count with area ≥ 0.2 × the largest face; primary = the largest face |
| `meshRoi` | `cx`, `cy`, `S`, `theta` (radians) |
| `meshFlag` | sigmoid of the presence logit |
| `meshPoints` | 478 × [X, Y, Z]: FaceMesh V2, iris centres at 468 (subject's right) and 473 (left) |
| `landmarks` | the contract's `FaceLandmarks`, including `leftIris` and `rightIris` |
| `earLeft`, `earRight` | eye aspect ratio of each eye |
| `yawDeg`, `pitchDeg`, `rollDeg` | head pose; + yaw = subject's left, + pitch = up, + roll = subject's left eye higher. `pitchDeg = pitchRaw − PITCH_BIAS_DEG` (−9.5) |
| `pitchRaw` | pitch before the bias correction |
| `pad.intBox` | integer box fed to the crop |
| `pad.v2`, `pad.v1se` | `cropBox` (inclusive ltx, lty, rbx, rby), `logits`, `softmax` |
| `livenessScore` | (softmax_v2[1] + softmax_v1se[1]) / 2 |
| `tensorStats` | for the 4 model inputs (`yunet`, `mesh`, `pad_v2`, `pad_v1se`): shape, mean, std and the first 8 values in memory order |
| `timingsMs` | Python timings; informational only |

Mesh, pose and PAD are computed on the primary face even when `faceCount > 1`, so `crew_multi`
covers every stage. The production analyzer skips them for multi-face frames.
