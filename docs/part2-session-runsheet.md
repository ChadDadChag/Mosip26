# Combined recording session: run sheet (F3 prints, F16 clips, F2 banding, F9 teammates, F5 second screen)

About 20 minutes for blocks 1 to 3. Add about 10 minutes per teammate (block 4) and 3 minutes for a second screen
(block 5). Claude starts each block and does the checks; you hold things and press keys.

**The session window, every block.** The orange **NEXT** line says what to hold and where. The right-hand panel shows
**LIVE**: the face size as a share of the picture, and "whole face visible". **SPACE** starts the take: three beeps,
then a red **RECORDING** line and a progress bar. Wait for green **Saved**. **S** skips a take. Close the window to stop.
A stopped block can simply be started again: takes already saved are skipped.

**Screenshots.** None during the session, of the laptop or the phone. If one is ever needed:
- no picture-in-picture or floating window (Claude first checks that `adb shell dumpsys activity activities | grep -c mode=pinned` prints 0);
- the status bar is cropped;
- it is viewed, then deleted, and never committed;
- never the debug page after a real session.

## 0. Setup (5 min)

1. Laptop on mains power, normal room light, webcam uncovered, every other camera app closed.
2. On the desk:
   - the colour A4 prints of your selfie and of the NASA portrait;
   - the phone, with automatic brightness OFF, Do Not Disturb ON and the screen timeout at 2 minutes or more;
   - in the phone's gallery: your selfie photo, the NASA portrait and your selfie video.
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
9. Claude then works on the clips while you go on with block 3:
   - packs each one (under 2 MB);
   - counts the faces and looks at every frame;
   - plays it three times through `RecordedVideoE2ETest` in the Registration Client build.
   A clip is committed only if it never shows a person and FAILED in all three runs. If one PASSES, that is a
   finding, not a test. If one must be redone, Claude deletes it and you repeat step 7 (the other clips are kept).

## 3. F2 banding (3 takes at 30 fps, lossless, about 3 min)

10. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only f2_banding`.
11. Open the NASA portrait full screen on the phone and set the **brightness slider to about 30%**. Then:
    "BANDING: PHONE at 30% BRIGHTNESS, VERY CLOSE (~10 cm), NASA PORTRAIT, face size 20% or more". Keep your own
    face out of view.
12. Set the **brightness to 100%**: "BANDING: PHONE at 100% BRIGHTNESS ...", in the same way.
13. Put the phone away: "BANDING: YOUR FACE, lean in until face size reads 25-40%, WHOLE face in the picture".
14. Claude runs the banding analysis on the three takes.

## 4. F9 teammates (optional; 12 takes each, about 10 min per person)

15. Read the consent text (`docs/part2-dataset-protocol.md`, section 2) to the teammate. Go on only if they agree.
    Each one gets the next free code: s02, s03, and so on. Never their name.
16. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s02 -Only bona_fide`. The teammate sits in front of the
    laptop and follows the "YOUR FACE:" prompts:
    - normal ×3;
    - close ×2;
    - far ×2;
    - dim ×1: turn the room light off for this one, then back on;
    - moving ×2;
    - close_whole ×2: lean in until LIVE reads 25–40%.
    You press SPACE.

## 5. F5 second screen (optional; 4 takes, about 3 min)

17. Play your selfie video in a loop on the second screen. Place it at normal distance, with its edges visible.
18. Claude runs `scripts\dataset-session.ps1 -Consent -Subject s01 -Only screen_replay_monitor`. For each take, the
    prompt is "SECOND SCREEN playing your SELFIE VIDEO", with your own face out of view.

## Afterwards (Claude)

- `PadEvaluation`: prints are now evaluated.
- `BindingEvaluation`, with the new subjects.
- The F16 commit.
- The banding result.
- Reports give aggregates only. All frames stay in `eval-data/`, except the reviewed F16 clips.
