# Combined recording session: run sheet (F3 prints, F16 clips, F2 banding, many identities, multiple faces, F9 teammates, F5 second screen)

| Blocks | Time |
|---|---|
| 1 to 3 | about 20 minutes |
| 4 and 5, many identities | about 12 minutes, plus 3.5 minutes for the optional close-up slideshow pass |
| 6, multiple faces | about 4 minutes |
| 7, each teammate | about 10 minutes |
| 8, second screen | about 3 minutes |
| Before the day | about 20 minutes of preparation |

Claude starts each block and does the checks; you hold things and press keys.

**The session window, every block.** The orange **NEXT** line says what to hold and where. The right-hand panel shows
**LIVE**: the face size as a share of the picture, and "whole face visible". **SPACE** starts the take: three beeps,
then a red **RECORDING** line and a progress bar. Wait for green **Saved**. **S** skips a take. Close the window to stop.
A stopped block can simply be started again: takes already saved are skipped.

**Screenshots.** None during the session, of the laptop or the phone. If one is ever needed:
- no picture-in-picture or floating window (Claude first checks that `adb shell dumpsys activity activities | grep -c mode=pinned` prints 0);
- the status bar is cropped;
- it is viewed, then deleted, and never committed;
- never the debug page after a real session.

**The photos of other people** (`test images/`, consented) never leave the laptop and the phone, and are never committed.
This applies to the photos, their recordings, the print sheets and the contact sheets.

## Before the day (about 20 min)

- **The grouping of the 42 photos.** Claude has proposed which photos show the same person. It can be confirmed
  after the session: the recordings do not change, only the evaluation is run again.
  - Check the contact sheets: one sheet per proposed person, every photo with its number and file name, in a scratch
    folder outside the repository.
  - Fix `test images/labels.csv` (`file,person_id`): photos of the same person get the same `person_id`, your own
    photos get `s01`, and anyone who should not be used gets an empty `person_id`.
- **Your print sheets.**
  - Your own 2-page sheet of 12 people: **cut into 12 single-person pieces**.
  - The NASA A4 and your selfie A4.
  - The **two group photos**, printed whole.
  - All prints are shredded after the session.
- **Print sources.** Copy the 12 source images of the sheet and the 2 group photos into `test images/print-sources/`
  (untracked, like the photos).
  - Claude checks them: faces per photo (the group photos report their face counts), and codes `q01`, `q02`, … for
    the 12 people by face. The group photos get no code.
  - Block 5 matches the cut-outs against these.
- **Phone album.** Put all 42 photos in one album on the phone. Set the gallery's slideshow to the same interval for
  every photo, 3 or 4 s, so that 42 photos fit in the 3.5-minute recording. Use no transition, or the simplest one,
  and no music.

## 0. Setup (5 min)

1. Laptop on mains power, normal room light, webcam uncovered, every other camera app closed.
2. On the desk:
   - the colour A4 prints of your selfie and of the NASA portrait, the 12 cut-outs and the 2 group photo prints;
   - the phone, with automatic brightness OFF, Do Not Disturb ON and the screen timeout at 5 minutes or more;
   - in the phone's gallery: your selfie photo, the NASA portrait, your selfie video and the slideshow album.
3. Sit about 50 cm from the laptop, as in round 1.

## 1. F3 prints, s01 (12 takes, about 7 min)

4. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only print_flat,print_curved`.
5. For each take, hold the print as the NEXT line says, at arm's length to the side, so that your own face is out of
   the picture:
   - takes 1 and 2: "PRINT held FLAT, normal distance: your SELFIE printed in colour";
   - take 3: "PRINT held FLAT, CLOSE: your SELFIE, face size 20% or more". Move the print until LIVE reads 20% or more;
   - takes 4 to 6: the same with "the NASA PORTRAIT";
   - takes 7 to 12: the same six with "PRINT CURVED around your hand".
6. Press SPACE and hold the print until Saved.

## 2. F16 regression clips (4 clips, about 3 min)

These clips are committed to the repository, so **nobody may be in them**: no face, no body, no reflection in the
phone, nobody behind you. Only the NASA picture, and your hand at most.

7. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only f16`.
8. Before each SPACE, check the preview. Only the NASA picture is visible, and LIVE shows one face, the NASA one.
   Tilt the phone so that the screen reflects no face and no lamp.
   1. "F16 CLIP: NASA PRINT held flat, normal distance (about 50 cm)"
   2. "F16 CLIP: NASA PRINT held flat, CLOSE: face size 20% or more"
   3. "F16 CLIP: PHONE showing the NASA PORTRAIT, normal distance, phone edges visible"
   4. "F16 CLIP: PHONE VERY CLOSE (~10 cm) showing the NASA PORTRAIT, face size 20% or more"
9. Claude then works on the clips:
   - packs each one (under 2 MB);
   - counts the faces and looks at every frame;
   - plays it three times through `RecordedVideoE2ETest` in the Registration Client build. This runs in the
     background while you go on with block 3.
   A clip is committed only if it never shows a person and FAILED in all three runs. If one PASSES, that is a
   finding, not a test. If one must be redone, Claude deletes it and you repeat step 7 (the other clips are kept).
10. **You look at every frame too.** About a minute after the last clip, Claude opens the contact sheets: every frame
    of every clip, numbered, from a scratch folder outside the repository. Each frame should show only the NASA
    picture and your hand. Say "all clear" or name a frame. The commit message records how many frames were checked,
    and that both you and Claude viewed all of them.

## 3. F2 banding (3 takes at 30 fps, lossless, about 3 min)

11. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only f2_banding`.
12. Open the NASA portrait full screen on the phone and set the **brightness slider to about 30%**. Then:
    "BANDING: PHONE at 30% BRIGHTNESS, VERY CLOSE (~10 cm), NASA PORTRAIT, face size 20% or more". Keep your own
    face out of view.
13. Set the **brightness to 100%**: "BANDING: PHONE at 100% BRIGHTNESS ...", in the same way.
14. Put the phone away: "BANDING: YOUR FACE, lean in until face size reads 25-40%, WHOLE face in the picture".
15. Claude runs the banding analysis on the three takes.

## 4. Many identities: phone slideshow (one 3.5-min recording, plus an optional close-up pass)

16. Phone brightness back to normal. Open the slideshow album at its **first photo**, ready to start.
17. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only slideshow`.
18. "SLIDESHOW: PHONE at normal distance (about 50 cm), the PHOTO SLIDESHOW playing from the first photo, phone edges
    visible; hold it until the slideshow ends; your own face OUT of view".
    - Start the slideshow.
    - Press SPACE right after it starts.
    - Hold the phone steady, in front of the camera, until Saved (3.5 min).
    - It does not matter if the slideshow ends early or loops.
19. Optional: "SLIDESHOW CLOSE: PHONE about 15 cm away, the slideshow again from the first photo, face size 20% or
    more". Restart the slideshow, then press SPACE. Press S to skip this pass.
20. Claude cuts each recording into one take per photo. Each take is matched to the photos by face, so nothing is
    labelled by hand, and transitions and blank frames are skipped. Claude reports how many photos and people were
    covered.

## 5. Many identities: printed cut-outs (two 4-min recordings)

21. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only print_multi`.
22. "CUT-OUTS FLAT". For each cut-out in turn:
    - hold it FLAT about 20 cm from the camera (LIVE face size about 10%) for about 5 s;
    - take it out of view for 1 s;
    - hold it again for 5 s;
    - take it out of view, and go on to the next cut-out.
    Keep your own face out of view. All 12 fit in the 4 minutes.
    - A full-body photo with a small face: hold it so that the face is as large as it can be in the picture.
    - A photo with sunglasses is recorded like any other.
23. "CUT-OUTS CURVED": the same, with each cut-out CURVED around your hand.
24. Claude cuts both recordings into takes as in step 20, matching against `test images/print-sources/`.
    - A cut-out that matches no source photo is not lost: it gets a new code (`u01`, `u02`, …), and its repeated
      presentations get the same code.
    - Your own live face is never turned into a take (`--exclude-subject` s01).
    - Very small faces (full-body photos) are expected to give FACE_TOO_SMALL non-responses. They are counted
      separately.

## 6. Multiple faces (problem statement section 9; 4 real liveness sessions, about 4 min)

These are real liveness checks, not recordings: no frame is kept, only the event log with its timings. Each session
retries by itself and ends with "run FINISHED"; expected every time: "multiple faces" on screen, then FAILED, never
passed.

25. Claude runs `scripts\session-series.ps1 -Plan m,n,o,o`. The NEXT line names the case. Press SPACE to start it,
    and hold until the run says FINISHED (about 15 s).
    - m: "MULTI-FACE: YOUR FACE plus ONE CUT-OUT beside it". Look at the camera and hold one cut-out next to your face,
      about 30 cm from the camera, so it looks at least half as tall as your face. A smaller face counts as
      background by design (under 20% of your face's area), and that would not test this case.
    - n: "MULTI-FACE: TWO CUT-OUTS side by side about 30 cm from the camera". Your own face out of view.
    - o, twice: "MULTI-FACE: a GROUP PHOTO print (several faces) about 30 cm from the camera". The first group photo,
      then the second. Your own face out of view. A face much smaller than the largest one in the photo counts as
      background, as in case m. In a photo with one dominant face, the others may be ignored, and the print is then
      judged like any other print.
26. Claude reports per case the result, the time to the first failed attempt, and the failure codes.

## 7. F9 teammates (optional; 12 takes each, about 10 min per person)

27. Read the consent text (`docs/part2-dataset-protocol.md`, section 2) to the teammate. Go on only if they agree.
    Each one gets the next free code: s02, s03, and so on. Never their name.
    Claude asks: is this teammate in `test images`? If yes, which pXX?
28. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s02 -Only bona_fide`. The teammate sits in front of the
    laptop and follows the "YOUR FACE:" prompts:
    - normal ×3;
    - close ×2;
    - far ×2;
    - dim ×1: turn the room light off for this one, then back on;
    - moving ×2;
    - close_whole ×2: lean in until LIVE reads 25–40%.
    You press SPACE.

## 8. F5 second screen (optional; 4 takes, about 3 min)

29. Play your selfie video in a loop on the second screen. Place it at normal distance, with its edges visible.
30. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only screen_replay_monitor`. For each take, the
    prompt is "SECOND SCREEN playing your SELFIE VIDEO", with your own face out of view.

## Afterwards (Claude)

- `SlideshowSegmenter` cuts the slideshow and cut-out recordings into takes: `screen_photo_multi` and `print_multi`,
  one subject per photographed person.
- **The F9 re-evaluation, one command:**

      scripts\f9-reeval.ps1 -Subjects s01,s02,s03 -SubjectMap s02=pXX,s03=pYY -LabelsConfirmed

  - The codes are the ones from step 27. Pass `-LabelsConfirmed` only if the labels were confirmed before the day.
  - It runs, in order, and stops at the first step that fails:
    - the inventory and the consent check (a subject without consent is refused);
    - `PadEvaluation` (the prints and the many-identity attacks are evaluated too);
    - `BindingEvaluation --identity` with the labelled photos (take normal#1 is each teammate's enrollment);
    - the session-guard replays at volatility 0.85 and 0.80.
  - It applies the decision rules R0–R5, which are fixed in the script before the data, and writes
    `eval-data\reports\f9-<time>\summary.md`: aggregates and codes only.
  - The F15 rule decides only with at least 3 people and 50 onboarding-like pairs; otherwise 0.40 stays. An F18
    change needs at least 3 subjects.
- **With the summary**, Claude reports its decision lines to the planner. The updates to
  `docs/part2-iso30107-evaluation.md` and `docs/part2-pad-architecture.md` are then a separate commit, after the
  planner's review. No rule is changed after the data is seen.
- The multi-face result per case, from the session log: outcome, time to the first failure and codes. Aggregates only.
- The F16 commit.
- The banding result.
- Reports give aggregates only. All frames stay in `eval-data/`, except the reviewed F16 clips.
