# Android liveness screenshots

The Android Registration Client's `LivenessPage` (Person 5, P5-3) in each state of
[`docs/ui-liveness-spec.md`](../../ui-liveness-spec.md) §3. Taken on a Samsung Galaxy S23 FE (Android 16) with the
**fake engine** and the debug page's **preview placeholder**: no camera picture is shown, so no face or personal data
appears. The system status and navigation bars are cropped; 540 px wide.

| File | State | Fake scenario / setup |
|---|---|---|
| `01-checking.png` | CHECKING_PASSIVE | PASS_ACTIVE |
| `02-challenge-blink.png` | CHALLENGE_PROMPT, blink (step 2 of 2) | PASS_ACTIVE |
| `03-challenge-smile.png` | CHALLENGE_PROMPT, smile | PASS_ACTIVE, challenge pool SMILE |
| `04-challenge-turn-left.png` | CHALLENGE_PROMPT, turn left | PASS_ACTIVE, challenge pool TURN_LEFT |
| `05-challenge-progress.png` | CHALLENGE_PROGRESS ("Please continue") | PASS_ACTIVE |
| `06-action-detected.png` | ACTION_DETECTED | PASS_ACTIVE |
| `07-hold-still.png` | HOLD_STILL | PASS_ACTIVE |
| `08-passed.png` | PASSED (the page closes after about 1 s) | PASS_ACTIVE |
| `09-attempt-failed-tips.png` | ATTEMPT_FAILED, LIVENESS_NOT_VERIFIED with the tips | FAIL_CHALLENGE_THEN_PASS_ON_RETRY |
| `10-attempt-failed-tips-buttons.png` | the same, scrolled to Retry / Cancel | FAIL_CHALLENGE_THEN_PASS_ON_RETRY |
| `11-pad-generic.png` | ATTEMPT_FAILED, PAD failure: the generic text, no tips | PAD_ATTACK |
| `12-guidance-no-face.png` | GUIDANCE no face | NO_FACE_THEN_PASS |
| `13-guidance-multiple-faces.png` | GUIDANCE multiple faces | MULTIPLE_FACES |
| `14-guidance-improve-lighting.png` | GUIDANCE improve lighting | POOR_LIGHTING_HINTS |
| `15-device-error-recoverable.png` | DEVICE_ERROR, recoverable: Retry and Cancel | DEVICE_DISCONNECT |
| `16-device-failure-final.png` | FAILED_FINAL, device: device text, recovery text, Close | DEVICE_FAILURE |
| `17-max-attempts-resident.png` | FAILED_FINAL, max attempts, resident recovery | MAX_RETRIES_EXCEEDED, RESIDENT |
| `18-max-attempts-operator.png` | FAILED_FINAL, max attempts, operator recovery | MAX_RETRIES_EXCEEDED, OPERATOR |
| `19-max-attempts-supervisor.png` | FAILED_FINAL, max attempts, supervisor recovery | MAX_RETRIES_EXCEEDED, SUPERVISOR |
| `20-operator-cooldown-refusal.png` | cooldown refusal (an immediate FAILED_FINAL) | OPERATOR again within the 60 s cooldown |

The oval colour always comes with an icon. The debug-only model chip is hidden in placeholder mode. English only:
the other languages fall back to English until their translations are added.
