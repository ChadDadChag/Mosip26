# Combined recording session: run sheet (F3 prints, F16 clips, F2 banding, many identities, F9 teammates, F5 second screen)

| Blocks | Time |
|---|---|
| 1 to 3 | about 20 minutes |
| 4 and 5, many identities | about 12 minutes, plus 3.5 minutes for the optional close-up slideshow pass |
| 6, each teammate | about 10 minutes |
| 7, second screen | about 3 minutes |
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

- **Confirm the grouping.** Claude has proposed which photos show the same person. Look at the contact sheets: one
  sheet per proposed person, every photo with its number and file name, in a scratch folder outside the repository.
  Then fix `test images/labels.csv` (`file,person_id`): give the same `person_id` to photos of the same person.
  - Give your own photos the code `s01`.
  - Leave `person_id` empty for anyone who should not be used.
- **Print the cut-outs.** Claude regenerates the life-size print sheet from the confirmed labels: 12 people, one per
  A4 page.
  - Print in colour at **100% / actual size** (not "fit to page"). The 10 cm bar on each page must measure 10 cm.
  - Trim each page along its thin line.
- **Phone album.** Put all the photos in one album on the phone. Set the gallery's slideshow to the same interval for
  every photo, 3 or 4 s, so that 42 photos fit in the 3.5-minute recording. Use no transition, or the simplest one,
  and no music.

## 0. Setup (5 min)

1. Laptop on mains power, normal room light, webcam uncovered, every other camera app closed.
2. On the desk:
   - the colour A4 prints of your selfie and of the NASA portrait, and the 12 trimmed cut-outs;
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
    - hold it FLAT at normal distance for about 5 s;
    - take it out of view for 1 s;
    - hold it again for 5 s;
    - take it out of view, and go on to the next cut-out.
    Keep your own face out of view. All 12 fit in the 4 minutes.
23. "CUT-OUTS CURVED": the same, with each cut-out CURVED around your hand.
24. Claude cuts both recordings into takes as in step 20.

## 6. F9 teammates (optional; 12 takes each, about 10 min per person)

25. Read the consent text (`docs/part2-dataset-protocol.md`, section 2) to the teammate. Go on only if they agree.
    Each one gets the next free code: s02, s03, and so on. Never their name.
26. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s02 -Only bona_fide`. The teammate sits in front of the
    laptop and follows the "YOUR FACE:" prompts:
    - normal ×3;
    - close ×2;
    - far ×2;
    - dim ×1: turn the room light off for this one, then back on;
    - moving ×2;
    - close_whole ×2: lean in until LIVE reads 25–40%.
    You press SPACE.

## 7. F5 second screen (optional; 4 takes, about 3 min)

27. Play your selfie video in a loop on the second screen. Place it at normal distance, with its edges visible.
28. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only screen_replay_monitor`. For each take, the
    prompt is "SECOND SCREEN playing your SELFIE VIDEO", with your own face out of view.

## Afterwards (Claude)

- `SlideshowSegmenter` cuts the slideshow and cut-out recordings into takes: `screen_photo_multi` and `print_multi`,
  one subject per photographed person.
- `PadEvaluation`: prints and the many-identity attacks are now evaluated.
- `BindingEvaluation`, with the new subjects and `--labelled "test images\labels.csv" --labels-confirmed`.
- The F16 commit.
- The banding result.
- Reports give aggregates only. All frames stay in `eval-data/`, except the reviewed F16 clips.
