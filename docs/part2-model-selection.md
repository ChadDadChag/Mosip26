# Part 2: model selection

This document explains which models the passive liveness and PAD engine (`liveness-analyzer`) uses, and why.
All four models are third-party, pre-trained and unmodified apart from format conversion. Provenance, pinned
sources, hashes and conversion checks are in [models/README.md](../models/README.md). Licence texts are in
[models/LICENSES.md](../models/LICENSES.md).

## The choice in one table

| Stage | Chosen | Size | Java CPU time per frame (p50) | Licence |
|---|---|---|---|---|
| Face detection | YuNet `2026may` (OpenCV Zoo) | 229,738 B | 5.1 ms | MIT |
| Landmarks + head pose | MediaPipe FaceMesh V2 (478 points), converted to ONNX | 4,921,061 B | 7.2 ms (pose 0.02 ms) | Apache-2.0 |
| Passive PAD | MiniFASNet V2 (crop scale 2.7) + MiniFASNet V1SE (crop scale 4.0), score = mean of their "real" probabilities | 1,743,495 B + 1,742,663 B | 4.7 ms for both | Apache-2.0 |
| Replay cue | Moiré from a 64 × 64 DFT of the face crop (our code, no model) | – | 0.4 ms | – |
| Runtime | ONNX Runtime 1.31.0 (`ai.onnxruntime`) | – | – | MIT |

**Total:** 8.24 MiB of models and about **18 ms per frame** for the whole `analyze()` call, at p50 on the development
laptop (AMD Ryzen AI 7 350, 1 intra-op thread). See [the benchmark](../liveness-eval/reports/benchmark-20261023.md).

## Face detector: YuNet 2026may vs 2023mar

| | 2026may (chosen) | 2023mar (fallback) |
|---|---|---|
| Input | symbolic H × W (any multiple of 32) | fixed 1 × 3 × 640 × 640 |
| What that means for us | runs on the frame downscaled to about 320 px wide, so the cost is small and there is no letterbox | every frame is letterboxed into 640 × 640 (top-left), about 4× the pixels |
| Outputs | `cls / obj / bbox / kps` at strides 8, 16 and 32, with 5 keypoints (eyes, nose, mouth corners) | same family |
| Evidence | Java decoding matches the Python reference exactly on the golden images (box IoU 1.000000, keypoint error 0.0000 px) | not built; kept as a documented fallback |

The detector also gives the face count. A second face counts only when its box is at least 20% of the primary face's
area, so a distant passer-by does not fail a session.

## Landmarks: legacy 468-point mesh vs FaceMesh V2

Phase 1 started with the legacy `face_landmark.tflite` (468 points). Its eyelid contour never fully closes, so blinks
could not be detected. A webcam A/B on the same frames, using the contract's eye-aspect-ratio (EAR) points:

| Mesh | Open-eye EAR | Blink minimum / open | 2 s eyes-closed hold / open | ORT time per run |
|---|---|---|---|---|
| legacy 468 | 0.23–0.24 | 0.62–0.63 | 0.69 | 3.2 ms |
| **FaceMesh V2** | 0.29 | **0.04** | **0.16–0.22** | 9.4 ms |

Part 3's blink detector needs "closed" below 0.6 × baseline. Only V2 gets there, and with a wide margin. V2 also
gives the iris centres (points 468 and 473), and it contains only standard ops, so it converts cleanly to ONNX.

Head pose comes from the mesh geometry. The mesh reports neutral heads about 9.5° "down". That bias was measured on
49 frontal public-domain NASA portraits (median −9.45°, IQR 6.3°) and is removed. Its value is a signed parameter of
the model bundle.

## Passive PAD: the candidates

Silent-Face-Anti-Spoofing (Minivision) ships two small CNNs trained on different crop scales. Each returns 3-class
logits, and class 1 is "real". We evaluated them alone, combined, and against a classical baseline, using
ISO/IEC 30107-3 metrics on the round-1 dataset.

**Single subject s01, provisional**. These numbers come from one person, one webcam and one phone. See
[part2-iso30107-evaluation.md](part2-iso30107-evaluation.md) for the method and the limits.

| Candidate | max APCER @ 0.85 | BPCER @ 0.85 | EER | Comment |
|---|---|---|---|---|
| V2 only | 6/10 (60%) | 1/9 | 31.7% | fooled most by a close-up phone photo |
| V1SE only | 4/10 (40%) | 1/9 | 21.1% | |
| MIN(V2, V1SE) | 4/10 (40%) | 1/9 | 21.1% | no better than the mean |
| **MEAN(V2, V1SE)** | 4/10 (40%) | 1/9 | 21.1% | the upstream fusion; kept |
| **MEAN + session moiré cap (shipped)** | **0/10 [≤ 25.9%]** | 1/9 | **0.0%** | the cap sends close-up screens to a challenge |
| HeuristicFaceAnalyzer (sharpness + colour diversity, no ML) | 0/3 | 4/4 | 70.8% | 87.5% of attack takes and 64% of bona fide takes gave no decision at all |

- **Why the mean stays the base score.** V2, V1SE and MIN were no better. The weak spot all of them share is a phone
  photo held about 10 cm from the camera. At that distance the 2.7× and 4.0× crops contain only screen content: the
  phone's edges and the hand that betray the attack at normal distance are outside the crop.
- **What fixes it.** The moiré of the screen's pixel grid. In round 1, the close-up attacks had a window-median moiré
  of 1.435–1.593, against 1.00–1.37 for every bona fide take.

## Why ONNX Runtime

- **One API on both clients.** `com.microsoft.onnxruntime:onnxruntime` (desktop) and `onnxruntime-android` expose the
  same `ai.onnxruntime` Java API. The same `liveness-analyzer` jar runs in the Registration Client and in the Android
  native layer. The analyzer declares the runtime `provided`, so each app brings its own.
- **Byte-level control.** Sessions are created from byte arrays (`createSession(byte[], options)`). The analyzer
  builds them from exactly the bytes it has just verified against the signed manifest, so no file is re-read between
  the check and the use.
- **Accelerators without code changes.** XNNPACK and NNAPI are execution providers chosen by config. If one is
  unavailable, the analyzer falls back to CPU.
- **Thread safety.** `OrtSession.run` is thread-safe, so one set of sessions is shared by every analyzer.

## Rejected options

| Option | Why it was rejected |
|---|---|
| MediaPipe "attention" face mesh | Uses custom TFLite ops that have no ONNX equivalent. FaceMesh V2 gives the iris points with standard ops. |
| Dynamic INT8 quantisation | Produces `ConvInteger` nodes, which are slow on CPU and NNAPI. If INT8 is needed on Android later, use static QDQ quantisation, re-run the golden parity tests and re-sign the bundle. |
| Face-size gate (ignore frames whose face fills more than A of the picture) | Bona fide close-ups (face area about 0.31) are as large as the close-up attacks (about 0.25). A gate at 0.20 stops 3 of 4 attack passes but turns every bona fide close-up into a non-response. |
| Hard moiré veto (score 0 per frame at moiré ≥ 1.4) | It separated round 1 perfectly. But isolated bona fide frames above 1.4 (far, moving head) drive Part 3's 3-frame PAD monitor to 0 in 3 of 11 bona fide takes, which would fail a real person mid-challenge. It is implemented but disabled until there is data from more devices. |
| A trained PAD model of our own | There is no labelled multi-subject dataset and no time to build one. The pre-trained models plus an evaluated cap are the honest choice. |
