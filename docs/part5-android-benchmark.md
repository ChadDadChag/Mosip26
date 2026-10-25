# Part 5: Android liveness on a real phone (benchmark and field checks)

Person 5, P5-5, 2026-10-24. One phone, one tester. Numbers that come from a person's
recordings are **single subject s01, provisional**. Everything here is aggregates and
timings: no images, no landmarks and no per-frame data of a person.

The Android integration lives in the Android Registration Client fork (branch
`person5-liveness`). This document records what was measured there and the settings
chosen from it.

## Summary

- **Android analyzer defaults: CPU execution provider, 2 intra-op threads.** In the profile build
  this was the fastest setting (26.6 ms per frame at p50) and matched the CPU 1-thread reference
  exactly. XNNPACK was about 2x slower, NNAPI about 4x slower, and 4 threads slower than 2.
- **INT8 is not recommended.** On the phone it is slower than fp32 (the mesh takes 18.0 ms
  instead of 8.9 ms), and it moves scores by up to 0.24 and landmarks by tens of pixels.
- **Real sessions in the profile build:**
  - About 42–57 ms per analysed frame (p50).
  - About 8 analysed frames per second during the passive check and 12 during challenges.
  - YUV conversion takes about 4–6% of one core.
  - The whole app uses about 236 MB (proportional set size, PSS) when idle with the models
    loaded, and up to 367 MB during a session.
- **Exposure assist (finding F8) works in the dark.**
  - With the assist off, the face stayed at a brightness of 20–26 (0–255 scale): the user was
    told to improve the lighting, the attempt failed and the session was cancelled.
  - With the assist on, exposure stepped up within about 4 s and the session passed passively.
- **Model update (plan A8):**
  - install, the "not newer" refusal and the refusal of a tampered copy all behaved as
    designed;
  - rollback returned to the bundled models;
  - restarts kept the installed state.
- **Airplane mode:**
  - with airplane mode and Wi-Fi off, the app cold-started, loaded the models, and passed a
    real and a fake check;
  - the liveness logs contain no network errors.
  - The ONNX Runtime library contains a telemetry component that starts with the app. It is
    now removed from the app.
- **Size:**
  - the arm64 profile APK is 256 MiB, and ONNX Runtime for four processor types (ABIs) is
    134 MiB of it;
  - an arm64-only APK would be about 145 MiB; measured in fix round 2 with `--split-per-abi` and
    the five-model bundle: 151.3 MiB (section G);
  - distribute per ABI (an Android App Bundle, or split APKs).

## Device and builds

| | |
|---|---|
| Phone | Samsung Galaxy S23 FE (SM-S711B) |
| Processor | Exynos 2200 (s5e9925): 1 x 2.8 GHz + 3 x 2.5 GHz + 4 x 1.8 GHz cores |
| Memory | 8 GB (7.1 GiB reported) |
| OS | Android 16 (API 36) |
| Runtime | onnxruntime-android 1.31.0, CameraX 1.3.4, Flutter 3.10.4 |
| Models | signed bundle `pad-2026.10.1`: YuNet 2026may, FaceMesh V2, MiniFASNet V2 (2.7) and V1SE (4.0) |
| Camera | front camera, 640 x 480 analysis stream, rotated to 480 x 640 frames, at most 15 frames/s delivered |
| Debug build | `flutter build apk --debug`: debuggable, all ABIs |
| Profile build | `flutter build apk --profile --target-platform android-arm64`, signed with the debug key; **not debuggable and not coverage-instrumented** (changed in this phase) |

`BuildConfig.LIVENESS_DIAGNOSTICS` is true in debug and profile builds and false in release. It
gates the debug page, config overrides, detailed logs, the benchmark and the model bundle
install.

## A. Analyzer benchmark

**Method.** The debug page's "Benchmark" button runs this sequence:
1. It captures 60 front-camera frames into memory while the tester faces the camera for about
   5 s. All 60 frames had one face.
2. It stops the camera.
3. For each setting it measures, in order:
   - **cold preload:** a fresh analyzer factory verifies the bundle and builds the ONNX
     sessions;
   - **first frame:** the first `analyze()` call;
   - **timed passes:** 10 warm-up calls, then 3 timed passes over the 60 frames;
   - **parity:** one pass on a fresh analyzer, compared frame by frame with CPU, 1 thread.
4. It discards the frames.

The frames are never written anywhere. The stage times come from `AnalyzerStats`, and a stage
counts only on frames where it ran. "pad" covers both MiniFASNet models. Pose takes under
0.05 ms and is not shown.

### Profile build (the numbers to use)

Times are in ms; per-stage values are p50 / p95.

| setting | provider in use | cold preload | first frame | detect | mesh | pad | cues | quality | **total** | matches CPU, 1 thread |
|---|---|---|---|---|---|---|---|---|---|---|
| CPU, 1 thread | CPU | 156* | 177* | 11.1 / 12.3 | 9.4 / 10.5 | 5.4 / 6.1 | 0.6 / 1.2 | 0.3 / 0.3 | **27.3 / 29.1** | reference |
| **CPU, 2 threads** | CPU | 92 | 25 | 10.7 / 13.4 | 8.9 / 12.2 | 5.6 / 8.1 | 0.7 / 0.8 | 0.4 / 0.4 | **26.6 / 32.3** | yes |
| CPU, 4 threads | CPU | 110 | 31 | 21.5 / 39.2 | 16.0 / 27.6 | 14.7 / 29.1 | 0.9 / 4.7 | 0.4 / 2.6 | 56.1 / 82.1 | yes |
| XNNPACK, 1 | XNNPACK | 156 | 55 | 15.3 / 16.9 | 25.6 / 27.3 | 11.6 / 12.5 | 0.8 / 0.9 | 0.4 / 0.5 | 53.7 / 58.0 | yes |
| XNNPACK, 2 | XNNPACK | 193 | 63 | 16.9 / 20.8 | 26.5 / 31.0 | 12.7 / 15.8 | 0.8 / 1.0 | 0.4 / 0.5 | 58.0 / 68.5 | yes |
| XNNPACK, 4 | XNNPACK | 158 | 100 | 24.8 / 30.4 | 63.3 / 94.4 | 58.5 / 81.7 | 0.8 / 1.0 | 0.4 / 0.6 | 144.9 / 200.5 | yes |
| NNAPI, 1 | NNAPI | 196 | 120 | 23.1 / 28.2 | 17.1 / 18.5 | 82.8 / 86.7 | 1.0 / 1.3 | 0.5 / 0.6 | 124.3 / 130.9 | yes |
| NNAPI, 2 | NNAPI | 138 | 96 | 16.9 / 23.8 | 14.0 / 16.8 | 79.7 / 85.4 | 1.1 / 1.5 | 0.6 / 0.8 | 113.4 / 120.8 | yes |
| NNAPI, 4 | NNAPI | 137 | 111 | 18.5 / 30.7 | 15.7 / 25.1 | 76.6 / 87.8 | 0.9 / 1.4 | 0.5 / 0.8 | 113.6 / 130.5 | yes |

\* CPU with 1 thread ran first, so its preload and first frame also include one-time costs:
loading the ONNX Runtime native library, class loading and JIT compilation.

**Parity.**
- Every fp32 setting gave the same livenessScore as the reference (|difference| < 0.00005) and
  the same landmarks (< 0.005 px) on all 60 frames.
- No frame had a score or landmarks on one side only.
- The planner's limits were |difference| ≤ 0.01 and ≤ 0.5 px, so no accelerator was rejected on
  accuracy.

**Provider in use.**
- ONNX Runtime accepted XNNPACK and NNAPI on Android 16; the analyzer did not fall back to CPU.
- NNAPI is deprecated from Android 15 and was the slowest here: about 80 ms for the two
  MiniFASNets.

### Debug build (for comparison)

Total time per frame, p50 / p95 in ms:

| CPU, 1 | CPU, 2 | CPU, 4 | XNNPACK, 1 | XNNPACK, 2 | XNNPACK, 4 | NNAPI, 1 | NNAPI, 2 | NNAPI, 4 |
|---|---|---|---|---|---|---|---|---|
| 34.9 / 37.8 | 34.4 / 39.2 | 49.3 / 68.8 | 67.7 / 71.3 | 48.3 / 51.1 | 147.3 / 207.7 | 124.5 / 130.7 | 120.2 / 125.7 | 118.6 / 136.5 |

The debuggable build is about 30% slower on CPU, mostly in the Java pre-processing (detect:
14.6 ms instead of 10.7 ms). The ranking of the settings is the same in both builds.

### Defaults chosen

`LivenessServiceHolder.ANDROID_ANALYZER_DEFAULTS` sets **`accelerator = CPU`** and
**`intraOpThreads = 2`**. The reasons:
- It had the lowest p50 per frame in both builds, by under 1 ms over 1 thread, which is within
  noise; its p95 is 3 ms worse.
- It matches the reference exactly.
- It is the analyzer's own default, so the Desktop and Android defaults agree.

Four threads are slower, probably because some of the work then runs on the slow cores and the
threads wait for each other. XNNPACK and NNAPI are slower on this processor.

The defaults apply only when the config has no value for the key. Any synced value or debug
override wins. An invalid analyzer config falls back to the Android defaults and logs an error.

| key | Android default | values |
|---|---|---|
| `mosip.registration.liveness.face.analyzer.intraOpThreads` | 2 | integer ≥ 1 |
| `mosip.registration.liveness.face.analyzer.accelerator` | CPU | CPU, XNNPACK, NNAPI (an unavailable one falls back to CPU) |
| `mosip.registration.liveness.face.android.exposureAssist` | true | false turns the dim-face exposure assist off |
| `mosip.registration.liveness.face.android.<workflow>.camera` | front | front, back (P5-4) |

New sessions pick up a changed analyzer setting after the next global-param sync. Running
sessions keep theirs.

## Real sessions (profile build)

All sessions used the real engine, the RESIDENT workflow and the front camera.

| session | frames analysed | time | analysis rate | per frame p50 / p95 (ms) | result |
|---|---|---|---|---|---|
| normal light | 10 | 1.1 s | 8.0/s | 57.5 / 73.5 | passed without a challenge |
| dim light, exposure assist off | 60 | 5.0 s | 11.9/s | 42.1 / 63.7 | passed after 2 challenges |
| dark, exposure assist off | 108 | 18.1 s | 5.9/s | 49.2 / 77.0 | cancelled by the tester |
| dark, exposure assist on | 45 | 5.5 s | 8.0/s | 52.9 / 66.1 | passed without a challenge |
| first session of a new app process | 12 | 1.3 s | 8.3/s | 46.0 / 218.7 | passed without a challenge (the first frame is cold) |

**Why a frame takes longer in a session than in the benchmark** (about 45–55 ms against 27 ms):
- In a session the camera, the preview and the YUV-to-RGB conversion run at the same time as the
  analyzer.
- At 8 frames per second the processor idles between frames, so it likely does not stay at full
  clock speed.

This was not investigated further. It costs no visible delay: the engine analyses at about
8 frames per second during the passive check and up to 12 during challenges.

**Camera conversion.**
- The camera ran at 25–29.5 frames/s, of which 12–14.7 frames/s were delivered (paced to at
  most 15).
- YUV-to-RGB took 3.3–4.5 ms per frame on average, which is 3.9–6.4% of one core. The debug
  build took about 16% (P5-3).

**Memory** (`dumpsys meminfo`, whole app: Flutter, the MOSIP client and liveness; fresh
process):

| state | total PSS | native heap PSS |
|---|---|---|
| idle on the liveness test page, models loaded | about 236 MB | about 60 MB |
| during a real session (peak) | 367 MB | 88 MB |
| during the benchmark (60 frames in memory, about 55 MB) | up to 490 MB | up to 155 MB |

**Model loading at app start** (verification and ONNX session creation, CPU, 2 threads):
140–266 ms over 7 cold starts. It runs on a background thread.

## B. INT8 (measured, not adopted)

The method, the desktop results and the Desktop `PadEvaluation` result are in
`models/README.md`, section "INT8 evaluation".
- **Script:** `models/tools/quantize_int8.py`.
- **Method:** static QDQ quantisation with per-channel weights, calibrated on public images only.
- **Models quantised:** the mesh and both MiniFASNets. YuNet stays fp32: opencv_zoo has no INT8
  build of the 2026may model.

On the phone, the INT8 models were in a separate test bundle (`pad-2026.10.3`). It was signed
locally with the development key, loaded by the benchmark through the normal signature check,
and not committed. Times are per frame in ms, profile build, p50:

| setting | mesh, fp32 → INT8 | pad (both models), fp32 → INT8 | total, fp32 → INT8 |
|---|---|---|---|
| CPU, 1 thread | 9.4 → 19.8 | 5.4 → 8.9 | 27.3 → 41.7 |
| CPU, 2 threads | 8.9 → 18.0 | 5.6 → 9.6 | 26.6 → 40.4 |
| XNNPACK, 2 threads | 26.5 → 22.8 | 12.7 → 9.5 | 58.0 → 45.2 |

**Parity against fp32 CPU, 1 thread, over the 60 frames:**
- profile run: max |Δ livenessScore| 0.10, max landmark distance 31 px;
- debug run (different frames): 0.24 and 88 px, and 2 frames where the score or the landmarks
  were present on one side only.

INT8 is slower because the models' PReLU layers stay in float, so every layer converts in and
out of 8-bit. **Recommendation: do not adopt INT8** (shipping it would also need planner
approval). Revisit only with quantisation-aware training or PReLU-free models.

## C. Persisted anonymised metrics

- **When:** after each real-engine session, the deltas of `LivenessMetrics` are added to
  SharedPreferences (`liveness_metrics`) by `LivenessMetricsStore`.
- **What:** counts and totals only: no session ids, timestamps, individual scores or images.
  The fake engine's sessions are not persisted.
- **Read:** Pigeon `metrics()` returns the totals and the derived rates. The debug page shows
  them on a Metrics card with a Reset button.

After this testing the store held the counts of **10 sessions across 7 app processes** (restarts
and reinstalls). They match the session logs one for one:

| sessions | passed without a challenge | passed after challenges | final failures | cancelled | retries | attempt failures | average processing per frame | average challenge completion | retry rate | failure rate | passive share |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 8 | 1 | 0 | 1 | 1 | 1 (POOR_QUALITY) | 55.2 ms (288 frames) | 1251 ms (2 challenges) | 0.10 | 0.00 | 0.89 |

## D. Dim faces: exposure assist (finding F8)

`CameraXFrameSource` (Mosip26 commit 450812e, "Part 1: CameraXFrameSource exposure assist for dim faces")
works as follows:
- **Measure:** it reads the mean brightness of the face region (luma, 0–255) from each
  delivered frame.
- **Step up:** below 70 for more than 1 s, it raises the camera's exposure compensation by one
  step every 500 ms, up to the camera's maximum.
- **Step back:** above 150, it steps back toward 0.
- **Configuration:** on by default; `mosip.registration.liveness.face.android.exposureAssist`
  (the debug page has a switch).
- **Logs:** the session start log and the once-a-second camera log show whether it is on, the
  luma and the exposure index.

Each test pair kept the same position and light. The assist was off for the first session of the
pair and on for the second.

| light | assist | face luma | exposure index | lighting hint | challenge | result |
|---|---|---|---|---|---|---|
| dim | off | 77–89 | 0 | no | yes (smile, blink): passive score 0.67 | passed in 5.8 s |
| dim | on | 48 | 0 (passed in 1.7 s, before the 1 s delay could act) | no | no: passive score 0.94 | passed in 1.7 s |
| dark (screen light only) | off | 20–26 for 19 s | 0 | **"improve the lighting"**, then the attempt failed (POOR_QUALITY) and the retry showed the hint again | no | cancelled by the tester at 19.6 s |
| dark | on | 22 → 36 → 77 | 0 → 2 → 4 → 6 → 7 in about 4 s | "improve the lighting" during the first seconds | no: passive score 0.9955 | passed in 6.1 s |

**Caveat.** In the last session the tester also raised the screen brightness slightly, so part of
the rise from 36 to 77 may come from that.

In the first, merely dim pair, the faces were bright enough for the quality checks (too dark is
below 40). The difference in passive score between those two sessions comes from different
frames, not from the assist, which never acted.

## E. Model update on the phone (plan A8)

**Steps.**
1. A test bundle `pad-2026.10.2` was signed with the development key: the same model files and
   parameters as `pad-2026.10.1`, a new version number. It is local and not committed.
2. It was pushed with `adb push` to
   `/sdcard/Android/data/io.mosip.registration_client/files/bundle-staging/`.
3. It was installed from the debug page's "Install model bundle" button.

**What the install does.**
- It verifies the staged bundle first, so a bad bundle never touches the store.
- On the first install it also installs the bundled models as the store's base, so that a
  rollback can return to them.
- It installs through `ModelStore.install`: anti-rollback, then an atomic switch.
- It swaps in a new analyzer, so the next session uses the new bundle. A running session keeps
  its models.

| case | action | result |
|---|---|---|
| a | install `pad-2026.10.2` | installed, after `pad-2026.10.1` was installed as the store's base; reloaded in 158 ms; the next session passed and reported `pad-2026.10.2` |
| e | restart the app | the new process loaded `pad-2026.10.2` (186 ms) |
| b | install the same bundle again | refused: "not newer than pad-2026.10.2" (tapped 4 times); store unchanged |
| c | install a tampered copy (1 byte changed in `face_mesh_v2.onnx`) | refused before the store was touched: `MODEL_INTEGRITY_FAILED`, "sha256 mismatch for face_mesh_v2.onnx"; the next session passed on `pad-2026.10.2` |
| d | Rollback | "rolled back to pad-2026.10.1", reloaded in 235 ms; the next session passed and reported `pad-2026.10.1` |
| e | restart the app | the new process loaded `pad-2026.10.1` (259 ms) |

**Not built: automatic download (`ModelUpdateWorker`).** The design:
- A WorkManager job with a network-connected constraint reads a URL from
  `mosip.registration.liveness.model.update.url`.
- It downloads the manifest, the signature and the model files into a private staging folder
  (not external storage).
- It calls the same install path: verify, then `ModelStore.install`, then reload. A failure
  leaves the current bundle in place and is audited.
- It retries with backoff. Downloads are capped by the same per-file size limit as
  `DirectoryBundleSource`.
- A test setup would use `python -m http.server` with `adb reverse`, and a debug-only
  network-security config allowing cleartext for 127.0.0.1 only.

## F. Airplane mode

1. With airplane mode on and Wi-Fi off (confirmed in the system log), the app was closed and
   started cold.
2. The bundle verified and loaded in 259 ms.
3. The real "Resident face" gate passed (score 0.967, 2.0 s).
4. The fake engine's "Resident face" gate finished as scripted: passed after the blink and smile
   challenges, 8.6 s.

The liveness logs (`LivenessApi`, `LivenessService`, `CameraXFrameSource`) contain no network
errors.

**Finding: ONNX Runtime's telemetry component.**
- **What it is:** the onnxruntime-android 1.31.0 package registers
  `ai.onnxruntime.TelemetryInitializer`. This component starts a telemetry HTTP client when the
  app starts. It collects the device class, locale, time zone and an id, and the package declares
  the INTERNET and ACCESS_NETWORK_STATE permissions for it.
- **Today:** in 1.31.0 it fails at every app start ("ORTTelemetry: Unable to initialize
  telemetry"; a native function is missing), so nothing was sent. A later version could send.
- **Fix:** the liveness module's manifest now removes the component (`tools:node="remove"`). The
  merged manifest no longer contains it, and the app starts without the warning. Models still
  load and sessions pass; inference does not use the component.

## G. Size

| item | size |
|---|---|
| debug APK (all ABIs, debuggable) | 431.3 MiB |
| **profile APK, `--target-platform android-arm64`** | **256.1 MiB** |
| ONNX Runtime in that APK: arm64-v8a / armeabi-v7a / x86 / x86_64 | 32.6 / 23.1 / 38.9 / 38.9 MiB (+ 0.1 MiB JNI each): **134 MiB, 52%** |
| other ABIs' native libraries in that APK (ONNX Runtime, sqlcipher and others) | 111.6 MiB |
| model files (assets) | 6.0 MiB |
| estimated arm64-only APK | about 145 MiB |

**What the ABI list does to the size.**
- `--target-platform` removes only Flutter's own libraries. The ONNX Runtime package brings all
  four ABIs, and the release build type lists all four in `abiFilters`.
- A universal release APK therefore carries four copies of ONNX Runtime.

**Measured per ABI (fix round 2, F10).** `flutter build apk --profile --split-per-abi`, bundle
`pad-2026.10.5` (five models, 17.7 MiB, with the int8 SFace for face binding):

| APK | size | native libraries |
|---|---|---|
| arm64-v8a | 158,607,245 B (151.3 MiB) | arm64-v8a only, 66.1 MiB (ONNX Runtime 32.6 MiB) |
| armeabi-v7a | 145,315,101 B (138.6 MiB) | armeabi-v7a only |
| x86_64 | 167,078,781 B (159.3 MiB) | x86_64 only |

Against the single arm64-targeted APK above (256.1 MiB, four-model bundle), the arm64 split is
105 MiB smaller even with face binding. Flutter builds no x86 split; the x86 copy of ONNX Runtime
goes. The release build type's `ndk.abiFilters` (upstream) made Gradle refuse `--split-per-abi`
("cannot be present when splits abi filters are set"). It now applies only without a split.

**Recommendations.**
- **Distribute per ABI: split APKs for sideloading (`flutter build apk --release --split-per-abi`),
  or an Android App Bundle (`flutter build appbundle`) through a store.** Most current Android
  phones and tablets are arm64, so the arm64 split is the one to test.
- Backlog: a reduced ONNX Runtime build containing only the operators these five models use
  would shrink the 32.6 MiB arm64 library further. Not done.

## Low-end phones: caveat and what to try

Only the S23 FE was tested. It is a mid-to-high-end phone with one fast core, three
medium-fast cores and four slow cores. Do not assume these times on a low-end device.

**Budget.**
- **Time per frame:** the passive check needs 10 good frames. Below about 8 analysed frames per
  second, sessions get longer. The camera keeps only the latest frame, so a slow device skips
  frames rather than queueing them.
- **Memory:** the app needs about 370 MB during a session.

**Ways to cut work per frame, in the order to try:**
1. Compare `intraOpThreads` 1 and 2. On a phone with only slow cores, 1 thread can win.
2. Try `accelerator = XNNPACK` with 1–2 threads. It lost here, but its kernels can beat the
   default CPU kernels on older cores. Always confirm parity with the debug page's Benchmark
   button, which reports it per setting.
3. Lower `detectorMaxWidth` (analyzer key, default 320; for example 256). This cuts the detect
   stage, the largest one. Check that small faces are still found (minimum face width is 80 px).
4. Lower the analysis resolution. `LivenessApi.ANALYSIS_RESOLUTION` is 640 x 480 and needs a code
   change. Face crops get fewer pixels, so re-check the quality thresholds.
5. Avoid INT8 as it stands: it was slower and less accurate here.

## Reproduce

These are the steps in the Android Registration Client fork.

**Builds:**
- `flutter build apk --debug`
- `flutter build apk --profile --target-platform android-arm64`
- Install either with `adb install -r --user 0 <apk>`.

**Benchmark:**
1. Open Login page → "Liveness test" → "Benchmark".
2. Face the camera for 5 s, then leave the phone still for 3–5 minutes.
3. The report shows on the page and in logcat as `LivenessApi: benchmark| ...` lines.
4. Optional: put extra signed bundles in `files/bench-bundles/<name>/`.

**Model update:**
1. Push a signed bundle with
   `adb push <bundle files> /sdcard/Android/data/io.mosip.registration_client/files/bundle-staging/`.
2. On the debug page, use "Install model bundle" or "Rollback".

**Memory:** run `adb shell dumpsys meminfo io.mosip.registration_client` during a session.

**INT8:**
- Run `python -I models/tools/quantize_int8.py --eval-data eval-data` (Mosip26).
- Sign a local test bundle with `ModelBundleTool manifest` and `ModelBundleTool sign` (see
  `models/README.md`).
- Run `PadEvaluation --bundle <dir>`.
