# Part 1: liveness test recordings

This is how the team records the face clips that test the device path, the analyzer (Part 2) and the engine (Part 3) end to end. Code cannot create these clips: they need real people, prints and screens in front of a real camera.

## Privacy rules (read first)

Face videos are biometric data.

- **Consent.** Record only people who have agreed, and only for this project. Keep the consent note outside the repo.
- **No real names.** Name files with a person ID such as `p03`. Keep the ID-to-name list off the repo, or don't keep one.
- **Never commit face clips.** `recordings/` is in `.gitignore`. Share clips only through the team's private storage. Delete them when the project ends, or when anyone asks.
- **Traces are safe to commit.** Part 3's `TraceRecorder` writes scale-free numbers only, with no pixels or landmark coordinates, so a face cannot be rebuilt from them. Commit traces only from people who agreed; see `liveness-tools/README.md`.
- **Clips in the repo show no person.** `registration-services/src/test/resources/liveness/videos/` holds one synthetic clip with no face, and four attack clips of a public-domain NASA portrait (see "Bundled regression clips" below). Nothing else is committed.

## What to record

Aim for at least 5 people × 3 takes of each label, 6–10 seconds each, with varied lighting (window, ceiling light, dim room), with and without glasses, at 40–80 cm.

| Label | What happens in front of the camera | Expected result in the file name |
|---|---|---|
| `live` | The person looks at the camera, blinks naturally and keeps still | `__expect-PASSED` |
| `print_attack` | A printed photo of the person, held flat and also bent, filling the frame | `__expect-FAILED-PAD_FAILURE` |
| `screen_attack` | A phone or tablet showing a photo of the person | `__expect-FAILED-PAD_FAILURE` |
| `replay_attack` | A phone or monitor playing a video of the person blinking and turning | `__expect-FAILED-PAD_FAILURE` |
| `no_face` | Empty background | `__expect-FAILED-FACE_NOT_DETECTED` |

**Live clips can only test the passive path.** Challenge prompts are random, so a recording cannot answer them, and that is the point. Challenges are tested live, with `LivenessHarness` in webcam mode, and with Part 3's traces.

## Recording

From `registration-client/registration` with this repo copied over it, after one `mvn install`:

    scripts/record-liveness-clip.sh print_attack p03 1        # macOS / Linux
    scripts\record-liveness-clip.ps1 -Label print_attack -Person p03 -Take 1   # Windows

The script:
1. counts down
2. records from the default webcam with `MjpegRecorder` (8 s at 15 fps by default)
3. writes `recordings/p03-print_attack-1__expect-FAILED-PAD_FAILURE.mjpeg`

The file uses the SBI `/stream` wire format, so the same file works in all of these:

- `MockFrameSource.ofVideoFile`, the mock device
- `LivenessHarness video <file>`, to watch it through the engine
- the patched MockMDS `video` mode, the real SBI path (`mock-mds-patch/README.md`)
- the recorded-video end-to-end tests below

To record through a real SBI device or MockMDS instead of a webcam:

    mvn -q exec:java -pl registration-services \
      -Dexec.mainClass=io.mosip.registration.liveness.device.demo.SbiStreamProbe \
      "-Dexec.args=4501 <serialNo> 150 --save recordings/p03-live-1__expect-PASSED.mjpeg"

## Running the end-to-end tests on the recordings

    mvn -pl liveness-api,liveness-core,registration-services -am test \
      -Dtest=RecordedVideoE2ETest -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip \
      -Dliveness.videos.dir=$(pwd)/recordings

`RecordedVideoE2ETest` plays each clip through:
1. the mock device
2. the analyzer
3. the real engine, with one attempt

It then checks the decision and the error code against the file name.

**The analyzer is Part 2's ONNX analyzer** (YuNet, FaceMesh V2 and MiniFASNet PAD from the signed model bundle,
see `docs/part2-pad-architecture.md`).
- `-Dliveness.analyzer=heuristic` selects the earlier stand-in (`HeuristicFaceAnalyzer`, now in `liveness-analyzer`),
  which is not a real PAD: it passes some attack clips (a static photo scored 1.0 in our check).
- Part 2's evaluation is provisional: the bona fide side is one subject (`docs/part2-iso30107-evaluation.md`).
  An attack clip that passes is a PAD gap the team needs to see. Do not rename the files to make them pass.

## Bundled regression clips (F16)

Four attack clips run with every `RecordedVideoE2ETest`, with no extra setup:

| Clip | In front of the camera | Expected result in the file name |
|---|---|---|
| `nasa-print-normal` | the NASA portrait printed on A4, at normal distance | `__expect-FAILED-PAD_FAILURE` |
| `nasa-print-close` | the same print, held close | `__expect-FAILED-CHALLENGE_TIMEOUT` |
| `nasa-phone-normal` | the portrait on a phone, at normal distance | `__expect-FAILED-POOR_QUALITY` |
| `nasa-phone-close-fill` | the phone held close, its screen filling the view | `__expect-FAILED-PAD_FAILURE` |

- **Format.** Each clip is 5 s at 15 fps: 75 frames at 640 × 480, in the SBI `/stream` format, 1.67–1.80 MB.
  `ClipPacker` packed them.
- **Privacy.** Only the portrait (the Wikimedia original, public domain) is in view, with at most a hand at the edge
  of the frame. Before the commit:
  - the face detector found exactly one face in each of the 300 frames;
  - the participant and Claude viewed all 300 frames.
- **The codes record today's behaviour.** They were chosen after the engine ran: each clip failed with the same
  code in 3 of 3 runs of `scripts/probe-regression-clips.ps1`.
  - If a model or threshold change makes a clip PASS, that is a PAD regression. Report it; do not rename the file.
  - If only the code changes (for example PAD_FAILURE to CHALLENGE_TIMEOUT), find out why before renaming.
- **Result.** In the Registration Client build, with the bundled folder, `RecordedVideoE2ETest` passed 5 of 5:
  these four clips and the no-face clip.
- **How they were recorded:** `docs/part2-dataset-protocol.md`, "Regression clips (F16)".

## Tuning (Part 3)

Record the same labels as traces with `TraceRecorder` (`liveness-tools/README.md`). Then run `DetectorTuningReport`, which reports detection rates, false-alarm rates and attack-pass rates for the thresholds.
