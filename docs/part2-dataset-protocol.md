# Part 2 PAD evaluation dataset: protocol

Owner: Person 2 (passive liveness and PAD). Tools: `liveness-eval` (`DatasetRecorder`, `LiveAnalyzerDemo --mode dataset`, `PadEvaluation`).

## 1. Purpose

This protocol records a small local dataset of bona fide and attack presentations. It is used to measure the passive PAD
(MiniFASNet V2 + V1SE, fused and windowed by Part 3's `PassiveEvaluator`) with ISO/IEC 30107-3:2023 metrics, to compare
candidate scorers, and to choose `passiveThreshold` and `padRejectThreshold`.

The first round has **one subject**. Its numbers show tendencies and failure modes, not field error rates, and every
report says so. More subjects and printed photos can be added later with the same tools and layout (section 8).

## 2. Consent

Read this text to the participant and record a take only after they agree:

> We will record short videos of your face, and of photos and videos of your face shown on a phone, to test how well
> the liveness check tells a real face from a photo or a screen.
>
> - The recordings are used **only for this evaluation**.
> - They are stored **only on this laptop**, in the folder `eval-data/`. This folder is excluded from the git
>   repository, so the recordings are **never committed or uploaded**.
> - You are identified only by a code such as `s01`, never by name.
> - Only aggregate numbers, such as error rates per attack type, are reported or shared.
> - You can ask at any time for your recordings to be deleted, and they will be deleted.
>
> Do you agree?

The tools refuse to record without `--consent`. Every `meta.json` stores `"consent": true`. Anything derived from the
recordings (per-take scores, per-frame features, reports) also stays under `eval-data/` unless the participant agrees
to share it. Aggregate-only reports may be committed only with their explicit agreement.

**Deleting a subject:** remove `eval-data/bona_fide/<subject>/`, every `eval-data/attack/*/<subject>/`, and any
report folder under `eval-data/reports/` that was computed from them.

## 3. Subjects

- Pseudonymous codes `s01`, `s02`, ... (`DatasetRecorder` accepts only `^s\d{2,3}$`). The key from code to person,
  if one is kept at all, is never written in the repository or under `eval-data/`.
- Photos shown in attack takes are either the subject's own selfie or a **public-domain** portrait: the NASA portrait
  of L. Gordon Cooper, which is the analyzer's golden fixture (`liveness-analyzer/src/test/resources/golden/portrait_frontal.png`,
  source https://commons.wikimedia.org/wiki/File:AMERICAN_AND_SOVIET_ASTRONAUTS_-_COOPER_-_MERCURY_9_-_GEMINI_5_-_NARA_-_17447491.jpg).
  Never use a photo of anyone who has not consented.

## 4. Capture device

The laptop's built-in webcam, through `WebcamFrameSource` (sarxos webcam-capture), at 640x480. `meta.json` records the
`FrameSource.DeviceInfo` of each take: `deviceId`, `vendor`, `model`, `certificationLevel`, `maxWidth`, `maxHeight`,
`maxFps`. Round 1 used `webcam:0` ("HP True Vision FHD Camera 0", certification level `NONE`).

## 5. Layout

```
eval-data/                                   (gitignored)
  bona_fide/<subject>/<take>/                meta.json, frame_00000.jpg ... frame_00059.jpg
  attack/<species>/<subject>/<take>/         same
  reports/                                   PadEvaluation output (pad-eval-<yyyyMMdd>/)
  traces/                                    Part 3 TraceRecorder traces (features only)
  f16-clips/<name>/                          regression clips before review (NASA portrait only, section 8)
  f2-banding/<subject>/<condition>/          lossless 30 fps banding takes (section 8)
  streams/<subject>/<condition>/             continuous recordings of many people's photos, before segmentation
  photo-set/                                 per-photo metrics of a labelled photo folder (section 8)
```

`PadEvaluation` reads only `bona_fide/` and `attack/`.

The species is taken from the directory and never guessed from the content. `meta.json` holds `label`, `kind`
(`bona_fide` or `attack`), `subject`, `take`, `condition`, `fps`, `frames`, `durationMs`, `format`, `jpegQuality`,
`chroma`, `width`, `height`, `device`, `recordedUtc` and `consent`.

## 6. Take specification

- **6 s at 10 fps = 60 frames.** Frames are paced by their timestamps, and a faster camera's extra frames are dropped.
- **JPEG quality 95 with 4:4:4 chroma** (`--format jpg`, the default). Chroma subsampling would blur the screen and
  print textures that PAD relies on. `--format png` gives lossless frames.
- The camera stays open between takes (`KeptOpenSource`), so no take loses its first second to a cold start.
- Each take starts after a 3-second countdown with a beep each second.
- The preview is mirrored like a selfie. Recorded frames are never mirrored.
- Lighting is the normal room light unless the condition says otherwise. The subject sits about 50 cm from the screen.

## 7. Presentation attack instrument (PAI) species and takes per subject

| Species | How it is made | Takes |
|---|---|---|
| `bona_fide` | The subject's live face. Conditions: `normal` x3, `close` x2 (the face fills a large part of the frame), `far` x2 (about 50 cm further back), `dim` x1 (room light off, screen light only), `moving` x2 (slow head movement), `close_whole` x2 (lean in until the live readout shows a face size of 25-40%, with the whole face in the picture) | 12 |
| `screen_photo` | A photo on a phone at normal distance, with the phone's edges visible, hand-held. Conditions: `selfie` x3 (a selfie of the subject taken beforehand), `nasa` x3 (the public-domain portrait) | 6 |
| `screen_photo_close` | The case missed in Phase 5: the phone photo held close, so the face fills much of the frame. `selfie` x3 and `nasa` x3 (phone held close, as the subject judges it), then `selfie_fill` x3 and `nasa_fill` x3 (phone about 10 cm from the camera, held until the live readout shows a face size of 20% or more) | 12 |
| `screen_replay` | A selfie video of the subject playing on the phone, hand-held. `selfie_video` x6 | 6 |
| `screen_replay_monitor` | Optional: the same video on a second screen (another laptop or a tablet). `selfie_video` x4 | 4 |
| `print_flat`, `print_curved` | A printed photo held flat, and held curved around a finger or a cylinder, hand-held. Per picture: `selfie` x2 and `nasa` x2 at normal distance, `selfie_close` x1 and `nasa_close` x1 close (face size 20% or more). **Later**: the tools support them and the report marks them "not yet evaluated" until they are recorded | 6 + 6 |
| `screen_photo_multi` | Many consented people's photos as a phone slideshow at normal distance (`slideshow`), optionally again close (`slideshow_close`). One continuous recording, cut by `SlideshowSegmenter` into one take per photo shown. The subject is the photographed person's code | about 1 per photo |
| `print_multi` | Printed single-person cut-outs (from a print sheet of consented people, each face a few cm), each held about 20 cm from the camera `flat` twice, then `curved` twice. Segmented the same way, against the sheet's source photos, with unknown people kept as `u01`, … | about 4 per person |

**Before the session**, the subject uses their phone to take one selfie photo and one 10-second selfie video, both in
normal light, and opens the NASA portrait on the phone. In every attack take, the subject's own face is out of the
camera's view.

**Face-size readout.** The session window shows, live, the detected face box as a share of the picture and whether
the whole face is visible. It comes from the analyzer running on preview frames, in memory only. Round 1 showed why it
is needed. Without it, the first `close` takes were too close (the face was cut off, so the takes were non-responses)
and the first phone `close` takes were not close enough (the face covered about 4% of the picture; a face at normal
sitting distance covers about 9%).

**Running a session** (about 8 minutes; takes already on disk are skipped, so an interrupted session can be rerun):

```
LiveAnalyzerDemo --mode dataset --consent --subject s01 [--only species-or-condition,...] [--root eval-data]
```

For example, `--only close_whole,selfie_fill,nasa_fill` records only those conditions.

To record a single take:

```
DatasetRecorder --consent --label screen_photo_close --subject s01 --take 7 --condition selfie [--format jpg]
```

## 8. Adding subjects and prints later

- **New subject:** read the consent text again and pick the next free code (`s02`, ...).
  - Record bona fide takes only: `LiveAnalyzerDemo --mode dataset --consent --subject s02 --only bona_fide`, which is
    12 takes (10, plus 2 `close_whole`).
  - Attacks with the new subject's own photo are optional; they need that person's selfie and consent for it.
  - `PadEvaluation` and `BindingEvaluation` pick up every subject under `eval-data/` and report how many there are.
  - `BindingEvaluation` compares a subject as the same person only with themselves; pairs across subjects are
    impostors.
- **Prints:** print the selfie and the NASA portrait in colour on plain A4 paper (or photo paper; record which in a
  note kept off the repository).
  - The guided session has 6 hand-held takes per species. For each picture, 2 are at normal distance (`selfie`,
    `nasa`) and 1 is close, with a face size of 20% or more on the live readout (`selfie_close`, `nasa_close`):
    `LiveAnalyzerDemo --mode dataset --consent --subject s01 --only print_flat,print_curved`.
  - Single takes also work: `DatasetRecorder --consent --label print_flat --subject s01 --take 1 --condition selfie`.
- Rerun `PadEvaluation`. Species without takes stay marked "not yet evaluated". The exact commands are in
  `docs/part2-iso30107-evaluation.md`, section 7.
- **Regression clips (F16), `--only f16`.** Four 5-second clips at 15 fps of the public-domain NASA portrait only:
  `nasa-print-normal`, `nasa-print-close`, `nasa-phone-normal` and `nasa-phone-close-fill`.
  - **No person may be in view:** no face, no body, no face reflected in the phone, nobody in the background. A hand
    holding the print or phone is the only exception. These clips are the only recordings that get committed.
  - Before committing, each clip goes through three steps:
    1. `ClipPacker` turns it into MJPEG under 2 MB.
    2. `models/tools/clip_privacy_check.py` counts the faces in every frame and writes contact sheets outside the
       repository. A person (the participant) and Claude look at every frame. The commit message records the
       number of frames checked and who viewed them.
    3. `scripts/probe-regression-clips.ps1` plays it through `RecordedVideoE2ETest` three times.
  - Only a clip that FAILED in all three runs is committed, to `registration-services/src/test/resources/liveness/videos/`
    as `<name>__expect-FAILED-<CODE>.mjpeg`, or as `<name>__expect-FAILED.mjpeg` if the code varies. A clip that
    PASSES is a finding to report, not a test.
- **Banding takes (F2), `--only f2_banding`.** Three 3-second takes at 30 fps in lossless PNG:
  - the phone close (filling the view) with the NASA portrait, at 30% brightness (PWM dimming on, `nasa_fill_pwm30`)
    and at 100% (`nasa_fill_100`), with automatic brightness off;
  - the subject's own face, `close_whole_30fps`.
  - `meta.json` counts the frames the camera repeated (`duplicates`). The banding tool's `--takes` mode reports
    their spectra (`docs/part2-f2-screen-cue-design.md`, section 6.2).
- **Starting a block:** `scripts\dataset-session.ps1 -Consent -Subject s01 -Only <block>` runs the session with this
  repository's build. Without `-Consent` it refuses.
- **Camera backend:** both launchers take `-Capture opencv|sarxos`. The default is OpenCV; if OpenCV cannot open the
  camera, the tools fall back to sarxos and say so.
  - F13: sarxos ended long series with a native exit (code 116).
  - `CaptureSoak` (capture plus the analyzer, frames never written) traced it to the camera being stopped and
    started for every session.
  - With a restart every 10 s, sarxos died with a native heap corruption (0xC0000374) after about 25 restarts.
    OpenCV ran 89 restarts without an error.
  - Continuous capture for 30 min ran clean on both backends: sarxos at 14.9 fps, OpenCV at 14.8 fps.
- **Many identities: a labelled photo folder.** Photos of people the participant knows, used with their consent,
  kept in an untracked folder that is also listed in `.git/info/exclude`. Nothing from it is ever committed: not
  the photos, not the recordings, not the print sheets, not the contact sheets, not `labels.csv`.
  1. `models/tools/photo_set_analysis.py` checks every photo with the reference pipeline: faces, landmarks, pose,
     EAR, quality and inter-eye distance. It writes `eval-data/photo-set/photos.csv` and proposes the people by SFace
     clustering: complete linkage, cosine 0.55. It writes `labels.csv` (`file,person_id,faces`) into the photo
     folder, and one contact sheet per proposed person outside the repository.
  2. The owner of the photos checks the contact sheets and fixes `labels.csv`. A recorded subject's own photos get
     that subject's code (`s01`).
  3. `BindingEvaluation --labelled <folder>/labels.csv [--labels-confirmed]` adds:
     - genuine pairs within a person;
     - impostor pairs across people, against the recorded subjects and against the NASA portraits.
     Until the labels are confirmed, the numbers are provisional, and the genuine pairs are only those the
     clustering grouped (all at 0.55 or more).
  4. **Print sheet.** `models/tools/print_sheet.py` can make the sheet outside the repository. It takes one photo per
     person (the most frontal, sharpest and largest) and lays it out life size (face box 15 cm, one per A4 page) or
     6 per page.
     - A participant may instead print their own sheet. Its source images then go into a subfolder of the photo
       folder (`print-sources/`).
     - Running `photo_set_analysis.py --prefix q --skip-groups` on that subfolder gives its people their own codes
       (`q01`, …). Group photos are analysed and their face counts reported, but they get no code.
     - Full-page life-size prints are already covered by the selfie and NASA prints.
  5. **Recording and segmentation.** `--only slideshow` and `--only print_multi` record the phone slideshow and the
     cut-outs as continuous recordings. `SlideshowSegmenter` cuts them into `screen_photo_multi` and `print_multi`
     takes:
     - it matches each frame's largest face to the photos (cosine 0.45 or more, ahead of every other person by
       0.05);
     - it keeps runs of 10 frames or more after trimming 2 at each end;
     - it skips transitions and blank frames.
     For the cut-outs, `--unknown-ids u --exclude-subject eval-data/bona_fide/s01` keeps a cut-out without a source
     photo as an unknown person (`u01`, …) instead of skipping it, and never turns the presenter's own live face
     into a take.
  6. **Multiple faces** (problem statement section 9). `scripts\session-series.ps1 -Plan m,n,o,o` runs real-engine
     sessions:
     - m: the live face with one cut-out beside it, held closer to the camera (a second face counts only from 20%
       of the largest face's area);
     - n: two cut-outs side by side;
     - o: a printed group photo, once per group photo.
     n and o are expected to fail every attempt with MULTIPLE_FACES (about 1.5 s of several faces), and the session
     to end FAILED with MAX_RETRIES_EXCEEDED, never PASSED. m passes on the live face if the cut-out's face stays
     under 20% of the live face's area, which is by design (`docs/part2-pad-architecture.md`, section 13). Only the
     event log is kept (`liveness-eval/target/`, or `-LogDir eval-data\sessions`), with no frames.
  7. **Single-print retest and the combined retest (F18).**
     `scripts\session-series.ps1 -Plan <cases> -LogDir eval-data\sessions` runs real-engine sessions with one
     printed cut-out at a time (numbered 1 to 6 on the back).
     - **Attack cases** (the presenter's face out of view):
       - y = still, CLOSE: the face in the cut-out as big on screen as a real face, about a quarter of the window's
         width;
       - v = the same size, with slight natural hand motion;
       - w = the same size, forced challenges from TURN_LEFT and TURN_RIGHT: the cut-out is rotated to fake a turn;
       - x = the same size, forced challenges from BLINK and SMILE: the cut-out is tilted to fake them;
       - u = the NASA A4 print.
     - **Real-face cases** (prints face down): a = normal, i = close (face about a third of the picture),
       j = far (about 1 m), k = approaching during the check, l = moving.
     - **Distances do not work for cut-outs.** s and t (still at about 20 and 30 cm) gave faces of 25–60 px, below
       the 80 px minimum, so the sessions timed out without judging the print. Use y, v, w and x. At about 1 m a
       real face is also below 80 px.
     - **The log** keeps every analysed frame's numbers, never the frames: pose, face size, the score, both models'
       live probabilities, moiré and the cap state, the volatility cap and PAD latch flags, and quality issues.
     - **Replay.** `models/tools/session_guard_replay.py <log>` replays a log through the print-guard spec, and
       Part 3's first passive window per attempt, with and without each guard.
     - **Plan length.** Keep plans to about 15 runs. Two longer series windows ended early (exit 116, F13).
- **Known limits of the recording tools** (task 30; documented, not changed):
  - **z and zf: wait for the camera before SPACE.** The positioning readout's camera is stopped when the run starts,
    and its grab thread gets 2 s to finish. SPACE pressed while the camera is still opening (0.8–2.5 s) can let the
    old thread close the camera the session has just opened. The session then reports a device error. Retry, or
    start again.
  - **The scripts rebuild only when the pom changes.** `dataset-session.ps1` and `session-series.ps1` compile
    `liveness-eval` when `target/classes` or its classpath file is missing, or `pom.xml` is newer than that file,
    not when a Java source changes. After pulling or editing code, run `mvn -f liveness-eval\pom.xml compile`
    first, or the old tools run.
  - **A take that fails while recording keeps its number.** Its folder stays, without `meta.json`.
    - `PadEvaluation` and `BindingEvaluation` skip it, as they read only takes with `meta.json`.
    - A rerun of the session skips that number too, as it skips any folder on disk.
    - To record it again, delete that folder first. It holds only the failed take's frames.
  - **The subject map's codes must be in the data.** `f9_reeval.py inventory` (step a of `scripts\f9-reeval.ps1`)
    and `finetune_minifasnet.py` refuse an `sNN=pNN` whose codes are not in the data.
    - sNN must be a listed subject.
    - pNN must have attack takes or labelled photos.
    - Unchecked, a typo such as `s02=p50` would turn that person's genuine pairs into impostors without a word.

## 9. Evaluation

```
PadEvaluation [--data eval-data] [--out eval-data/reports/pad-eval-<yyyyMMdd>] [--no-heuristic]
```

- Every take gets a fresh analyzer, with frames in order and timestamps 1000/fps apart.
- **Presentation score:** the median of the first full `PassiveEvaluator` window (default RESIDENT policy). A take
  without a full window is a non-response.
- **Metrics (ISO/IEC 30107-3:2023):**
  - APCER per species, the maximum APCER over species, and BPCER, over a grid of thresholds.
  - ACER, which is non-normative in the 2023 edition and shown only for continuity.
  - EER, and BPCER at APCER <= 5% and <= 1%.
  - APNRR and BPNRR (non-response rates).
  - The three engine zones at 0.85 / 0.30: passive pass, challenge, PAD reject.
  - One-sided 95% Clopper-Pearson upper bounds for 0/N results.
- **Outputs:**
  - `report.md` contains aggregates only.
  - `scores.csv` (per take), `frames.csv` (per frame: features only, no pixels or landmarks) and `scatter.csv` stay
    local under `eval-data/`.

**The F9 re-evaluation (several subjects).** `scripts\f9-reeval.ps1 -Subjects s01,s02,... [-SubjectMap "s02=pXX,..."]`
runs the steps in order and writes `eval-data/reports/f9-<yyyyMMdd-HHmmss>/summary.md` (an `-Out` folder must be
empty). The steps:
- the inventory and the consent check;
- this evaluation;
- `BindingEvaluation --identity`;
- `models/tools/session_guard_replay.py` over the recorded takes (from `frames.csv`) and the live logs, at volatility
  0.85 and 0.80.

The summary applies decision rules R0–R5, which were fixed on 2026-10-28 before the teammates' data. They are in
`models/tools/f9_reeval.py` and in the script's header; `f9_reeval.py --self-test` checks the rules on synthetic
inputs and that the two copies are the same.
