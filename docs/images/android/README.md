# Android liveness screenshots

The Android Registration Client's `LivenessPage` (Person 5, P5-3) in each state of
[`docs/ui-liveness-spec.md`](../../ui-liveness-spec.md) §3, drawn to the visual design of §9. Rendered on 2026-10-28
by the client's `test/liveness/liveness_screenshots_test.dart` from the **fake engine's events**, with the grey
**placeholder** instead of a camera picture, so no face or personal data can appear. 360×740 dp at 1.5× (540×1110 px),
the size of the earlier phone screenshots, with the app's Noto Sans (task 21); no system bars.

    LIVENESS_SCREENSHOTS=<dir> flutter test test/liveness/liveness_screenshots_test.dart

| File | State | Events |
|---|---|---|
| `01-checking.png` | CHECKING_PASSIVE | STARTED, CHECKING_PASSIVE |
| `02-challenge-blink.png` | CHALLENGE_PROMPT, blink (step 2 of 2) | CHALLENGE_PROMPT BLINK 2/2 |
| `03-challenge-smile.png` | CHALLENGE_PROMPT, smile | CHALLENGE_PROMPT SMILE 1/2 |
| `04-challenge-turn-left.png` | CHALLENGE_PROMPT, turn left | CHALLENGE_PROMPT TURN_LEFT 1/2 |
| `05-challenge-progress.png` | CHALLENGE_PROGRESS ("Please continue"), the step bar filling | TURN_LEFT 1/2, progress 0.7 |
| `06-action-detected.png` | ACTION_DETECTED | BLINK 1/2, ACTION_DETECTED |
| `07-hold-still.png` | HOLD_STILL | HOLD_STILL |
| `08-passed.png` | PASSED (the page closes after about 1 s) | PASSED |
| `09-attempt-failed-tips.png` | ATTEMPT_FAILED, LIVENESS_NOT_VERIFIED with the tips; one attempts line | CHALLENGE_TIMEOUT, 3 retries left |
| `10-attempt-failed-tips-buttons.png` | the same at a 1.3 text scale, the tips scrolled to their end; Retry and Cancel stay in place | as 09 |
| `11-pad-generic.png` | ATTEMPT_FAILED, PAD failure: the generic text, no tips | PAD_FAILURE, VERIFICATION_NOT_COMPLETED |
| `12-guidance-no-face.png` | GUIDANCE no face | CHECKING_PASSIVE, GUIDANCE NO_FACE |
| `13-guidance-multiple-faces.png` | GUIDANCE multiple faces | CHECKING_PASSIVE, GUIDANCE MULTIPLE_FACES |
| `14-guidance-improve-lighting.png` | GUIDANCE improve lighting | CHECKING_PASSIVE, GUIDANCE IMPROVE_LIGHTING |
| `15-device-error-recoverable.png` | DEVICE_ERROR, recoverable: Retry and Cancel | DEVICE_ERROR, recoverable |
| `16-device-failure-final.png` | FAILED_FINAL, device: device text, recovery text, Close | FAILED_FINAL DEVICE_PROBLEM |
| `17-max-attempts-resident.png` | FAILED_FINAL, max attempts, resident recovery | MAX_RETRIES_EXCEEDED, RESIDENT |
| `18-max-attempts-operator.png` | FAILED_FINAL, max attempts, operator recovery | MAX_RETRIES_EXCEEDED, OPERATOR |
| `19-max-attempts-supervisor.png` | FAILED_FINAL, max attempts, supervisor recovery | MAX_RETRIES_EXCEEDED, SUPERVISOR |
| `20-operator-cooldown-refusal.png` | cooldown refusal (an immediate FAILED_FINAL) | MAX_RETRIES_EXCEEDED on attempt 1, OPERATOR |

The oval colour always comes with the state badge, an icon. The debug-only model chip is off. The screenshots are in
English. The app has the liveness texts in all six of its languages (English, Arabic, French, Hindi, Kannada and
Tamil), added in fix round 4; the five non-English ones were machine-translated, then reviewed and corrected by
native speakers on 2026-10-28 (see [`docs/ui-liveness-spec.md`](../../ui-liveness-spec.md) Appendix A). On the phone itself the screen is the same:
the test loads the app's bundled fonts. In `15` and `16` the English device text takes 3 lines at 360 dp (spec §9, status card).
