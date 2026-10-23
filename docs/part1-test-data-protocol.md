# Part 1: liveness test recordings

This is how the team records the face clips that test the device path, the analyzer (Part 2) and the engine (Part 3) end to end. Code cannot create these clips: they need real people, prints and screens in front of a real camera.

## Privacy rules (read first)

Face videos are biometric data.

- **Consent.** Record only people who have agreed, and only for this project. Keep the consent note outside the repo.
- **No real names.** Name files with a person ID such as `p03`. Keep the ID-to-name list off the repo, or don't keep one.
- **Never commit face clips.** `recordings/` is in `.gitignore`. Share clips only through the team's private storage. Delete them when the project ends, or when anyone asks.
- **Traces are safe to commit.** Part 3's `TraceRecorder` writes scale-free numbers only, with no pixels or landmark coordinates, so a face cannot be rebuilt from them. Commit traces only from people who agreed; see `liveness-tools/README.md`.
- **Synthetic clips are fine.** The one clip in the repo (`registration-services/src/test/resources/liveness/videos/`) is synthetic and contains no face.

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

**Today the analyzer is the Part 1 stand-in (`HeuristicFaceAnalyzer`), which is not a real PAD.**
- It will pass some attack clips: a static photo scored 1.0 in our check.
- Expect `print_attack`, `screen_attack` and `replay_attack` clips to fail until Part 2's model is plugged in.
- Those failures are the PAD gaps the team needs to see. Do not rename the files to make them pass.

## Tuning (Part 3)

Record the same labels as traces with `TraceRecorder` (`liveness-tools/README.md`). Then run `DetectorTuningReport`, which reports detection rates, false-alarm rates and attack-pass rates for the thresholds.
