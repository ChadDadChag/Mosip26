# Design mockups and wireframes: face liveness for the MOSIP Registration Clients

This document shows what a person sees during a face liveness check in the Desktop and Android Registration Clients of
MOSIP (Modular Open Source Identity Platform). It goes from user flows, to low-fidelity wireframes, to the real screens.
It ends by mapping the screens to the problem statement. You do not need any other document to read it.

## Terms

| Term | Meaning |
|---|---|
| Face liveness | A check that the face in front of the camera belongs to a live person who is present. |
| PAD | Presentation Attack Detection: finding a printed photo, a screen or a replayed video held up to the camera. |
| Passive check | A check that asks the person to do nothing. |
| Active challenge | A prompted action, such as "Please blink", used when the passive check is not sure. |
| SBI | Secure Biometric Interface: MOSIP's standard for talking to biometric devices. |
| Resident, operator, supervisor | The person being registered, the staff member running the client, and the staff member who approves. |

## The screen at a glance

One screen serves all three workflows, on both clients. Only the title and the recovery text change. From top to
bottom it has: a title, a camera card with a live preview and an oval guide, a status line, a hint line, a progress row,
tips (on failure only) and buttons pinned to the bottom. The camera card is 4:3 on Desktop and 3:4 on Android.

| State | Status line | Hint line | Oval and icon on the real screen | Progress row | Buttons |
|---|---|---|---|---|---|
| Starting | Starting face liveness check… | empty | grey, camera | Attempt 1 of 4 | Cancel |
| Checking | Checking face liveness… | empty | blue, scanning | Attempt 1 of 4 | Cancel |
| Guidance | Unchanged | Such as: Please move closer. | blue, warning by the hint | Attempt 1 of 4 | Cancel |
| Challenge prompt | Please blink | empty | amber, eye | Step 1 of 2, bar, Attempt 1 of 4 | Cancel |
| Challenge progress | The prompt stays | Please continue | amber, arrow | The bar fills | Cancel |
| Action detected | Action detected | empty | green, check | The step is full | Cancel |
| Hold still | Hold still | Please look at the camera. (after a turn) | blue, scanning | Attempt 1 of 4 | Cancel |
| Passed | Good, face captured successfully | empty | green, check | empty | None; goes on after about 1 second |
| Attempt failed | We could not verify face liveness. Please try again. | empty; tips below | red, cross | 3 attempts left | Retry, Cancel |
| PAD failure | Face verification could not be completed. Please try again. | empty | red, cross | 3 attempts left | Retry, Cancel |
| Maximum attempts | Face verification could not be completed. | The workflow's recovery text | red, cross | empty | Close |
| Device error | Camera/device not available. Check the connection and try again. | empty | red, camera off | Attempt 1 of 4 | Retry, Cancel |

## 1. User flows

These flows show what the person sees and does. They are not code flows. Blue boxes hold the exact screen texts.

### Flow 1: resident face capture

![User flow for resident face capture, from Scan to the stored face, with challenge and failure branches](images/wireframes/flow-1-resident-capture.svg)

The operator presses Scan, and the resident only looks at the camera. If the check is sure, the screen passes with no
action from anyone. If it is not sure, the system picks a challenge. After a pass, the existing capture takes the photo,
and the system checks that it shows the same person. If it does not, nothing is stored, and the next Scan starts again.

### Flow 2: operator and supervisor authentication

![User flows for operator and supervisor authentication, with failure and identity branches](images/wireframes/flow-2-operator-supervisor.svg)

Operators and supervisors see the same screen with their own title. Neither of them chooses a test. The screen appears
after the password, or for a Desktop face login, before the face is captured. A second question then checks that this
person is the user. On the operator's side, a failed check leads back to the login page, and new checks end at once for
60 seconds. On the supervisor's side, nothing is approved.

### Flow 3: retry and maximum attempts

![User flow of four attempts with attempts left, then a recovery screen for each workflow](images/wireframes/flow-3-retry-max-attempts.svg)

Each failed attempt says how many are left, and Retry appears only when a retry exists. After the fourth attempt, the
screen says the verification could not be completed and gives the recovery path for that workflow. Only Close remains.

### Flow 4: device error

![User flow of a device error, with automatic recovery, Retry, the fourth-error limit and the Android camera permission path](images/wireframes/flow-4-device-error.svg)

A device problem never costs an attempt, and the screen stays open so the person can reconnect the device. The same
attempt number continues afterwards. The fourth device problem in a session ends it. On Android, a refused camera
permission shows the same device text, and "Open settings" appears when Android will no longer ask.

## 2. Wireframes

These are low-fidelity, greyscale wireframes, one set per screen state. Each set shows Desktop on the left and Android
on the right.

- **Desktop** is a modal window over the landscape client, so the client stays visible behind it.
- **Android** is a full, portrait screen.
- **Blue numbered pins** are labels, not part of the design. The key is under each wireframe.
- **Colour is left out on purpose.** The note at the top right of each set gives the real colour and icon.
- **The wireframes are not to scale.** A line can wrap differently from the real screen.
- **All English texts are read from the client's `messages_en.properties`,** so they are the shipped texts.

### Wireframe 1: checking

![Wireframe of the checking state on Desktop and Android](images/wireframes/wf-01-checking.svg)

The status line shows that liveness is being verified, and the person does nothing. "Attempt 1 of 4" shows in the
progress row while the session runs.

### Wireframe 2: positioning guidance

![Wireframe of positioning guidance on Desktop and Android](images/wireframes/wf-02-guidance.svg)

The hint line tells the person what to fix, with a warning icon beside it. The other hints are "No face detected. Please
look at the camera.", "Only one person should be in view.", "Please move back.", "Please center your face in the
oval.", "Please improve the lighting.", "Please hold steady." and "Please look at the camera."

### Wireframe 3: active challenge prompt

![Wireframe of a challenge prompt on Desktop and Android](images/wireframes/wf-03-challenge-prompt.svg)

The prompt takes the status line. The progress row shows the step, a bar with one segment per challenge, and the attempt.

### Wireframe 4: challenge progress

![Wireframe of challenge progress on Desktop and Android](images/wireframes/wf-04-challenge-progress.svg)

The prompt stays while "Please continue" appears on the hint line and the current segment fills.

### Wireframe 5: action detected

![Wireframe of the action detected state on Desktop and Android](images/wireframes/wf-05-action-detected.svg)

The status changes as soon as the action is seen, so the person knows to stop.

### Wireframe 6: hold still

![Wireframe of the hold still state on Desktop and Android](images/wireframes/wf-06-hold-still.svg)

This state appears only after challenges. A pass with no challenge goes straight to wireframe 7.

### Wireframe 7: passed

![Wireframe of the passed state on Desktop and Android](images/wireframes/wf-07-passed.svg)

There are no buttons. The flow goes on by itself after about 1 second.

### Wireframe 8: attempt failed, with tips and Retry

![Wireframe of a failed attempt with tips, attempts left and Retry on Desktop and Android](images/wireframes/wf-08-attempt-failed-tips.svg)

The tips give clear actions, and "3 attempts left" says that a retry exists. On Android the camera card shrinks to make
room, and only the tips may scroll, never the buttons.

### Wireframe 9: PAD generic failure

![Wireframe of the generic failure after a PAD failure on Desktop and Android](images/wireframes/wf-09-pad-generic-failure.svg)

The text is generic and there are no tips, so nothing tells an attacker what to change. Other failures that are not the
person's fault show the same text.

### Wireframe 10: maximum attempts, resident

![Wireframe of the resident maximum attempts screen on Desktop and Android](images/wireframes/wf-10-max-attempts-resident.svg)

The text no longer says "Please try again", because this screen cannot try again. The hint line points to the supervisor
and the biometric exception process.

### Wireframe 11: maximum attempts, operator

![Wireframe of the operator maximum attempts screen on Desktop and Android](images/wireframes/wf-11-max-attempts-operator.svg)

During the 60-second cooldown a new check ends at once on this same screen, without opening the camera.

### Wireframe 12: maximum attempts, supervisor

![Wireframe of the supervisor maximum attempts screen on Desktop and Android](images/wireframes/wf-12-max-attempts-supervisor.svg)

The same screen with the supervisor's title and recovery text.

### Wireframe 13: device error

![Wireframe of a recoverable device error on Desktop and Android](images/wireframes/wf-13-device-error.svg)

Retry opens the device again. "Attempt 1 of 4" does not change. Desktop has no picture while the device is away. The
Android wireframe follows its screenshot, which shows the grey placeholder.

### Wireframe 14: device failure, final

![Wireframe of a final device failure on Desktop and Android](images/wireframes/wf-14-device-failure-final.svg)

The screen keeps the device text and adds the recovery text. There is one small difference: Desktop shows the camera-off
icon here, and Android shows the cross. On Android, "Open settings" joins Close when the camera permission is refused
for good.

### Wireframe 15: operator variant

![Wireframe of an operator challenge on Desktop and Android](images/wireframes/wf-15-operator-variant.svg)

Only the title changes, and there is one step by default.

### Wireframe 16: supervisor variant

![Wireframe of a supervisor challenge on Desktop and Android](images/wireframes/wf-16-supervisor-variant.svg)

Only the title changes, and there is one step by default.

## 3. Right-to-left layout

![Wireframe of the Arabic layout: a mirrored failed attempt on Desktop and a mirrored head-turn challenge on Android](images/wireframes/wf-17-arabic-right-to-left.svg)

Arabic mirrors the screen on both clients: the title, the status group, the progress row, the tips and the button
order. The camera card and the state icons do not mirror, because they show the person's physical left and right. So the
arrow for "turn your head to the left" still points left. The Desktop set shows the buttons and tips mirrored, and the
Android set shows the mirrored progress row with the unmirrored arrow. The generator read every Arabic string from the
client's `messages_ar.properties`. None was retyped. The Arabic is machine-translated, then reviewed and corrected by a
native speaker.

## 4. High-fidelity mockups: the real screens

These are the real screens. Select an image to open it at full size.

**How they were made.**
- The Desktop images come from the production screen classes, rendered at the window's size of 720 × 820. The camera
  picture is a public-domain portrait from NASA (National Aeronautics and Space Administration), not a person from this
  project.
- The Android images come from a widget test of the production page, driven by the scripted engine's events, with a grey
  placeholder instead of a camera picture. They are 540 × 1110 pixels.
- Both clients use the same design tokens. The title bar keeps each client's own colour.

### Passive check

| State | Desktop | Android |
|---|---|---|
| **Checking.** The person does nothing. | <a href="images/part4/01-checking.png"><img src="images/part4/01-checking.png" width="220" alt="Desktop: Checking face liveness"></a> | <a href="images/android/01-checking.png"><img src="images/android/01-checking.png" width="170" alt="Android: Checking face liveness"></a> |
| **Guidance.** Desktop: move closer. Android: no face. | <a href="images/part4/02-guidance.png"><img src="images/part4/02-guidance.png" width="220" alt="Desktop: guidance, Please move closer"></a> | <a href="images/android/12-guidance-no-face.png"><img src="images/android/12-guidance-no-face.png" width="170" alt="Android: guidance, no face detected"></a> |
| **Guidance.** Android: several people, poor lighting. | | <a href="images/android/13-guidance-multiple-faces.png"><img src="images/android/13-guidance-multiple-faces.png" width="170" alt="Android: guidance, only one person should be in view"></a> <a href="images/android/14-guidance-improve-lighting.png"><img src="images/android/14-guidance-improve-lighting.png" width="170" alt="Android: guidance, please improve the lighting"></a> |

### Active challenge

| State | Desktop | Android |
|---|---|---|
| **Prompt.** Blink, step 1 on Desktop, step 2 on Android. | <a href="images/part4/03-challenge-blink.png"><img src="images/part4/03-challenge-blink.png" width="220" alt="Desktop: Please blink"></a> | <a href="images/android/02-challenge-blink.png"><img src="images/android/02-challenge-blink.png" width="170" alt="Android: Please blink"></a> |
| **Prompt.** Smile and turn left. | | <a href="images/android/03-challenge-smile.png"><img src="images/android/03-challenge-smile.png" width="170" alt="Android: Please smile"></a> <a href="images/android/04-challenge-turn-left.png"><img src="images/android/04-challenge-turn-left.png" width="170" alt="Android: Please turn your head to the left"></a> |
| **Progress.** "Please continue", and the bar fills. | <a href="images/part4/04-challenge-progress.png"><img src="images/part4/04-challenge-progress.png" width="220" alt="Desktop: challenge progress"></a> | <a href="images/android/05-challenge-progress.png"><img src="images/android/05-challenge-progress.png" width="170" alt="Android: challenge progress"></a> |
| **Action detected.** | <a href="images/part4/05-action-detected.png"><img src="images/part4/05-action-detected.png" width="220" alt="Desktop: Action detected"></a> | <a href="images/android/06-action-detected.png"><img src="images/android/06-action-detected.png" width="170" alt="Android: Action detected"></a> |
| **Hold still.** Desktop also shows the "look at the camera" hint that follows a turn. | <a href="images/part4/06-hold-still-look-at-camera.png"><img src="images/part4/06-hold-still-look-at-camera.png" width="220" alt="Desktop: Hold still"></a> | <a href="images/android/07-hold-still.png"><img src="images/android/07-hold-still.png" width="170" alt="Android: Hold still"></a> |

### Passed

| State | Desktop | Android |
|---|---|---|
| **Passed.** Goes on by itself after about 1 second. | <a href="images/part4/07-passed.png"><img src="images/part4/07-passed.png" width="220" alt="Desktop: Good, face captured successfully"></a> | <a href="images/android/08-passed.png"><img src="images/android/08-passed.png" width="170" alt="Android: Good, face captured successfully"></a> |

### Failure and retry

| State | Desktop | Android |
|---|---|---|
| **Attempt failed.** Tips, attempts left, Retry. | <a href="images/part4/08-attempt-failed-retry.png"><img src="images/part4/08-attempt-failed-retry.png" width="220" alt="Desktop: attempt failed with tips"></a> | <a href="images/android/09-attempt-failed-tips.png"><img src="images/android/09-attempt-failed-tips.png" width="170" alt="Android: attempt failed with tips"></a> |
| **Large text.** At a 1.3 text scale the tips scroll, and Retry and Cancel stay in place. | | <a href="images/android/10-attempt-failed-tips-buttons.png"><img src="images/android/10-attempt-failed-tips-buttons.png" width="170" alt="Android: attempt failed at a large text scale"></a> |
| **PAD failure.** The generic text, no tips. | <a href="images/part4/09-pad-failure-generic.png"><img src="images/part4/09-pad-failure-generic.png" width="220" alt="Desktop: generic failure text"></a> | <a href="images/android/11-pad-generic.png"><img src="images/android/11-pad-generic.png" width="170" alt="Android: generic failure text"></a> |

### Maximum attempts

| State | Desktop | Android |
|---|---|---|
| **Resident.** The biometric exception path. | <a href="images/part4/11-max-attempts-resident.png"><img src="images/part4/11-max-attempts-resident.png" width="220" alt="Desktop: resident maximum attempts"></a> | <a href="images/android/17-max-attempts-resident.png"><img src="images/android/17-max-attempts-resident.png" width="170" alt="Android: resident maximum attempts"></a> |
| **Operator.** Contact the administrator or try later. | | <a href="images/android/18-max-attempts-operator.png"><img src="images/android/18-max-attempts-operator.png" width="170" alt="Android: operator maximum attempts"></a> |
| **Operator cooldown.** A new check during the cooldown ends at once on the same screen. | | <a href="images/android/20-operator-cooldown-refusal.png"><img src="images/android/20-operator-cooldown-refusal.png" width="170" alt="Android: operator cooldown refusal"></a> |
| **Supervisor.** Contact the administrator. | <a href="images/part4/13-supervisor-final-failure.png"><img src="images/part4/13-supervisor-final-failure.png" width="220" alt="Desktop: supervisor maximum attempts"></a> | <a href="images/android/19-max-attempts-supervisor.png"><img src="images/android/19-max-attempts-supervisor.png" width="170" alt="Android: supervisor maximum attempts"></a> |

### Device and engine problems

| State | Desktop | Android |
|---|---|---|
| **Device error.** Retry and Cancel. | <a href="images/part4/10-device-error.png"><img src="images/part4/10-device-error.png" width="220" alt="Desktop: device error"></a> | <a href="images/android/15-device-error-recoverable.png"><img src="images/android/15-device-error-recoverable.png" width="170" alt="Android: device error"></a> |
| **Device failure, final.** Recovery text and Close. | | <a href="images/android/16-device-failure-final.png"><img src="images/android/16-device-failure-final.png" width="170" alt="Android: final device failure"></a> |
| **Fail closed.** The engine cannot start: the generic text, recovery text and Close. | <a href="images/part4/14-unavailable-fail-closed.png"><img src="images/part4/14-unavailable-fail-closed.png" width="220" alt="Desktop: fail-closed screen"></a> | |

### Operator variant

| State | Desktop | Android |
|---|---|---|
| **Operator challenge.** Own title, one step. | <a href="images/part4/12-operator-login-challenge.png"><img src="images/part4/12-operator-login-challenge.png" width="220" alt="Desktop: operator challenge"></a> | |

An empty cell means there is no screenshot of that state on that client. The screen itself is the same on both clients.

## 5. Requirements and design rules

### Problem statement requirements for the user interface and user experience

| Section | Requirement | Screens that meet it |
|---|---|---|
| 10.1 Face capture screen | Live preview, positioning guidance, liveness status, capture progress, a clear sign that liveness is being verified | Wireframes 1 to 4; the real screens under "Passive check" and "Active challenge" |
| 10.1 Face capture screen | A simple experience with no technical model information | All screens; see the design rules below |
| 10.2 Passive liveness | "Checking face liveness…", no action needed, and the flow proceeds by itself | Wireframes 1 and 7; flow 1 |
| 10.3 Active challenge | The prompt appears by itself, and the person does not select it | Wireframes 3, 4, 15 and 16; flow 1 |
| 10.4 Challenge feedback | "Please continue", "Action detected", "Hold still", "Good, face captured successfully" | Wireframes 4, 5, 6 and 7 |
| 10.5 Liveness failure | The failure text and the five tips | Wireframe 8; flow 3 |
| 10.6 PAD failure | The generic text, with detail only in the audit trail | Wireframe 9; flow 3 |
| 10.7 Retry | A clear sign that a retry exists, then the recovery path at the limit | Wireframes 8 and 9 for "3 attempts left" and Retry; wireframes 10, 11 and 12 for the limit; flow 3 |
| 10.8 Operator and supervisor | The same interaction, starting by itself, with no test to select | Wireframes 11, 12, 15 and 16; flow 2 |

### Text check

Each text the problem statement quotes matches `messages_en.properties` on Desktop and the same keys on Android.

| Problem statement | Section | Screen text | Where |
|---|---|---|---|
| Checking face liveness | 10.2 | Checking face liveness… | Wireframe 1 |
| Please blink | 10.3 | Please blink | Wireframe 3 |
| Please smile | 10.3 | Please smile | Wireframe 15 |
| Please turn your head to the left | 10.3 | Please turn your head to the left | Wireframe 4 |
| Please continue | 10.4 | Please continue | Wireframe 4 |
| Action detected | 10.4 | Action detected | Wireframe 5 |
| Hold still | 10.4 | Hold still | Wireframe 6 |
| Good, face captured successfully | 10.4 | Good, face captured successfully | Wireframe 7 |
| We could not verify face liveness. Please try again. | 10.5 | We could not verify face liveness. Please try again. | Wireframe 8 |
| Face verification could not be completed. Please try again. | 10.6 | Face verification could not be completed. Please try again. | Wireframe 9 |

The problem statement writes the first text with three dots. The clients use a single ellipsis character.

### Design rules

- **No technical detail for users.** No screen shows a score, a threshold, a model name, a PAD detail or a challenge
  timing. Diagnostic mode adds detail to logs and audit only, and the screen stays the same with it on.
- **A generic PAD message.** A PAD failure shows the same text as an engine error, with no tips.
- **The same screen and texts on both clients.** Both clients draw one design from the same states, message keys and
  colours. Only each client's title bar and fonts differ.
- **Languages.** Android has six: English (en), Arabic (ar), French (fr), Hindi (hi), Kannada (kn) and Tamil (ta).
  Desktop has three: English, Arabic and French, the languages its client ships. The other languages are machine
  translations, reviewed by native speakers.
- **Online and offline look the same.** Nothing on the screen depends on the network.
- **Colour is never the only signal.** Every oval colour comes with an icon and a text. Red and green also differ by
  shape.
- **The person never chooses.** The system picks each challenge, and starts the check by itself.
- **Mirror the preview, never the frames.** The preview is mirrored like a selfie. The frames the engine sees are not.
- **Buttons appear only when they work.** Retry appears only when a retry exists. Cancel stays until the end. Close
  appears only on the final failure.
- **Reachable for everyone.** Touch targets are at least 48 units. Screen readers announce each status change. The layout
  grows with large text, and only the tips scroll.

### Limits

- The Desktop client was not launched end to end, because that needs a registered machine and a MOSIP server. The screen
  was rendered from its production classes and tested in those renders.
- The Android screens ran on one phone, with no MOSIP backend. The gates were run from a debug page.
- Screen reader behaviour on Desktop was not tried with a real screen reader.
