# Test data

This document lists all the sample data needed to test the face liveness solution. It is written for anyone evaluating
the project who has not seen it before.

**In short:**
- You need **no account, password or token** for any test in this project.
- You do not need a biometric device or a MOSIP server.
- Everything you need is in this repository. The submission pack also has a `test-data` folder, listed in section 10.
- The face recordings of real people that we used for our evaluation are **not published**, because they are biometric
  data. Section 9 gives their size and make-up, and explains how to record your own.

**Terms used here:**
- **Presentation attack:** someone shows the camera something other than a live face, such as a printed photo or a
  video on a screen.
- **PAD (Presentation Attack Detection):** the part of the system that detects those attacks.
- **Bona fide:** a genuine presentation, by a live person.

## 1. Accounts and credentials

| Where | What you need |
|---|---|
| Android app, **Face liveness demo** page | Nothing. The page opens from the login screen without signing in. |
| Desktop demo window | Nothing. |
| Automated tests | Nothing. |
| Patched MockMDS (MOSIP's simulated biometric device) | Nothing. |
| MOSIP login, in the Android or Desktop client | Not used. MOSIP's own login needs a MOSIP server and an enrolled user, and this project does not provide either. The demo page and demo window exist so that you can test the liveness check without one. |
| Signing a new model bundle | Not needed for testing. The private signing key is deliberately not shared. Section 7 gives you a bundle that is already signed. |

## 2. Face images

Both images are works of the US federal government (NASA) and are in the public domain in the United States. Neither
shows a member of the team.

| File | What it shows | Use it to test |
|---|---|---|
| [`portrait_frontal.png`](../liveness-analyzer/src/test/resources/golden/portrait_frontal.png) | One frontal face, 554 x 640 pixels (astronaut portrait, 1964) | Print attacks and photo-on-a-screen attacks |
| [`crew_multi.png`](../liveness-analyzer/src/test/resources/golden/crew_multi.png) | Four frontal faces, 640 x 512 pixels (crew portrait, 2008) | The "multiple faces detected" case |

Both are in `liveness-analyzer/src/test/resources/golden/`. The sources, with their Wikimedia Commons links, are in the
[README in that folder](../liveness-analyzer/src/test/resources/golden/README.md). The JSON files next to them are the
expected outputs of each model. The Java tests compare against them to prove that the Java code gives the same results
as the Python reference pipeline.

## 3. Recorded clips

These clips run through the real engine and the real signed models, with no camera. Each one is an MJPEG file (a series
of JPEG images) in the same format that a biometric device sends over MOSIP's `/stream` call. The expected result is in
the file name, and the test `RecordedVideoE2ETest` fails if the engine gives a different result.

Folder: `registration-services/src/test/resources/liveness/videos/`

| Clip | What the camera saw | Expected result |
|---|---|---|
| `nasa-print-normal` | The portrait printed on A4, at normal distance | Failed: `PAD_FAILURE` |
| `nasa-print-close` | The same print, held close | Failed: `CHALLENGE_TIMEOUT` (it could not answer the challenge) |
| `nasa-phone-normal` | The portrait on a phone, at normal distance | Failed: `POOR_QUALITY` (the face is too small to use) |
| `nasa-phone-close-fill` | The phone held close, its screen filling the view | Failed: `PAD_FAILURE` |
| `no-face-blue-screen` | A synthetic blue screen with no face | Failed: `FACE_NOT_DETECTED` |

The four attack clips are 5 seconds at 15 frames per second, 75 frames at 640 x 480. Only the public-domain portrait is
in view, with at most a hand at the edge of the frame. Before they were committed, the face detector found exactly one
face in each of the 300 frames, and all 300 frames were viewed.

**No live-person clips are included,** so these clips test refusal only. A live person's clip could only test the
passive check anyway, because challenges are random and a recording cannot answer them.

To run a clip yourself, see [Usage and installation](usage-and-installation.md), route D.

## 4. Scripted engine scenarios (no camera, no models)

`FakeLivenessService` plays a fixed script with real timing, so that every screen and message can be seen on demand.
Use it through the Android demo page (**Simulated screens**) or the Desktop window (`--scenario <name>`).

| Scenario | What you see |
|---|---|
| `PASS_PASSIVE` | Checking, then passed, with no challenge |
| `PASS_ACTIVE` | Checking, then the challenge count from the policy (resident 2, operator and supervisor 1), then passed |
| `PASS_AFTER_1_CHALLENGE`, `PASS_AFTER_2_CHALLENGES` | A fixed number of challenges, then passed |
| `FAIL_CHALLENGE_THEN_PASS_ON_RETRY` | A failed attempt, then a new sequence after Retry, then passed |
| `PAD_ATTACK` | A failed attempt each time, showing only the generic message, then a final failure for too many attempts |
| `CHALLENGE_TIMEOUT` | A prompt that runs for the full timeout, then a failed attempt, then passed after Retry |
| `NO_FACE_THEN_PASS` | A "no face" hint, then checking again with the hint cleared, then passed |
| `MULTIPLE_FACES` | A "multiple faces" hint, then a failed attempt, then passed after Retry |
| `POOR_LIGHTING_HINTS` | "Improve lighting", then "move closer", then challenges, then passed |
| `DEVICE_DISCONNECT` | A device error during a challenge. Retry does not use up an attempt. Then passed |
| `DEVICE_FAILURE` | The device keeps failing: Retry three times, then a final device failure |
| `MAX_RETRIES_EXCEEDED` | Four failed attempts, then a final failure. For an operator this starts the 60-second cooldown |
| `ENGINE_ERROR` | A failed attempt that does not use a retry, then a final engine failure |
| `RANDOM` | One of the above, picked for each session |

## 5. Synthetic traces

The folder `liveness-tools/src/test/resources/traces/` has 20 synthetic trace files: two each of blink, smile, turn left,
turn right, look up, look down, neutral, photo attack, screen attack and replay attack. A trace records the measurements
that the action detectors use (such as eye openness and head angle) for each frame. It has no image and no face
coordinates, so no face can be rebuilt from it. The replay tests use these files to check the challenge detectors, and
`DetectorTuningReport` uses them to report detection and false-alarm rates.

## 6. Configuration samples

| File | What it is |
|---|---|
| [`liveness-default.properties`](../liveness-api/src/main/resources/liveness-default.properties) | The default settings. Workflow keys override the `.default.` keys. |
| [`part4-registration-default.properties`](part4-registration-default.properties) | The block to add to the MOSIP server's `registration-default.properties` for the Desktop client. |

The defaults that matter most when you test:

| Setting | Default | Meaning |
|---|---|---|
| `passiveThreshold` | 0.85 | At or above this score, the face passes with no action |
| `padRejectThreshold` | 0.30 | Below this score, the attempt fails as an attack |
| `challengeTypes` | `BLINK,SMILE,TURN_LEFT,TURN_RIGHT` | The actions the system can pick from |
| `minChallenges` | 2 (operator and supervisor: 1) | How many actions to ask for |
| `challengeTimeoutMs` | 6000 | Time allowed for each action |
| `maxRetries` | 3 | Retries after the first attempt |
| `onRepeatedFailure` | `BLOCK` (operator: `COOLDOWN`) | What happens after the last attempt |
| `cooldownSec` | 60 | How long an operator waits after repeated failures |

To force a challenge on a live face, set `passiveThreshold` to 0.9999. The Android demo page has a switch for this:
**Always ask for actions**.

## 7. Model bundles

| Bundle | Where | Use |
|---|---|---|
| `pad-2026.10.5` | `models/` in this repository | The signed bundle that ships in both clients |
| `pad-2026.10.7.zip` | Submission pack, `test-data/model-bundles/` | A correctly signed bundle with a newer version number. It holds the same five models. Use it to test the offline model install and rollback |
| `pad-2026.10.7-tampered.zip` | Submission pack, `test-data/model-bundles/` | A copy with one byte changed in one model. The install must refuse it with an integrity error, and the current models must stay in place |

These bundles are signed with a **development key**. The matching public key is built into the code and must be replaced
before any real deployment.

## 8. Live attack props

To try the attacks yourself, use the portrait from section 2. Keep your own face out of view during an attack test: a
real face in the frame is not an attack, and the system may pass it.

| Attack | How to set it up |
|---|---|
| Printed photo | Print `portrait_frontal.png` in colour to fill an A4 page. Avoid shiny patches from lamps. Hold it so that the face fills about a quarter of the view or more |
| Bent print | The same print, curved by hand |
| Photo on a screen | Show the portrait full screen on a laptop or phone, and point the test camera at it |
| Video replay | Record a short video of a person who has agreed to this, then play it full screen on a tablet |
| Multiple faces | Show `crew_multi.png` on a screen |

A small face gives a "move closer" message and not a PAD decision: the minimum is 80 pixels wide and 4% of the picture.
This is why the size of the face in view matters.

## 9. Our evaluation dataset (not published)

Our evaluation used 178 recorded presentations (9,098 frames): 47 bona fide and 131 attacks. All went through one laptop
webcam at 640 x 480.

| Class | What it is | Takes |
|---|---|---|
| Bona fide | Four live people (s01 to s04), at normal, close and far distances, moving, and in dim light. s01 has 11 takes, the others 12 | 47 |
| `screen_photo` | A photo on a phone at normal distance | 6 |
| `screen_photo_close` | A photo on a phone held close | 12 |
| `screen_replay` | A selfie video on a phone | 6 |
| `screen_replay_monitor` | A selfie video on an 11-inch tablet | 12 |
| `print_flat` | A colour A4 print held flat | 6 |
| `print_curved` | The same print, bent | 6 |
| `screen_photo_multi` | A slideshow of photos of 14 consented people on a phone | 38 |
| `print_multi` | Printed cut-outs of 13 people | 45 |

**Why it is not published.** Face recordings are biometric data. Each person agreed to take part on the condition that
only aggregate numbers would be shared, so the recordings stay on the laptop that recorded them and are excluded from
git. The repository's `.gitignore` blocks them. All the results in our documents are aggregates, and people appear only
as codes such as s01.

**To build your own dataset:** the protocol in [part2-dataset-protocol.md](part2-dataset-protocol.md) gives the consent
text, the run sheet and the labels. `LiveAnalyzerDemo --mode dataset` records the takes, and `PadEvaluation` produces
the ISO/IEC 30107-3 report from them. Results are in [Test cases and results](test-cases-and-results.md).

## 10. The `test-data` folder in the submission pack

| Item | Contents |
|---|---|
| `images/` | `portrait_frontal.png`, `crew_multi.png` and the source notes |
| `clips/` | The five recorded clips from section 3 |
| `config/` | The two configuration files from section 6 |
| `model-bundles/` | The signed and tampered bundles from section 7, with their SHA-256 hashes |
| `traces/` | The 20 synthetic traces from section 5 |
| `README.txt` | A short guide to this folder |
