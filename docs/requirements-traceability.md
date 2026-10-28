# Requirements traceability matrix

This matrix covers every requirement in the problem statement *04 Face Liveness Detection and Presentation Attack
Detection for Registration and Biometric Authentication*, in the order of the PDF. For each requirement it shows the
status, where it is implemented, how it was verified, and the known gaps. As of 2026-10-28 it describes `main` at
57b4c95: Parts 1 to 5 with the fix rounds. That includes PR #9 (F3, F13, F16, F18), PR #10 (the F2 prototypes) and
PR #11 (F17), and, since the previous version of this matrix:
- PR #12, F15: identity binding for the password-only gates;
- PR #13, F13b: Part 1's development webcam on OpenCV, and a clean exit;
- PR #14: the visual design on both clients, the Android demo page and font, and F19 (the Android camera's rotation);
- PR #15: the F9 re-evaluation runner;
- PR #16, F5: a second-screen replay on a tablet, evaluated at two distances;
- PR #17: the F2b fine-tuning preparation.

It also includes the native-speaker review of the translations on 2026-10-28 (Tamil without changes, French corrected).
Only facts from the documents and code in this repository are used.

**Read the evidence with its qualifiers.** Every PAD error rate, threshold check and live session in this repository
comes from **one bona fide subject (s01) and one laptop webcam**, and is **provisional**. The attacks use one phone,
A4 prints, printed cut-outs of 13 people, a phone slideshow of photos of 14 people (consented photos; only aggregates
are kept) and, in F5, one tablet replaying the subject's video. The phone results come from one Samsung Galaxy S23 FE and one tester.
Nothing was run against real L0/L1 SBI hardware, and neither client was run end to end against a MOSIP backend.

## Legend

| Status | Meaning |
|---|---|
| ✅ Done | Implemented, and verified by the tests, device runs or evaluations cited. Any limits on that verification are in the Notes column. |
| 🟡 Partial or provisional | Implemented, but part of the requirement is not met, or the evidence is provisional (single subject s01, or not run on the target hardware). The Notes say which part. |
| ⏳ Not yet | Not implemented, or not evaluated at all. The Notes give the reason. |

## At a glance

One row per bullet of the PDF, so the same feature can appear in several sections (for example, a mandatory task
that repeats an overview requirement points back to it with "As OV-n").

| Section | ✅ | 🟡 | ⏳ |
|---|---|---|---|
| 1. Overview | 12 | 5 | 0 |
| 2. Resident registration | 9 | 1 | 0 |
| 3. Operator authentication | 7 | 0 | 0 |
| 4. Supervisor authentication | 2 | 1 | 0 |
| 5. Configuration | 11 | 0 | 0 |
| 6. Online and offline | 4 | 0 | 0 |
| 7. Integration | 6 | 1 | 0 |
| 8. Expected tasks | 19 | 2 | 0 |
| 9. Mandatory tasks 1–9 | 59 | 3 | 0 |
| 10. UI/UX 10.1–10.8 | 19 | 0 | 0 |
| 11. Bonus tasks | 7 | 1 | 0 |
| 12. Good-to-have tasks | 10 | 2 | 0 |
| 13. Deliverables | 47 | 1 | 0 |
| **Total (229)** | **212** | **17** | **0** |

PRs #12 to #17 change no status. Their rows were ✅ already (F13b, F19, the visual design), or stay 🟡 for a reason
they do not remove: F15 and F5 add evidence to SV-2, OV-15 and M3.2, which still rest on one bona fide subject. With
PRs #9 to #11, three rows had changed: OV-13 (prints) from ⏳ to 🟡, and BT-3 (automated attack scenarios) and BT-6
(secure offline model updates) from 🟡 to ✅.

The 🟡 rows come down to four themes:
- no real L0/L1 hardware (OV-2, RR-2, IN-7, M1.1, GT-6, D3.1);
- PAD evidence from one bona fide subject, with desktop-monitor replay and masks not yet covered (OV-13 to OV-16, SV-2, ET-6,
  M3.2, M3.5);
- one mid-to-high-end test phone (ET-21, BT-7);
- GT-4: guidance texts are not server-configurable.

## Who did what

| Part | Scope | Main documents |
|---|---|---|
| P1 | Device and frame pipeline: the common `Frame` / `FrameSource` contract, the SBI `/stream` adapter, the mock device, the MockMDS patch, the Android CameraX source | [part1-frame-streaming-architecture.md](part1-frame-streaming-architecture.md), [part1-qa-checklist.md](part1-qa-checklist.md), [part1-test-data-protocol.md](part1-test-data-protocol.md) |
| P2 | Passive liveness and PAD engine: the ONNX analyzer, the signed model bundle, model updates, face binding (F1), the ISO/IEC 30107-3 evaluation | [part2-model-selection.md](part2-model-selection.md), [part2-pad-architecture.md](part2-pad-architecture.md), [part2-iso30107-evaluation.md](part2-iso30107-evaluation.md), [part2-dataset-protocol.md](part2-dataset-protocol.md), [part2-findings-for-other-parts.md](part2-findings-for-other-parts.md), [part2-f2-screen-cue-design.md](part2-f2-screen-cue-design.md), [part2-f2b-finetune-prep.md](part2-f2b-finetune-prep.md), [models/README.md](../models/README.md) |
| P3 | Challenges and orchestrator: the session state machine, the passive-to-active decision, challenge selection, action detectors, retries, policy, audit, metrics | [person3-design.md](person3-design.md), [PART3_STATUS.md](../PART3_STATUS.md), [contract-change-proposals.md](contract-change-proposals.md) |
| P4 | Desktop Registration Client integration (JavaFX, v1.2.0.2) | [part4-desktop-integration.md](part4-desktop-integration.md), [desktop-integration/README.md](../desktop-integration/README.md) |
| P5 | Android Registration Client integration (Flutter, v1.1.1) | [part5-android-integration.md](part5-android-integration.md), [part5-android-benchmark.md](part5-android-benchmark.md), [android-integration/README.md](../android-integration/README.md) |
| shared | The liveness screen, states and texts for both clients | [ui-liveness-spec.md](ui-liveness-spec.md) |

## Code map

The "Where" column uses these short prefixes. Tests live in the same packages under `src/test`.

| Prefix | Path |
|---|---|
| `api/` | `liveness-api/src/main/java/io/mosip/registration/liveness/api/` |
| `core/` | `liveness-core/src/main/java/io/mosip/registration/liveness/core/` |
| `fake/` | `liveness-fake/src/main/java/io/mosip/registration/liveness/fake/` |
| `tools/` | `liveness-tools/src/main/java/io/mosip/registration/liveness/tools/` |
| `analyzer/` | `liveness-analyzer/src/main/java/io/mosip/registration/liveness/analyzer/` |
| `eval/` | `liveness-eval/src/main/java/io/mosip/registration/liveness/eval/` |
| `device/` | `registration-services/src/main/java/io/mosip/registration/liveness/device/` |
| `desktop/` | `registration-services/src/main/java/io/mosip/registration/liveness/desktop/` |
| `ctl/` | `registration-client/src/main/java/io/mosip/registration/controller/liveness/` |
| `cam/` | `android-liveness-frame-source/src/main/java/io/mosip/registration/liveness/device/android/` |
| `arc:` | a file of the Android Registration Client v1.1.1 after applying the patches in `android-integration/patches/` (58 on `main`) |

The Desktop hooks are in existing client classes: `GenericBiometricsController` and `BiometricsController`
(`controller/device/`), `LoginController` and `AuthenticationController` (`controller/auth/`),
`EODAuthenticationController` (`controller/eodapproval/`), `BaseController`, `ClientApplication`, and
`SessionContext` (registration-services).

---

## 1. Overview

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **OV-1** Applies to resident face capture, operator biometric authentication and supervisor biometric authentication | ✅ | P3 `api/WorkflowType` (RESIDENT, OPERATOR, SUPERVISOR). P4 hooks in the classes above. P5 five hooks ([P5 §3](part5-android-integration.md)) | Desktop `LivenessGateTest`, `DesktopLivenessServiceTest`. Android gate widget tests (9); on the S23 FE all five hook gates proceeded with the real engine (scores 0.994 to 0.9998) | Hooks not run end to end in a live client: no MOSIP backend (see [Known gaps](#known-gaps-and-next-steps)) |
| **OV-2** Receive the face frame stream from the connected L0/L1 device and use the configured liveness/PAD capability | 🟡 | P1 `device/sbi/SbiStreamFrameSource` over the client's own MDS discovery and `BioService.getStream()`. P4 `desktop/DeferredFrameSource`. P5 `cam/CameraXFrameSource` | `SbiStreamFrameSourceTest`, `DevicePathInteropTest`. Unpatched MockMDS returned error 605; patched, it streamed 45 of 45 frames, in order and byte-identical ([README](../README.md)) | **No real SBI hardware was used.** Android takes liveness frames from the phone camera, because SBI intents have no stream; the SBI capture stays the stored biometric and is bound to the live face by F1 ([P1 arch §5](part1-frame-streaming-architecture.md); see [Known gaps](#known-gaps-and-next-steps)) |
| **OV-3** Hybrid approach: passive first, active when needed | ✅ | P3 `core/session/SessionStateMachine`, `core/passive/PassiveEvaluator`, `core/challenge/ChallengeRunner` ([P3 design §3](person3-design.md)) | `EngineScenarioTest.residentGetsTwoChallengesAndPasses`, `padFailureOnPassiveWindow`. Live, s01: passive pass 0.9994 in 2.0 s; three forced-challenge sessions passed on the first attempt ([P2 findings](part2-findings-for-other-parts.md)) | — |
| **OV-4** Passive check needs no user action; the engine produces a quality/confidence score | ✅ | P2 `analyzer/OnnxFaceAnalyzer`: YuNet, FaceMesh V2, MiniFASNet V2 + V1SE mean, session moiré cap; quality checks in `analyzer/quality/QualityAssessor` | Java vs Python parity on golden images (`YuNetParityTest`, `FaceMeshLandmarkerParityTest`, `SilentFacePadParityTest`). `analyze()` p50 18.0 ms on the laptop, 26.6 ms on the S23 FE ([P2 arch §10](part2-pad-architecture.md), [P5 bench §A](part5-android-benchmark.md)) | — |
| **OV-5** Compare the passive result against a configurable threshold | ✅ | P3 `PassiveEvaluator`: median of 10 usable frames against `passiveThreshold` 0.85 and `padRejectThreshold` 0.30 | `PassiveEvaluatorTest`, `PolicyResolverTest`, `r9_onlyUsableFramesCountTowardsTheWindow` | Defaults kept at 0.85 / 0.30; with one subject, moving them "would be fitting noise" ([P2 eval §5](part2-iso30107-evaluation.md)) |
| **OV-6** Threshold met and no attack: the workflow proceeds | ✅ | P3 PASSED (PASSIVE_ONLY). P4 `ctl/LivenessGate`, P5 `arc:lib/ui/liveness/liveness_gate.dart` return PROCEED | `EngineScenarioTest`, `LivenessGateTest`. Phone P5-2: real engine passed passively, 0.9996, 2.3 s | — |
| **OV-7** Threshold not met: active verification starts automatically | ✅ | P3 `SessionStateMachine` (score between the two thresholds and `activeEnabled`) | `residentGetsTwoChallengesAndPasses`. Phone, dim light: passive 0.67, two challenges, passed in 5.8 s ([P5 bench §D](part5-android-benchmark.md)) | — |
| **OV-8** The client prompts dynamically selected facial actions | ✅ | P3 `core/challenge/ChallengeSelector` (SecureRandom), event `CHALLENGE_PROMPT`. Texts in [UI spec §4.1](ui-liveness-spec.md) | `ChallengeSelectorTest`; Desktop `LivenessScreenModelTest`; Flutter controller tests (36) | — |
| **OV-9** Analyse the following frames to decide whether a live person performed the action | ✅ | P3 `ChallengeRunner`; detectors in `core/detect/`; `core/passive/PadMonitor`; `core/detect/FaceContinuityGuard` | `EngineScenarioTest.everyChallengeTypeCanBePassed`, `padFailureMidChallengeEvenIfTheActionIsPerformed` | — |
| **OV-10** Proceed only when the liveness and PAD criteria are met | ✅ | P3: PASSED only from a full passive window at or above the threshold, or after every challenge was detected. P4/P5 gates act only on `onComplete(PASSED)` and fail closed | Invariants fuzz-tested (`fuzzRandomAnalysesKeepInvariants`). `FaceBindingTest`, `RealFaceBindingTest`; Android `face_binding_test.dart` | Face binding (F1) also requires the captured face to MATCH the live face (cosine ≥ 0.45) |
| **OV-11** The user never selects the challenge; the sequence is dynamic and unpredictable | ✅ | P3 `ChallengeSelector` (SecureRandom; a retry never repeats the previous sequence; BLINK never alone) and a random 0–400 ms prompt jitter ([P3 design §7](person3-design.md)) | `distributionIsUniformOverValidSequences` (10,000 draws), `retrySequenceDiffersFromPrevious`, `replayedVideoOfNaturalBlinkingNeverPasses` | — |
| **OV-12** Challenges such as blink, smile, turn left/right, look in a direction | ✅ | P3 `api/ChallengeType`: BLINK, SMILE, TURN_LEFT, TURN_RIGHT, LOOK_UP, LOOK_DOWN; `BlinkDetector`, `SmileDetector`, `HeadTurnDetector`, `GazePitchDetector` | `r3_everyDetectorDetectsItsAction`, `everyChallengeTypeCanBePassed`. Live, s01: BLINK 0.90–1.01 s, SMILE 0.90–1.36 s, TURN_LEFT 2.02–2.13 s | LOOK_UP / LOOK_DOWN are supported but not in the default pool. Testers twice read "turn" as "tilt"; the clearer prompt wording is still open ([P4 §11](part4-desktop-integration.md)) |
| **OV-13** PAD protects against printed photographs | 🟡 | P2 PAD runs on every frame; P3 `PadMonitor`. F18 session print guards: `analyzer/pad/PadLatch` (after a 3-frame median below 0.30, the rest of the session is capped at 0.60) and `analyzer/pad/VolatilityCap` (capped while the second-lowest of the last 10 model scores is below 0.85); a cap forces a challenge and never rejects ([P2 arch §3](part2-pad-architecture.md)) | F3 ([P2 eval §4](part2-iso30107-evaluation.md)): `print_flat` 0/6 [≤ 39.3%], `print_curved` 0/6 [≤ 39.3%], printed cut-outs of 13 people (`print_multi`) 0/28 responding [≤ 10.1%]. Live: a cut-out held at real-face size passed twice before F18 (0.9998, then 0.944 on attempt 4). With the guards, 29 live attack sessions: none passed; 1 attempt that would have passed without them was capped. Recorded takes: attacks 0/119 passing before and after | Provisional: one bona fide subject and one webcam. The cut-out margin is thin (highest window median 0.82). The guards' cost: 1 of 7 live real-face passive passes became a challenge, with 0 rejections; re-check on more subjects (F9) |
| **OV-14** PAD protects against digital images shown on screens | 🟡 | P2 MiniFASNet + `analyzer/pad/MoireCap` ([P2 arch §3](part2-pad-architecture.md)) | s01: close-up phone photos passing passively went from 4/12 to 0/12 with the moiré cap; shipped scorer max APCER @ 0.85 = 0/10 [≤ 25.9%], BPCER 1/9, EER 0.0% ([P2 eval §4](part2-iso30107-evaluation.md)). Phone: a face photo on a laptop screen was rejected on all 4 attempts (P5-2, and twice in P5-3). F3: a phone slideshow of photos of 14 people (`screen_photo_multi`) 0/8 responding [≤ 31.2%]; 30 takes timed out because the faces were too small | Single subject s01, one phone: provisional. The moiré margin is thin (bona fide up to 1.37, attacks from 1.435); a second cue is F2 |
| **OV-15** PAD protects against video replay | 🟡 | P2 PAD; P3 continuous `PadMonitor`, random challenges and prompt jitter | s01: `screen_replay` (a selfie video on the phone) 6/6 PAD-rejected, 0 passive passes [≤ 39.3%]. F5: the subject's 2-minute video on a tablet (Galaxy Tab, about 11 in). At 40–60 cm: 0 of 6 live sessions passed, every attempt ended in PAD_FAILURE; the 4 recorded takes were non-responses (face too small). At real-face size (the face 20–35% of the picture, 2026-10-28): 0 of 2 live sessions and 0 of 7 responding recorded takes passed [≤ 34.8%]; the PAD latch and the PAD monitor decided it, not the moiré cap ([P2 eval §4.6](part2-iso30107-evaluation.md)) | One subject, one tablet, one video. No desktop monitor, and no face larger than 35% of the picture |
| **OV-16** Other supported presentation attack techniques | 🟡 | P3 `FaceContinuityGuard` (face or photo swap), `core/session/FrameSanity` (frozen or replayed streams), the tilt-spoof guard (12°); P2 pose gate | `EngineScenarioTest`; live tilt-guard check: a tilted phone photo had 0 of 8 challenges detected at small tilts and 0 of 8 at large tilts ([PART3_STATUS §9](../PART3_STATUS.md)) | Masks and 3D attacks not tested. Digital injection is out of scope for PAD ([P2 eval §8](part2-iso30107-evaluation.md)) |
| **OV-17** No face capture accepted and no authentication completed when liveness/PAD fails | ✅ | P4 gate BLOCKED, binding fails closed ([P4 §9](part4-desktop-integration.md)). P5 gate blocked; the login page logs the operator out ([P5 §8](part5-android-integration.md)) | `LivenessGateTest`, `FaceBindingTest`; Flutter gate tests | — |

## 2. Resident registration (workflow steps 1–10)

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **RR-1** Start face capture | ✅ | P4 `GenericBiometricsController`: Scan of the Face modality calls `LivenessGate.run(RESIDENT)` before the SBI `/capture`. P5 `arc:lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` and the landscape `arc:lib/ui/process_ui/widgets/biometric_scan_middle_block.dart` | Desktop sequence (a) ([P4 §3](part4-desktop-integration.md)); Android "Resident face" hook gate on the phone | The exception photo is deliberately not gated: it is the recovery path |
| **RR-2** Receive face frames from the L0/L1 device | 🟡 | P1 `SbiStreamFrameSource`, wrapped in `device/util/PreviewFrameSource` for the preview. P5 CameraX | As OV-2 | As OV-2: no real device; Android uses the phone camera |
| **RR-3** Perform passive liveness and PAD | ✅ | P2 analyzer + P3 `PassiveEvaluator` | As OV-4 | — |
| **RR-4** Evaluate the liveness quality/confidence | ✅ | P3 median of 10 eligible frames (one face, no quality issue, a score, yaw and pitch within 25°) | `PassiveEvaluatorTest`, `r9_onlyUsableFramesCountTowardsTheWindow` | — |
| **RR-5** Threshold met: accept the face | ✅ | P4: PROCEED, then the unchanged `/capture`, then `desktop/FaceBinding`; stored only on MATCH. P5: `arc:lib/ui/liveness/face_binding.dart` and the native `FaceBinding` | `DesktopLivenessServiceTest`; `RealFaceBindingTest` (ISO 19794-5 records decode; same person MATCH). Phone FR-2: binding test MATCH 0.90 (int8 SFace) | The stored face is the SBI capture, accepted only when it matches the live face |
| **RR-6** Threshold not met: start an active challenge automatically | ✅ | P3 | As OV-7 | — |
| **RR-7** Prompt the resident with the selected action | ✅ | P3 + UI; resident `minChallenges` = 2 | As OV-8 | — |
| **RR-8** Analyse the following frames | ✅ | P3: up to 15 fps during challenges | As OV-9. Phone sessions: about 12 analysed frames per second during challenges ([P5 bench](part5-android-benchmark.md)) | — |
| **RR-9** Accept the face only after successful liveness/PAD | ✅ | P4/P5 gates and binding | As OV-10 | — |
| **RR-10** Allow retry after a failure, within the configured retry policy | ✅ | P3 `core/session/AttemptTracker`, `maxRetries` 3 (4 attempts); Retry button per [UI spec §2](ui-liveness-spec.md) | `r14_timeoutAndRetriesLeft` (3, 2, 1, then MAX_RETRIES_EXCEEDED); `LivenessScreenModelTest`. Phone P5-3: screen-photo attack rejected on all 4 attempts, final screen with recovery text | Device errors never use up a retry. A retry keeps the session's PAD latch (F18), so retries cannot give a print a fresh passive window |

## 3. Operator authentication

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **OP-1** Receive the operator's face frame stream | ✅ | Desktop: SBI `/stream` of the face device. Android: CameraX | As OV-2 | Same caveat as OV-2 |
| **OP-2** Perform passive liveness and PAD | ✅ | P3 OPERATOR policy; same engine as the resident flow | Phone P5-4: hook gates passed with the real engine | — |
| **OP-3** Start active verification when required | ✅ | OPERATOR `minChallenges` = 1 | Phone P5-2: "operator, forced": exactly one challenge, passed | — |
| **OP-4** Prompt the operator to perform the action | ✅ | Title `liveness.title.operator`; same screen | Desktop screen `images/part4/12-operator-login-challenge.png` | — |
| **OP-5** Validate the action from the incoming frames | ✅ | P3 detectors and `PadMonitor` | As OV-9 | — |
| **OP-6** Complete authentication only when the criteria are met | ✅ | Desktop `LoginController` (face login: gate, then `SessionContext.create(FACE)`; password login: gate after the password, `desktop.operator.gatePasswordLogin`), `AuthenticationController` (packet authentication), `BiometricsController` (onboarding). Android `arc:lib/ui/login_page.dart` (logs out unless the gate proceeds), `arc:lib/ui/process_ui/generic_process.dart`, `arc:lib/ui/onboard/widgets/operator_biometric_capture_scan_block_view.dart` | `LivenessGateTest`; Flutter gate tests; Desktop sequences (b), (b2), (c) | After 4 failed attempts the operator gets a 60 s cooldown; it is in memory and resets on restart (proposal P1) |
| **OP-7** Where face verification is used, liveness is an extra control that the matched face belongs to a live person | ✅ | Desktop: gate, then `/capture`, `FaceBinding` MATCH, then the existing template match (`SessionContext.validateFace`, `BaseController.captureAndValidateFace`). Password-only gates (F15): the live best frame against the user's onboarded face, `desktop/IdentityBinding` and Android `arc:android/liveness/.../IdentityBinding.java` ([P4 §2.5](part4-desktop-integration.md), [P5 §3 (k)](part5-android-integration.md)) | `FaceBindingTest`, `RealFaceBindingTest`; F15: `IdentityBindingTest`, `RealIdentityBindingTest` (Desktop), `IdentityBindingTest` on the real matcher (Android), the gates' retry tests. Live, 2026-10-28: the Desktop screen demo accepted the operator (MATCH 0.64) and refused a second person (NO_MATCH 0.07); the phone accepted s01 (MATCH 0.81) and refused a second person (NO_MATCH) | Android v1.1.1 logs in by password; F15 checks there that the live person is the user, with a provisional threshold (0.40, until the F9 data). Not run in a logged-in client: no backend (see [Known gaps](#known-gaps-and-next-steps)) |

## 4. Supervisor authentication

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **SV-1** The same liveness and PAD mechanism applies to supervisor authentication | ✅ | SUPERVISOR policy (1 challenge, BLOCK). Desktop `EODAuthenticationController` and the reviewer step of `AuthenticationController`. Android `arc:lib/ui/approve_packet/widget/authenticate_dialogbox.dart` | Desktop sequences (c) and (d); screen `13-supervisor-final-failure.png`. Phone P5-4 hook gate; Android screen `19-max-attempts-supervisor.png` | — |
| **SV-2** The supervisor is physically present, and the biometric is not a presentation attack | 🟡 | Continuous PAD, `FaceContinuityGuard`; on Desktop the face approval also runs binding and the template match | As OV-13 to OV-16 | PAD coverage is provisional (one bona fide subject; prints evaluated in F3). On Android the approval follows a password; F15 compares the live face with the supervisor's onboarded face (threshold 0.40, provisional), so the check is of the supervisor and not only of a live person. Not run in a logged-in client (no backend) |
| **SV-3** Start an active challenge automatically when passive confidence is too low | ✅ | P3, same as OV-7 | As OV-7 | — |

## 5. Configuration

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **CF-1** Configurable at Registration Client level | ✅ | Keys `mosip.registration.liveness.face.<scope>.<field>` ([P3 design §1.3](person3-design.md)). Desktop `desktop/DesktopLivenessConfig` over `ApplicationContext.map()` (synced `GLOBAL_PARAM`). Android `AndroidConfigProvider` over `GlobalParamRepository` | `DesktopLivenessConfigTest`; Android `AndroidConfigProviderTest` (4) | Ready-to-append block: [part4-registration-default.properties](part4-registration-default.properties) |
| **CF-2** Enable or disable liveness verification | ✅ | `enabled`; `newSession()` returns null and the legacy flow runs | `DefaultLivenessServiceTest.serviceBasics`, `FakeLivenessServiceTest.disabledWorkflowReturnsNullSession`, `DesktopLivenessServiceTest`. Phone P5-4: operator disabled by config, no page, no session | — |
| **CF-3** Passive liveness quality/confidence threshold | ✅ | `passiveThreshold` (0.85), `padRejectThreshold` (0.30) | `PolicyResolverTest` | — |
| **CF-4** Active liveness enablement | ✅ | `activeEnabled` | `inconclusiveWithActiveDisabledIsBelowThreshold` | — |
| **CF-5** Minimum number of active challenges | ✅ | `minChallenges` (resident 2, operator and supervisor 1) | `ChallengeSelectorTest`; phone P5-2 operator: exactly one challenge | — |
| **CF-6** Supported challenge types | ✅ | `challengeTypes` (default BLINK, SMILE, TURN_LEFT, TURN_RIGHT) | `ChallengeSelectorTest.onlyUsesSupportedTypesAndIgnoresDuplicates` | Only types the analyzer can measure are offered (D23) |
| **CF-7** Challenge timeout | ✅ | `challengeTimeoutMs` (6000) | `r14_timeoutAndRetriesLeft` | — |
| **CF-8** Maximum retry count | ✅ | `maxRetries` (3) | `r14_timeoutAndRetriesLeft` | — |
| **CF-9** Liveness policies per workflow (resident, operator, supervisor) | ✅ | `resident.`, `operator.`, `supervisor.` and `default.` keys; lookup: workflow key, bundled workflow, default key, bundled default, code | `PolicyResolverTest`, `DesktopLivenessConfigTest` | — |
| **CF-10** Behaviour when liveness repeatedly fails | ✅ | `onRepeatedFailure` (BLOCK or COOLDOWN; operator COOLDOWN), `cooldownSec` (60) | `r15_cooldownOnTheRealService`; Android screen `20-operator-cooldown-refusal.png` | Cooldown is in memory and resets on app restart (proposal P1, not applied) |
| **CF-11** Configuration applies online and offline | ✅ | Read locally from the last synced values; missing keys use `liveness-api/src/main/resources/liveness-default.properties`; an invalid config falls back to the bundled defaults and is audited, so liveness is never switched off (D5) | `r23_reloadDoesNotAffectRunningSession`; `DesktopLivenessServiceTest` (a sync applies to the next session); Android `GlobalParamRepositoryRefreshListenerTest` (3) | — |

## 6. Online and offline support

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **ON-1** Liveness works in online and offline registration environments | ✅ | Engine and signed models run locally; the engine has no network code ([P3 design §12a](person3-design.md), [P4 §8](part4-desktop-integration.md), [P5 §4](part5-android-integration.md)) | Android in airplane mode: cold start, models verified and loaded in 259 ms, real resident gate passed (0.967, 2.0 s), no network errors in the liveness logs ([P5 bench §F](part5-android-benchmark.md)) | The Desktop client was not run offline end to end (it was not launched; see Known gaps) |
| **ON-2** Core liveness, PAD, challenge generation and action validation are available locally | ✅ | `liveness-core` and `liveness-analyzer` ship in the Desktop jars and the Android module | Engine tests run with no network. Android: ONNX Runtime's telemetry component removed and telemetry switched off | — |
| **ON-3** Consistent behaviour in online registration, offline registration, operator and supervisor authentication | ✅ | One engine and one code path for every workflow; the screen does not depend on connectivity ([UI spec §1](ui-liveness-spec.md), principle 3) | As ON-1 | The airplane-mode run used the resident gate; operator and supervisor use the same path |
| **ON-4** Configuration and model updates that need connectivity are synchronized securely when online | ✅ | Config: the existing MOSIP sync, then `reloadConfig()` for new sessions. Models: P2 `analyzer/integrity/ModelUpdater` + `ModelStore` (HTTPS, SHA-256, signature, anti-rollback, atomic switch); Desktop after a `ConfigSyncedEvent`; Android `ModelUpdateWorker` | `ModelUpdaterTest` (19), `DesktopModelUpdateTest`. Phone FR-4: INSTALLED in 1.9 s; a flipped byte gave ZIP_HASH_MISMATCH; a manifest edited after signing gave BUNDLE_INVALID; a wrong URL gave HTTP_STATUS 404; the store was unchanged after each failure | Off by default: MOSIP has no update server for these bundles yet. Tested against a laptop server |

## 7. Integration (recommended tech stack)

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **IN-1** Desktop: Java, the existing Registration Client stack and device integration framework, a camera/device abstraction | ✅ | Java modules of `registration-client-parent` (JDK 11); SBI through the existing `MosipDeviceSpecificationFactory` and `BioService.getStream()`; `FrameSource` as the abstraction | Built and tested inside Registration Client v1.2.0.2 | Part 1 modified no existing client class ([README](../README.md)); the Part 4 hooks are additive ([P4 §1](part4-desktop-integration.md)) |
| **IN-2** Android: Java/Flutter, Android camera APIs, the vendor device SDK | ✅ | Flutter screens + a Java native module (`arc:android/liveness/`); CameraX; SBI intents stay the source of the stored face | 121 module tests and 302 Flutter tests, measured on the client state the 58 patches on `main` reproduce (clone 95ebc5f; the module tests at a4cc328, with no native change since) ([P5 §10](part5-android-integration.md)) | — |
| **IN-3** Common face capture device interface | ✅ | `api/Frame`, `api/FrameSource`, `api/DeviceCapabilities` ([P1 arch §2](part1-frame-streaming-architecture.md)) | `FrameTest`; `DevicePathInteropTest` | — |
| **IN-4** Device adapter pattern for several vendors | ✅ | Adapters `device/sbi/SbiStreamFrameSource`, `device/mock/MockFrameSource`, `cam/CameraXFrameSource`; `PreviewFrameSource` tee | `DevicePathInteropTest`: the mock device and the SBI adapter give identical frames and the same decision | — |
| **IN-5** Configurable liveness quality validation | ✅ | P2 analyzer keys `mosip.registration.liveness.face.analyzer.*` (luma, blur, face size, centring, moiré) ([P2 arch §9](part2-pad-architecture.md)); P3 thresholds | `AnalyzerConfigTest`, `QualityAssessorTest`, `RelativeBlurTest` | — |
| **IN-6** Existing Registration Client configuration framework | ✅ | As CF-1 | As CF-1 | — |
| **IN-7** Independent of any one vendor; works with different compliant devices | 🟡 | Any SBI-compliant MDS through `SbiStreamFrameSource`; any other camera as a `FrameSource` | MockMDS and the mock device only | No real vendor device tested; the hardware checks in [part1-qa-checklist.md](part1-qa-checklist.md) are not ticked |

## 8. Expected tasks

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **ET-1** Analyse the existing biometric device integration | ✅ | P1 [arch §1](part1-frame-streaming-architecture.md): MDS discovery, `/capture`, `/stream` | Found and fixed MockMDS's `serialNo` lookup bug (`mock-mds-patch/`) | — |
| **ET-2** Analyse the existing Desktop and Android face capture workflows | ✅ | [P1 arch §1, §5](part1-frame-streaming-architecture.md); [P4 §1](part4-desktop-integration.md) (changed classes); [P5 §3](part5-android-integration.md) (hooks) | — | — |
| **ET-3** Define the face frame streaming interface | ✅ | `api/Frame`, `api/FrameSource` | `FrameTest`, `SbiStreamFrameSourceTest` | — |
| **ET-4** Define the liveness/PAD processing interface | ✅ | `api/FaceAnalyzer`, `FrameAnalysis`, `LivenessService`, `LivenessSession`, `LivenessListener`, `LivenessEvent`, `LivenessResult`, `ErrorCode` ([P3 design §1](person3-design.md)) | Shared by both clients unchanged | Contract proposals P1–P7 are not applied; each has a workaround ([proposals](contract-change-proposals.md)) |
| **ET-5** Evaluate suitable liveness and PAD technologies | ✅ | [part2-model-selection.md](part2-model-selection.md): candidates, ONNX Runtime, rejected options | Candidate scorers compared with ISO/IEC 30107-3 metrics (s01) | — |
| **ET-6** Evaluate the solution against ISO/IEC 30107 | 🟡 | `eval/PadEvaluation`, `eval/PadMetrics`; [part2-iso30107-evaluation.md](part2-iso30107-evaluation.md) | 134 takes and 6,458 frames (bona fide and 8 attack species, F5's tablet replay among them), plus 39 live real-engine sessions for F18 and 8 live tablet sessions for F5; F5's 8 takes at real-face size are evaluated on their own. APCER per species, BPCER, APNRR/BPNRR, Clopper-Pearson bounds | One bona fide subject (s01), provisional (F9). Second screens: one tablet only (F5); no desktop monitor. Conformance would need more subjects and an accredited lab ([P2 eval §8](part2-iso30107-evaluation.md)) |
| **ET-7** Implement passive liveness detection | ✅ | P2 `analyzer/OnnxFaceAnalyzer`; P3 `PassiveEvaluator` | As OV-4 | — |
| **ET-8** Implement Presentation Attack Detection | ✅ | P2 `analyzer/pad/SilentFacePad`, `MoireCap`, `ReplayCues`; P3 `PadMonitor` | `SilentFacePadTest`, `MoireCapTest`, `PadMonitorTest` | Coverage per attack type: OV-13 to OV-16 |
| **ET-9** Implement active challenge-response | ✅ | P3 `ChallengeRunner` | `ChallengeRunnerTest`, `EngineScenarioTest` | — |
| **ET-10** Implement dynamic challenge selection | ✅ | P3 `ChallengeSelector` | As OV-11 | — |
| **ET-11** Implement facial action detection | ✅ | P3 `core/detect/` (EAR blink, mouth smile, yaw turn, pitch look) | Detector unit tests; live s01 sessions after the Part 2 review changes P3-1 to P3-4 ([PART3_STATUS §9](../PART3_STATUS.md)) | Detector thresholds checked on one subject's live sessions only |
| **ET-12** Implement configurable liveness thresholds | ✅ | As CF-3 | As CF-3 | — |
| **ET-13** Integrate liveness into resident face capture | ✅ | Section 2 | Section 2 | — |
| **ET-14** Integrate liveness into operator authentication | ✅ | Section 3 | Section 3 | — |
| **ET-15** Integrate liveness into supervisor authentication | ✅ | Section 4 | Section 4 | — |
| **ET-16** Implement online/offline processing | ✅ | Section 6 | Section 6 | — |
| **ET-17** Implement retry and failure handling | ✅ | P3 [design §4, §11](person3-design.md); UI [spec §3](ui-liveness-spec.md) | Section 9.9 below | — |
| **ET-18** Implement the required UI/UX changes | ✅ | Section 10 | Section 10 | — |
| **ET-19** Implement configuration changes | ✅ | Section 5 | Section 5 | — |
| **ET-20** Implement audit and diagnostic logging | ✅ | P3 `core/audit/AuditRecorder`; P4 `desktop/DesktopAuditSink` (REG-LIV-001 to 013, [P4 §6](part4-desktop-integration.md)); P5 `AndroidAuditSink` (REG-EVT-009 to 020, [P5 §6](part5-android-integration.md)); `diagnosticMode` adds `diag.*` statistics | `AuditRecorderTest.publicApiCannotAcceptImagesOrLandmarks` (reflection), `DesktopAuditSinkTest`, `AndroidAuditSinkTest` (11) | No image, landmark or coordinate reaches the audit trail |
| **ET-21** Perform performance and interoperability testing | 🟡 | Performance: `eval/AnalyzerBenchmark`, the Android debug-page benchmark. Interoperability: `DevicePathInteropTest`, `device/demo/SbiStreamProbe`, MockMDS | Laptop `analyze()` p50 18.0 ms. S23 FE: 26.6 ms p50 in the benchmark, 42–57 ms per frame in real sessions, 236 MB idle and 367 MB peak memory ([P5 bench](part5-android-benchmark.md)) | No real SBI device; one mid-to-high-end phone, no low-end phone (F11) |

## 9. Mandatory tasks

### 9.1 L0/L1 device integration

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M1.1** Integrate with supported L0/L1 biometric devices | 🟡 | P1 `device/sbi/SbiStreamFrameSource`, `SbiStreamFrameSourceFactory` | `SbiStreamFrameSourceTest`, `DevicePathInteropTest`; MockMDS 45 of 45 frames | Not run against real SBI hardware. `MockFrameSource.asCertificationLevel("L1")` poses as an L1 device |
| **M1.2** Receive face image/frame streams | ✅ | P1 `device/util/MjpegFrameReader`, `JpegToRgb888Converter` | `SbiStreamFrameSourceTest.convertsStreamFramesIntoLivenessFrames`, `MjpegFrameReaderTest.readsExactMockMdsWireFormat`, `SbiStreamProbeTest` | Verified on the SBI wire format through MockMDS |
| **M1.3** Continuous or periodic frame processing | ✅ | `frameStride` in all three sources; engine `analyzeFps` (8 passive, up to 15 in challenges), newest frame only | `SbiStreamFrameSourceStrideTest`, `MockFrameSourceTest`, `r19_threadingUnderAFastCamera` | — |
| **M1.4** Handle supported device frame formats | ✅ | MJPEG/JPEG to RGB888, GRAY8 for mono/NIR, CameraX YUV_420_888; `desktop/CapturedFaceDecoder` for ISO 19794-5 captures | `JpegToRgb888ConverterTest` (RGB and greyscale JPEG), `FrameTest`; Android CameraX YUV and rotation tests (4), and the target rotation from the app window, not the physical display (F19, 6); `RealFaceBindingTest` | F19 was found live (faces arrived sideways under another app's fixed-rotation transform); the live check on APK 4 passed twice, but the original mismatch did not recur, so the fix rests on the unit tests ([P5 §11](part5-android-integration.md)) |
| **M1.5** Handle device communication failures | ✅ | `SbiStreamFrameSource`: watchdog, reconnect with backoff ([P1 arch §6](part1-frame-streaming-architecture.md)) | `SbiStreamFrameSourceReconnectTest`, `UnplugReplugIntegrationTest` (camera that never comes back) | — |
| **M1.6** Handle connection and disconnection | ✅ | P1 reconnect; P3 DEVICE_ERROR with Retry, never counted as an attempt (D19, D25); P4 `desktop/DeferredFrameSource` | `UnplugReplugIntegrationTest.unplugMidCaptureShowsDeviceErrorWithRetryAndReplugPasses`, `DeferredFrameSourceTest` | The test found a stale-frame engine bug, now fixed. The manual USB unplug check is not ticked |
| **M1.7** Common device abstraction | ✅ | `api/FrameSource` | `DevicePathInteropTest` | — |

### 9.2 Passive liveness

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M2.1** Analyse incoming face frames for passive liveness | ✅ | As OV-4 | As OV-4 | — |
| **M2.2** Generate liveness quality/confidence | ✅ | `FrameAnalysis.livenessScore` per frame; median per window as `LivenessResult.passiveScore` | `OnnxFaceAnalyzerTest`, `Part3IntegrationTest` | — |
| **M2.3** Compare the result against the configured threshold | ✅ | As OV-5 | As OV-5 | — |
| **M2.4** Determine whether active liveness is required | ✅ | Three zones: pass, challenge, PAD reject | `EngineScenarioTest`; [P2 eval §4.2](part2-iso30107-evaluation.md) zone table | — |
| **M2.5** Passive liveness works offline | ✅ | As ON-1 | Airplane-mode run | — |

### 9.3 Presentation attack detection

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M3.1** PAD is part of the liveness assessment | ✅ | The per-frame score is the PAD output; the passive median below 0.30 is PAD_FAILURE with no fallback to challenges; `PadMonitor` keeps checking during challenges | `padFailureOnPassiveWindow`, `padFailureMidChallengeEvenIfTheActionIsPerformed` | — |
| **M3.2** Detect the applicable presentation attack types | 🟡 | P2 + P3 | Screens, phone replay, prints and a tablet replay evaluated (OV-13 to OV-15). F5, the tablet at two distances: 0 of 8 live sessions passed, and 0 of 7 responding recorded takes at real-face size | Second screens: one subject and one tablet, no desktop monitor; masks not tested; one bona fide subject (F9) |
| **M3.3** Prevent face capture when PAD detects an attack | ✅ | PAD_FAILURE never reaches PASSED; the resident gate blocks the capture | `padFailureOnPassiveWindow`; phone P5-3: attack rejected on all 4 attempts, final screen with recovery text | — |
| **M3.4** Prevent biometric authentication when PAD detects an attack | ✅ | Same engine for OPERATOR and SUPERVISOR; a blocked gate means no login, packet signature or approval | Desktop sequences (b) to (d); Flutter gate tests | — |
| **M3.5** Evaluate the implementation against ISO/IEC 30107 | 🟡 | As ET-6 | As ET-6 | As ET-6 |

### 9.4 Active liveness

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M4.1** Start active verification automatically when required | ✅ | As OV-7 | As OV-7 | — |
| **M4.2** Select the challenge dynamically | ✅ | As OV-11 | As OV-11 | — |
| **M4.3** Prompt the user with the selected action | ✅ | `CHALLENGE_PROMPT` with step i of n | `LivenessScreenModelTest`; Desktop screen `03-challenge-blink.png`; Android screens 02–04 | — |
| **M4.4** Receive and analyse the following frames | ✅ | P3 up to 15 fps during challenges | As OV-9 | — |
| **M4.5** Verify the requested action | ✅ | Detectors; a clearly opposite movement is WRONG_ACTION | `r4_wrongActionsAreRejected` | — |
| **M4.6** Re-evaluate liveness during active verification | ✅ | `PadMonitor` (3-frame rolling median below 0.30 fails), face count, `FaceContinuityGuard`, tilt-spoof guard | `padFailureMidChallengeEvenIfTheActionIsPerformed`, `secondFaceAppearsMidChallenge` | — |
| **M4.7** Complete the workflow only after successful verification | ✅ | As OV-10 | As OV-10 | — |

### 9.5 Resident registration

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M5.1** Integrate liveness into resident face capture | ✅ | As RR-1 | As RR-1 | Not run end to end (no backend) |
| **M5.2** Prevent acceptance of a face that fails liveness | ✅ | Gate + F1 binding | `FaceBindingTest`; Android `face_binding_test.dart` | — |
| **M5.3** Prompt the resident automatically when active verification is required | ✅ | As OV-7, OV-8 | As OV-7 | — |
| **M5.4** Support retry after a failure | ✅ | As RR-10 | As RR-10 | — |
| **M5.5** Enforce the configured retry limit | ✅ | FAILED_FINAL(MAX_RETRIES_EXCEEDED), Close only, resident recovery text (biometric exception process) | `r14_timeoutAndRetriesLeft`; Desktop screen `11-max-attempts-resident.png`; Android screen `17-max-attempts-resident.png` | — |

### 9.6 Operator authentication

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M6.1** Integrate liveness into operator biometric authentication | ✅ | As OP-6 | As OP-6 | Not run end to end (no backend) |
| **M6.2** Perform passive liveness and PAD | ✅ | As OP-2 | As OP-2 | — |
| **M6.3** Trigger an active challenge when required | ✅ | As OP-3 | As OP-3 | — |
| **M6.4** Validate the operator's facial action | ✅ | As OP-5 | As OP-5 | — |
| **M6.5** Prevent authentication when liveness fails | ✅ | Gate BLOCKED; Android logs the operator out | `LivenessGateTest`; Flutter gate tests | — |
| **M6.6** Liveness works in offline authentication | ✅ | As ON-1, ON-3 | Engine has no network code; airplane-mode run | Airplane-mode run used the resident gate |

### 9.7 Supervisor authentication

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M7.1** Integrate liveness into supervisor biometric authentication | ✅ | As SV-1 | As SV-1 | Not run end to end (no backend) |
| **M7.2** Perform passive liveness and PAD | ✅ | SUPERVISOR policy | As SV-1 | — |
| **M7.3** Trigger an active challenge when required | ✅ | As SV-3 | As SV-3 | — |
| **M7.4** Validate the supervisor's facial action | ✅ | P3 detectors | As OV-9 | — |
| **M7.5** Prevent authentication when liveness fails | ✅ | Desktop EOD approval: nothing approved. Android: the dialog stays, nothing approved | Desktop sequence (d); Flutter gate tests | — |
| **M7.6** Liveness works in offline authentication | ✅ | As ON-3 | As M6.6 | As M6.6 |

### 9.8 Configuration

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M8.1** Liveness enablement | ✅ | As CF-2 | As CF-2 | — |
| **M8.2** Passive liveness threshold | ✅ | As CF-3 | As CF-3 | — |
| **M8.3** Active liveness policy | ✅ | As CF-4 | As CF-4 | — |
| **M8.4** Challenge types | ✅ | As CF-6 | As CF-6 | — |
| **M8.5** Minimum challenge count | ✅ | As CF-5 | As CF-5 | — |
| **M8.6** Challenge timeout | ✅ | As CF-7 | As CF-7 | — |
| **M8.7** Maximum retry count | ✅ | As CF-8 | As CF-8 | — |
| **M8.8** Workflow-specific liveness policies | ✅ | As CF-9 | As CF-9 | — |

### 9.9 Error handling

The full code-to-behaviour table is [P3 design §4](person3-design.md); the screen for each case is [UI spec §3](ui-liveness-spec.md).

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **M9.1** Device unavailable | ✅ | `SbiStreamFrameSourceFactory.create` fails fast; `DEVICE_UNAVAILABLE`; DEVICE_ERROR with Retry; the 4th device error in a session is final | `SbiStreamFrameSourceTest.factoryFailsFastWhenDeviceIsNoLongerAvailable`, `DeferredFrameSourceTest`; Desktop screen `10-device-error.png`, Android screens 15 and 16 | — |
| **M9.2** Device connection failure | ✅ | `DEVICE_CONNECTION_FAILED`, `DEVICE_DISCONNECTED` with reconnect | `SbiStreamFrameSourceReconnectTest` | — |
| **M9.3** Camera unavailable | ✅ | `CAMERA_UNAVAILABLE`; Android permission refused: device text, recovery text, "Open settings" | `MockFrameSourceTest.webcamModeReportsErrorWhenGrabberThrows`; phone P5-4: permission refused permanently, Open settings, then the next gate passed (0.9944) | — |
| **M9.4** Face not detected | ✅ | `FACE_NOT_DETECTED`; hint "No face detected. Please look at the camera." | `noFaceUntilPassiveTimeout`; `DesktopLivenessServiceTest` (recorded clip, real models: FAILED with `lastCause=FACE_NOT_DETECTED`) | — |
| **M9.5** Multiple faces detected | ✅ | `MULTIPLE_FACES` after 1.5 s in passive, 2 frames during a challenge; a second face counts only at 20% or more of the primary face's area | `secondFaceAppearsMidChallenge`, `r12_guidanceForEveryProblem`. Live ([P2 arch §13](part2-pad-architecture.md)): two cut-outs side by side and a group-photo print failed all 4 attempts with MULTIPLE_FACES (hint at about 1.3 s) | By design, a face smaller than 20% of the largest one is ignored, so bystanders far behind do not fail the session: a small cut-out beside a live face was ignored and the live face passed. The analysed face is the largest, and face binding checks the stored face |
| **M9.6** Poor face quality | ✅ | `POOR_QUALITY`; hints MOVE_CLOSER, MOVE_BACK, CENTER_FACE, IMPROVE_LIGHTING, HOLD_STEADY; Android exposure assist for dim faces (F8) | `poorQualityUntilPassiveTimeout`; phone, dark room: assist off, "improve the lighting" then POOR_QUALITY; assist on, passed passively (0.9955) | — |
| **M9.7** Liveness quality below threshold | ✅ | Active challenge, or `LIVENESS_BELOW_THRESHOLD` when `activeEnabled=false` | `inconclusiveWithActiveDisabledIsBelowThreshold` | — |
| **M9.8** PAD failure | ✅ | `PAD_FAILURE`, generic message; `PAD_ATTACK_SUSPECTED` audit with `pad.*` medians | `padFailureOnPassiveWindow`, `FakeLivenessServiceTest.padFailureOnlyEverMapsToTheGenericMessage` | — |
| **M9.9** Active challenge failure | ✅ | `CHALLENGE_FAILED` (WRONG_ACTION, CONTINUITY_BREAK) | `r4_wrongActionsAreRejected` | — |
| **M9.10** Challenge timeout | ✅ | `CHALLENGE_TIMEOUT` (no detection in `challengeTimeoutMs`, or no stable baseline in 4 s) | `r14_timeoutAndRetriesLeft` | — |
| **M9.11** Maximum retries exceeded | ✅ | `MAX_RETRIES_EXCEEDED`, FAILED_FINAL; operator cooldown | `r14_timeoutAndRetriesLeft`, `r15_cooldownOnTheRealService` | — |
| **M9.12** Invalid or incomplete frame data | ✅ | `INVALID_FRAME`: a corrupt JPEG is skipped; 3 bad frames per attempt fail it; bad framing triggers a reconnect; bounded sizes | `SbiStreamFrameSourceCorruptFrameTest`, `MjpegFrameReaderTest`, `SessionStateMachineTest.corruptFramesReportedByTheSourceAreNotDeviceErrors`, `FrameSanityTest` | — |
| **M9.13** Liveness engine errors | ✅ | `ENGINE_ERROR` (3 analyzer failures in a row; no retry used up; final the second time); `MODEL_INTEGRITY_FAILED` (final, no fallback to unverified models) | `r17_analyzerExceptionsBecomeEngineError`, `VerifiedBundleTest`, `DesktopModelPreloadTest`; phone: a tampered bundle refused ("sha256 mismatch"); Desktop fail-closed screen `14-unavailable-fail-closed.png` | — |

## 10. UI/UX requirements

The screen is specified once for both clients in [ui-liveness-spec.md](ui-liveness-spec.md). Desktop renders it in
`ctl/LivenessView` with `ctl/LivenessScreenModel` (`registration-client/src/main/resources/fxml/LivenessCheck.fxml`,
`liveness.css`); Android in `arc:lib/ui/liveness/liveness_page.dart` and `liveness_controller.dart`. Screens:
[docs/images/part4/](images/part4/) (Desktop, 14) and [docs/images/android/](images/android/README.md) (Android, 20).
Both clients follow the spec's visual design (§9, PR #14): layout tests at several sizes and text scales (Desktop
`LivenessViewLayoutTest`; Android 141 checks over every state, and 47 in landscape and at a 2.0 text scale), with the
screens rendered again from the production code. Android debug and profile builds add a face liveness demo page
([P5 §9](part5-android-integration.md)).

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **UI-1.1** Live face preview | ✅ | Desktop: the SBI stream through `PreviewFrameSource`, mirrored for display only. Android: CameraX preview (PlatformView) | `PreviewFrameSourceTest`; Desktop screens rendered from the production classes (`LivenessScreenDemo --snapshots`) | Android screenshots use the fake engine and a preview placeholder, so no face is shown |
| **UI-1.2** Face positioning guidance | ✅ | Oval guide and hint line; eight `Guidance` values; engine filter (`core/guidance/PositioningHintFilter`) plus a 500 ms UI debounce | `PositioningHintFilterTest`, `r12_guidanceForEveryProblem`, `LivenessScreenModelTest` | A tilted head gets LOOK_AT_CAMERA; a dedicated hint is proposal P7 (not applied) |
| **UI-1.3** Liveness status | ✅ | Status line, oval colour always paired with an icon | `LivenessScreenModelTest` (every row of the spec's state table); Flutter page tests (11) | — |
| **UI-1.4** Capture progress | ✅ | "Step i of n", progress bar, "Attempt a of m" | As UI-1.3 | — |
| **UI-1.5** Clear indication that liveness is being verified | ✅ | Per-workflow title; "Checking face liveness…" | As UI-1.3 | — |
| **UI-1.6** Do not expose technical model information | ✅ | No score, model, threshold, PAD detail or timing on screen; diagnostics go to logs and audit only. Android debug page only in debug and profile builds | `LivenessMessagesTest` (no technical words in any text) | — |
| **UI-2.1** Passive: "Checking face liveness..." | ✅ | `liveness.status.checking`, the exact text | `LivenessMessagesTest` (catalogue equals the spec word for word); Android ARB tests | — |
| **UI-2.2** Proceed automatically when the criteria are met | ✅ | PASSED shows the success state, then continues after about 1 s (`desktop.autoContinueMs`) | `LivenessScreenModelTest`; Flutter page tests | — |
| **UI-2.3** No user action during passive verification | ✅ | No button other than Cancel in the passive state | As UI-1.3 | — |
| **UI-3.1** Display a challenge automatically when passive is below the threshold ("Please blink", "Please smile", "Please turn your head to the left") | ✅ | `liveness.challenge.*`, the exact texts | Desktop screen `03-challenge-blink.png`; Android screens 02–04 | — |
| **UI-3.2** The system generates the prompt; the user does not select an action | ✅ | As OV-11 | As OV-11 | — |
| **UI-4** Real-time feedback: "Please continue", "Action detected", "Hold still", "Good, face captured successfully" | ✅ | `CHALLENGE_PROGRESS` (plus a 1.5 s heartbeat), `ACTION_DETECTED`, `HOLD_STILL`, `PASSED` | Desktop screens 04–07; Android screens 05–08 | — |
| **UI-5** Liveness failure: "We could not verify face liveness. Please try again." and the five tips | ✅ | `liveness.error.liveness_not_verified`, `liveness.tips.*` | Desktop screen `08-attempt-failed-retry.png`; Android screens 09–10 | — |
| **UI-6.1** PAD failure: the generic "Face verification could not be completed. Please try again." | ✅ | `ErrorCode.PAD_FAILURE.userMessage()` = VERIFICATION_NOT_COMPLETED; no tips | `padFailureOnlyEverMapsToTheGenericMessage`; Desktop screen `09-pad-failure-generic.png`; Android screen `11-pad-generic.png` | — |
| **UI-6.2** Detailed PAD information only through diagnostic or audit mechanisms | ✅ | `PAD_ATTACK_SUSPECTED` (Desktop REG-LIV-005, Android REG-EVT-013) with at most 8 `pad.*` medians | `r24_r25_r26_auditOnTheRealService` | — |
| **UI-7.1** Clearly indicate when a retry is available | ✅ | Retry only after ATTEMPT_FAILED or a recoverable DEVICE_ERROR; "{n} attempt(s) left" | `LivenessScreenModelTest`; Flutter controller tests | — |
| **UI-7.2** At the maximum attempts, say so and point to the recovery process | ✅ | "Face verification could not be completed." plus the workflow's recovery text; Close only | Desktop screens 11 and 13; Android screens 17–19 | — |
| **UI-8.1** The same interaction principles for operator and supervisor authentication | ✅ | One screen for all workflows; only the title and recovery text differ | Desktop screens 12–13; Android screens 18–20 | — |
| **UI-8.2** Liveness starts automatically; the operator or supervisor never selects a test | ✅ | The gate opens the screen; challenges are chosen by the engine | Gate tests on both clients | — |

## 11. Bonus tasks

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **BT-1** A mock L0/L1 device for development and automated testing | ✅ | P1 `device/mock/MockFrameSource` (images, recorded video, webcam; `asCertificationLevel`), `MjpegRecorder`; `mock-mds-patch/` (MockMDS lookup fix and face stream modes) | `MockFrameSourceTest`, `MjpegRecorderTest`; MockMDS `FaceStreamProviderTest` (7); 45 of 45 frames through the real SBI path | `SarxosWebcamFrameGrabber` and MockMDS webcam mode compile but were not run with a camera. F13: sarxos, the library behind that grabber, crashed the evaluation tools with a native heap corruption after about 25 camera restarts in a soak. OpenCV ran 89 restarts and 30 minutes without an error, and is now the tools' default capture. F13b: Part 1's development webcam paths default to OpenCV too, and the tools release the camera before they exit (sarxos' shutdown hook once hung the Desktop demo's exit); `WebcamGrabbersTest` (9), `LivenessScreenDemoTest` (2). On 2026-10-28 two live screen-demo runs and a delete exited cleanly; which grabber ran was not captured |
| **BT-2** Automated liveness test scenarios | ✅ | P3 `fake/FakeScenario` (scripted scenarios with real timing), `EngineScenarioTest`, `Part3RequirementsAuditTest` (the requirement-by-requirement audit); P1 `RecordedVideoE2ETest`; `LivenessHarness --headless` exit codes; `tools/TraceRecorder`, `TraceReplayer`, `DetectorTuningReport` | `FakeLivenessServiceTest.everyScenarioEndsInExactlyOneExpectedResult`; [PART3_STATUS](../PART3_STATUS.md): 29 DONE, 0 PARTIAL | — |
| **BT-3** Automated PAD attack test scenarios | ✅ | `eval/PadEvaluation` over consented local recordings; `RecordedVideoE2ETest` for `__expect-FAILED-<code>` clips; scripted attack scores in engine tests; `FakeScenario` PAD attack. F16: four attack clips in `registration-services/src/test/resources/liveness/videos/nasa-*`, a public-domain NASA portrait printed or shown on a phone, 75 frames each | `PadEvaluationTest`, `PadMetricsTest`; engine PAD tests. `RecordedVideoE2ETest` runs the four clips with every build (5 of 5 with the synthetic clip); their expected codes (PAD_FAILURE twice, POOR_QUALITY, CHALLENGE_TIMEOUT) were observed in 3 of 3 runs, and all 300 frames were checked | One identity and two species (print, phone screen). Consented recordings of real people stay local for privacy ([part1-test-data-protocol.md](part1-test-data-protocol.md)) |
| **BT-4** An interoperability testing framework | ✅ | `DevicePathInteropTest`, `SbiStreamProbe` (checks any MDS answers the client's exact `/stream` request) | `SbiStreamProbeTest`; MockMDS run | No real vendor device |
| **BT-5** Liveness model version management | ✅ | Signed `models/manifest.json` with `bundleVersion` `pad-YYYY.MM.N`; `analyzer/integrity/ModelStore` (anti-rollback, keeps current and previous, rollback); `modelVersion` in every audit record; `eval/ModelBundleTool` | `ModelStoreTest`, `VerifiedBundleTest`, `ModelBundleToolTest`; phone model-update cases a to e ([P5 bench §E](part5-android-benchmark.md)) | — |
| **BT-6** Secure offline model updates | ✅ | `ModelStore.install` (verify, anti-rollback, atomic switch); `ModelBundleTool install` and `rollback`; the Android debug page. F17, in release builds: `analyzer/integrity/ModelUpdater.installZip` takes the zip the online update downloads, from removable media (size, entry and name limits, zip-slip refused, a temporary folder that is always deleted; signature, sizes and hashes are checked before the version). Desktop: `desktop/DesktopLivenessService.installOfflineBundle` and `rollbackModelBundle`, as two operational-task tiles. Android: a "Face liveness models" page with the system file picker. Supervisors and admins only, switch `model.offlineInstall.enabled`; audited as REG-LIV-012 / REG-EVT-019, with codes and versions only | Phone (debug page): install, "not newer" refused, a tampered copy refused before the store was touched, rollback, restart persistence. F17: `ModelUpdaterTest` (19), `DesktopOfflineModelInstallTest` (7), `LivenessOfflineModelInstallTest` (10), widget tests (6). The real test zips were run through `installZip` with the built-in keys: installed, tampered refused, zip-slip refused, rolled back | F17 was not tried in a logged-in client, because there is no backend. The role gate is proven by tests and the release build. The Android phone check opens the same production page from the debug build. It passed on 2026-10-27 on a Galaxy S23 FE: 10.5 in use, 10.8 installed from Downloads with the system file picker, then rolled back to 10.5 |
| **BT-7** Optimise liveness processing for low-resource Android devices | 🟡 | Benchmark-chosen defaults (CPU, 2 threads); frames paced to 15/s, newest frame only; 8 fps passive; per-ABI APKs; int8 SFace ([P5 bench](part5-android-benchmark.md)) | S23 FE: 26.6 ms per frame p50; the arm64 split APK is 151.3 MiB, against 256 MiB for the single arm64-targeted APK; INT8 for the liveness models measured and rejected (slower and less accurate) | Only one mid-to-high-end phone tested (F11). A reduced ONNX Runtime build is in the F10 backlog (F10b) |
| **BT-8** Hardware acceleration where available | ✅ | Analyzer key `accelerator` (CPU, XNNPACK, NNAPI) with CPU fallback; `factory.activeAccelerator()` reports what runs | `OnnxModelsAcceleratorTest`; phone: every provider gave the reference scores (difference below 0.00005); XNNPACK about 2x and NNAPI about 4x slower than CPU, so CPU is the default | No GPU provider evaluated |

## 12. Good-to-have tasks

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **GT-1** Configurable challenge pools | ✅ | As CF-6 | As CF-6 | — |
| **GT-2** Multiple facial actions | ✅ | Six challenge types; `minChallenges` above 1 | As OV-12 | — |
| **GT-3** Different liveness policies for resident, operator and supervisor | ✅ | As CF-9; shipped defaults differ (resident 2 challenges and BLOCK; operator 1 and COOLDOWN; supervisor 1 and BLOCK) | As CF-9 | — |
| **GT-4** Configurable user guidance | 🟡 | All texts are message keys (`messages_*.properties` on Desktop, ARB files on Android), per language and per workflow (titles, recovery texts); `desktop.animations` for reduced motion | `LivenessMessagesTest`; Android ARB tests (13) | Guidance texts change only with an app update, not through synced configuration. Hint timing is engine code (`core/EngineTuning`) |
| **GT-5** Diagnostic mode for device and liveness troubleshooting | ✅ | `diagnosticMode` adds `diag.*` statistics to audit; Desktop `LIVENESS-DIAG` client-log trace; `LivenessHarness --diagnostic`, `FramePipelineDemo`, `SbiStreamProbe`; Android debug page | `AuditRecorderTest` diagnostic tests, `HarnessModelTest` | Never changes the production screen |
| **GT-6** Multiple biometric device vendors | 🟡 | As IN-7 | As IN-7 | As IN-7 |
| **GT-7** Anonymised operational metrics | ✅ | `core/metrics/LivenessMetrics`; Desktop `LIVENESS_METRICS` audit (REG-LIV-011) every 20 sessions; Android `LivenessMetricsStore` across restarts | Android: the stored counts of 10 sessions across 7 app processes matched the session logs one for one | Counts and averages only: no session ids, scores, images or device identity |
| **GT-8a** Average liveness processing time | ✅ | `avgProcessingMs` | Phone store: 55.2 ms over 288 frames | — |
| **GT-8b** Challenge completion time | ✅ | `avgChallengeCompletionMs` | Phone store: 1251 ms over 2 challenges | — |
| **GT-8c** Liveness retry rate | ✅ | retries per session | Phone store: 0.10 | — |
| **GT-8d** Liveness failure rate | ✅ | final failures per session | Phone store: 0.00 | — |
| **GT-9** Device capability discovery | ✅ | `api/DeviceCapabilities`, `device/util/CapabilityProbe`, `SbiStreamFrameSourceFactory.probeCapabilities` ([P1 arch §7](part1-frame-streaming-architecture.md)) | `MjpegRecorderTest` probe tests | MDS device info has no resolution or frame rate, so for SBI devices they are observed from the stream |

## 13. Deliverables

### 13.1 Technical design

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **D1.1** High-level architecture for integrating L0/L1 devices with the Registration Client | ✅ | [P1 arch §3–§5](part1-frame-streaming-architecture.md); [P4 §1](part4-desktop-integration.md); [P5 §2](part5-android-integration.md) | — | — |
| **D1.2** Face frame streaming architecture | ✅ | [P1 arch](part1-frame-streaming-architecture.md) §2–§10 | — | — |
| **D1.3** Liveness and PAD processing architecture | ✅ | [P2 arch §1–§2](part2-pad-architecture.md); [P3 design §2](person3-design.md) | — | — |
| **D1.4** Passive-to-active liveness decision flow | ✅ | [P3 design §3, §3a, §6](person3-design.md) | — | — |
| **D1.5** Dynamic challenge mechanism | ✅ | [P3 design §7](person3-design.md) | — | — |
| **D1.6** Resident registration integration flow | ✅ | [P3 design §10.1](person3-design.md); [P4 §3 (a)](part4-desktop-integration.md); [P5 §3 (a)](part5-android-integration.md) | — | — |
| **D1.7** Operator authentication integration flow | ✅ | [P3 design §10.2](person3-design.md); [P4 §3 (b), (b2), (c)](part4-desktop-integration.md); [P5 §3 (b)–(d)](part5-android-integration.md) | — | — |
| **D1.8** Supervisor authentication integration flow | ✅ | [P3 design §10.3](person3-design.md); [P4 §3 (c), (d)](part4-desktop-integration.md); [P5 §3 (e)](part5-android-integration.md) | — | — |
| **D1.9** Online and offline architecture | ✅ | [P3 design §12a](person3-design.md); [P4 §8](part4-desktop-integration.md); [P5 §4](part5-android-integration.md); model updates [P2 arch §7](part2-pad-architecture.md) | — | — |
| **D1.10** Configuration design | ✅ | [P3 design §1.3](person3-design.md); [P4 §5](part4-desktop-integration.md); [P5 §5](part5-android-integration.md); analyzer keys [P2 arch §9](part2-pad-architecture.md) | — | — |
| **D1.11** Device integration interface | ✅ | [P1 arch §2](part1-frame-streaming-architecture.md) | — | — |
| **D1.12** Liveness engine interface | ✅ | [P3 design §1](person3-design.md); [P2 arch §2, §5](part2-pad-architecture.md) | — | — |
| **D1.13** Error handling and recovery flow | ✅ | [P3 design §4, §11](person3-design.md); [P1 arch §6, §9](part1-frame-streaming-architecture.md); [P4 §3 (e), (f)](part4-desktop-integration.md); [P5 §3 (f), (g)](part5-android-integration.md) | — | — |
| **D1.14** Sequence diagrams | ✅ | [P1 arch §3](part1-frame-streaming-architecture.md); [P3 design §10](person3-design.md); [P2 arch §7](part2-pad-architecture.md); [P4 §3](part4-desktop-integration.md) (a)–(g); [P5 §3](part5-android-integration.md) (a)–(j) | — | — |
| **D1.15** Security and privacy considerations | ✅ | [P3 design §12](person3-design.md); [P2 arch §7–§8](part2-pad-architecture.md); [P4 §9](part4-desktop-integration.md); [P5 §8](part5-android-integration.md); recording privacy in [part1-test-data-protocol.md](part1-test-data-protocol.md) and [part2-dataset-protocol.md](part2-dataset-protocol.md) | — | — |
| **D1.16** ISO/IEC 30107 alignment considerations | ✅ | [P3 design §13](person3-design.md); [P2 eval §8](part2-iso30107-evaluation.md) checklist; ISO/IEC 19794-5 best frame in [P3 design §12b](person3-design.md) | — | The document exists; the evaluation behind it is provisional (ET-6) |

### 13.2 UI/UX design

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **D2.1** Updated resident face capture screen | ✅ | [UI spec](ui-liveness-spec.md); [P4 §4](part4-desktop-integration.md) | Desktop screens 01–11; Android screens 01–17 | — |
| **D2.2** Updated operator authentication screen | ✅ | Same screen, operator title and recovery text | Desktop screen 12; Android screens 18 and 20 | — |
| **D2.3** Updated supervisor authentication screen | ✅ | Same screen, supervisor title and recovery text | Desktop screen 13; Android screen 19 | — |
| **D2.4** Live face preview | ✅ | As UI-1.1 | As UI-1.1 | — |
| **D2.5** Liveness verification status | ✅ | [UI spec §3](ui-liveness-spec.md) | As UI-1.3 | — |
| **D2.6** Active challenge prompts | ✅ | [UI spec §4.1](ui-liveness-spec.md) | As UI-3.1 | — |
| **D2.7** Facial action guidance | ✅ | [UI spec §4.3](ui-liveness-spec.md) | As UI-1.2 | — |
| **D2.8** Challenge progress indication | ✅ | [UI spec §2](ui-liveness-spec.md) | As UI-1.4 | — |
| **D2.9** Liveness failure messages | ✅ | [UI spec §4.1–§4.2](ui-liveness-spec.md) | As UI-5 | — |
| **D2.10** PAD/failure handling messages | ✅ | [UI spec §1, §3](ui-liveness-spec.md) | As UI-6.1 | — |
| **D2.11** Retry flow | ✅ | [UI spec §6](ui-liveness-spec.md) | As UI-7.1 | — |
| **D2.12** Maximum retry/failure flow | ✅ | [UI spec §3, §6](ui-liveness-spec.md) | As UI-7.2 | — |
| **D2.13** Device error handling | ✅ | [UI spec §3](ui-liveness-spec.md) (DEVICE_ERROR row) | Desktop screen 10; Android screens 15–16 | — |
| **D2.14** Online/offline UI behaviour | ✅ | [UI spec §1](ui-liveness-spec.md), principle 3: identical | — | — |
| **D2.15** Consistent Desktop and Android experience | ✅ | One spec, same states, texts and message keys | Desktop `LivenessMessagesTest` (catalogue equals the spec); Android ARB tests (45 liveness keys, F15's among them, and F17's 15 model-page keys, in six languages) | Translations are machine-translated; Tamil and French were reviewed by a native speaker on 2026-10-28 (French corrected). Desktop has English, Arabic and French (the languages v1.2.0.2 ships); Android has six languages |

### 13.3 Implementation / build

| Requirement | Status | Where (Part, code/doc path) | Evidence / verification | Notes / gaps |
|---|---|---|---|---|
| **D3.1** L0/L1 biometric device integration | 🟡 | As M1.1 | As M1.1 | No real SBI hardware |
| **D3.2** Face frame stream handling | ✅ | As M1.2–M1.4 | As M1.2 | — |
| **D3.3** Liveness/PAD engine integration | ✅ | P4 `desktop/DesktopLivenessService` (engine, device, preload, config reload, metrics); P5 `arc:android/liveness/.../LivenessServiceHolder.java` | `DesktopLivenessServiceTest` (real engine, signed ONNX models, recorded clip); `LivenessServiceHolderTest` | — |
| **D3.4** Passive liveness detection | ✅ | As ET-7 | As ET-7 | — |
| **D3.5** Active liveness challenge | ✅ | As ET-9 | As ET-9 | — |
| **D3.6** Dynamic challenge selection | ✅ | As ET-10 | As ET-10 | — |
| **D3.7** Facial action validation | ✅ | As ET-11 | As ET-11 | — |
| **D3.8** Configurable liveness threshold | ✅ | As CF-3 | As CF-3 | — |
| **D3.9** Resident face capture integration | ✅ | Section 2 | Section 2 | — |
| **D3.10** Operator authentication integration | ✅ | Section 3 | Section 3 | — |
| **D3.11** Supervisor authentication integration | ✅ | Section 4 | Section 4 | — |
| **D3.12** Retry and failure handling | ✅ | As ET-17 | Section 9.9 | — |
| **D3.13** Online/offline support | ✅ | Section 6 | Section 6 | — |
| **D3.14** Configuration support | ✅ | Section 5 | Section 5 | — |
| **D3.15** Audit and diagnostic logging | ✅ | As ET-20 | As ET-20 | — |
| **D3.16** Desktop Registration Client changes | ✅ | `desktop-integration/person4-desktop.diff` (69 text files on `main`) plus 14 screen images; [P4 doc](part4-desktop-integration.md) | Measured on `main` at 57b4c95 (2026-10-28), the Part 4 test command runs `registration-services` 179 (Part 1's 81 and Part 4's 52 liveness tests, the four F16 clips, and the existing audit, login and config sync suites) and `registration-client` 43, all passing; `RecordedVideoE2ETest` 5 of 5 ([P4 §10](part4-desktop-integration.md)) | Compiled and tested, not launched end to end (needs a registered machine, database and server) |
| **D3.17** Android Registration Client changes | ✅ | `android-integration/patches/` (58 patches against v1.1.1 on `main`), `person5-liveness.diff`, `scripts/` | For `main` with its 58 patches, measured on the client state they reproduce (clone 95ebc5f: the Flutter tests on 2026-10-28; the module tests at a4cc328, with no native change since): 121 module tests, 302 Flutter tests and 17 clientmanager tests (3 refresh-listener, 14 `SessionManagerTest`) pass. A fresh clone of v1.1.1 with the 58 patches, or with the consolidated diff, gives the integration branch's tree; rebuilt engine jars differ only in their embedded build info | 14 upstream v1.1.1 clientmanager tests fail identically before any liveness change ([P5 §10](part5-android-integration.md)) |

---

## Known gaps and next steps

| Gap | Effect | Next step | Source |
|---|---|---|---|
| **F2: a second screen cue** | Close-up screen attacks are caught by one cue only, the session moiré cap, measured on one phone and one webcam with a thin margin (1.37 vs 1.435) | The design is on `main`. Three prototypes were measured, all with negative results: bezel and edges that move with the face, banding, and colour. None ships, so the moiré cap stays the only screen cue. Next: lossless multi-device recordings, or fine-tuning MiniFASNet on close-up screens with the multi-subject data. The fine-tuning script and its preparation note are on `main` (F2b, PR #17); the real run needs the F9 data | [part2-f2-screen-cue-design.md](part2-f2-screen-cue-design.md), [part2-f2b-finetune-prep.md](part2-f2b-finetune-prep.md) |
| **F3: prints evaluated on one bona fide subject** | Prints and cut-outs give APCER 0/6, 0/6 and 0/28, but the cut-out margin is thin (highest window median 0.82). The cost of the F18 guards on real faces (1 of 7 passive passes became a challenge) is known for s01 only | Re-check per subject on the F9 data with `models/tools/session_guard_replay.py`. If more than 25% of real-face passive passes become challenges, evaluate a volatility threshold of 0.80 | [P2 eval §4–§5](part2-iso30107-evaluation.md) |
| **F9: single-subject evaluation is provisional** | All PAD rates, the moiré cap and the 0.85 / 0.30 thresholds rest on subject s01, one webcam and one phone | At least 10 subjects with varied skin tones, glasses, more phones and cameras; then re-check the thresholds with the rule in [P2 eval §5](part2-iso30107-evaluation.md), and F15's identity threshold (0.40) with its rule. The runner is on `main` (PR #15: `models/tools/f9_reeval.py`, `scripts/f9-reeval.ps1`; decision rules R0–R5 fixed on 2026-10-28, before the teammates' data); the data run follows the teammate recordings. Second screens (F5) still need a desktop monitor, more tablets and phones, and faces over 35% of the picture | [P2 eval §6](part2-iso30107-evaluation.md), [dataset protocol](part2-dataset-protocol.md), [P2 eval §4.6](part2-iso30107-evaluation.md) |
| **F10b: reduced ONNX Runtime build (F10 backlog)** | ONNX Runtime is 32.6 MiB of the 151.3 MiB arm64 APK | Build a reduced ONNX Runtime with only the operators the five models use | [P5 bench §G](part5-android-benchmark.md), [P5 §11](part5-android-integration.md) |
| **F11: low-end phone performance** | Only a Samsung Galaxy S23 FE was tested; times and the 370 MB session memory may not hold on low-end devices | Benchmark a low-end phone with the debug page; try `intraOpThreads` 1, XNNPACK, a smaller `detectorMaxWidth` | [P5 bench, "Low-end phones"](part5-android-benchmark.md) |
| **No MOSIP backend** | Neither client was run end to end. Desktop hooks were checked by compilation, code review, unit tests and rendered screens; Android hooks by the debug page's "Hook gates" (the same `LivenessGate.run` call), widget tests and code review. No SBI capture was made on Android | Run both clients against a MOSIP environment with a real L0/L1 device | [P4 §11](part4-desktop-integration.md), [P5 §11](part5-android-integration.md) |
| **Android liveness frames come from the phone camera** | On Android the liveness stream is CameraX, not the L0/L1 device; the SBI capture stays the stored face | By design: the Android SBI has no stream. Mitigated by F1 face binding (the SBI capture is stored only if it matches the live face) | [P1 arch §5](part1-frame-streaming-architecture.md), [UI spec §6](ui-liveness-spec.md) |
| **Translations are machine-translated** | Arabic, Hindi and Kannada texts need a native speaker's review; Tamil (no changes) and French (corrected: « détection du vivant ») were reviewed on 2026-10-28. Desktop carries Arabic and French only | Native-speaker review; add Hindi, Kannada and Tamil to the Desktop when the client has full translations | [UI spec, Appendix A](ui-liveness-spec.md) |
| **Development signing key** | The model bundle is signed with `mosip-liveness-dev-2026-10` | Replace it with a production key held in an HSM or offline signing machine, then re-sign and release | [P2 arch §8](part2-pad-architecture.md), [models/README.md](../models/README.md) |

**Closed by PRs #12 to #17** (since the previous version of this matrix):
- **Password-only gates check identity** (F15, PR #12). After Android's operator login, packet authentication and
  supervisor approval, and after the Desktop's password login, the live best frame is compared with the user's
  onboarded face (`FaceMatcher.identify`, threshold 0.40, provisional). One retry, then the step is refused (on the
  Desktop, one failed login); without an onboarded face the check is presence-only and audited. Live on 2026-10-28:
  the Desktop screen demo accepted the operator (MATCH 0.64) and refused a second person (NO_MATCH 0.07); the phone
  accepted s01 (MATCH 0.81) and refused a second person (NO_MATCH). Not run in a logged-in client (no backend); the
  threshold waits for the F9 data (OP-7, SV-2).
- **Part 1's development webcam on OpenCV, and a clean exit** (F13b, PR #13): the tools default to OpenCV and release
  the camera before they exit, with a watchdog. Two live screen-demo runs on 2026-10-28 exited with no hang; which
  grabber ran was not captured (BT-1).
- **The visual design on both clients** (PR #14): spec §9 on Desktop and Android, with layout tests and the screens
  rendered again; on Android a face liveness demo page (debug and profile builds) and one font, Noto Sans, for the six
  languages (section 10).
- **The Android camera's rotation** (F19, PR #14): the camera takes the app window's rotation, not the physical
  display's. 6 unit tests, 3 of which fail without the fix; on APK 4 the live check passed twice, though the original
  mismatch did not recur (M1.4).
- **A second-screen replay on a tablet** (F5, PR #16): 0 of 8 live sessions passed (6 at 40–60 cm, 2 at real-face
  size), and 0 of 7 responding recorded takes at real-face size; the PAD latch and the PAD monitor decided it, not the
  moiré cap. A desktop monitor, faces over 35% and more subjects are still open (OV-15, under F9).

**Still open after PRs #12 to #17:** the F9 data run (the runner of PR #15 is ready; the teammate recordings come
first), the F2b real fine-tuning run (the preparation of PR #17 needs the F9 data), the review of the Arabic, Hindi and
Kannada texts (Tamil and French were reviewed on 2026-10-28), and
the F10b (a reduced ONNX Runtime) and F11 (a low-end phone) limits. The table above has the details.

**Closed by PRs #9 to #11:**
- **Real attack clips in the automated tests** (F16): four clips of a public-domain NASA portrait, printed or on a
  phone, run by `RecordedVideoE2ETest` with every build (BT-3).
- **Production offline model install** (F17): a signed bundle zip from removable media on both clients, in release
  builds, for supervisors and admins, verified before its version is compared (BT-6). It has not been tried in a
  logged-in client; the Android phone check passed on 2026-10-27.
- **Prints passing on a retry** (F18): the session print guards (OV-13).

Other open items recorded in the documents:
- **No real L0/L1 hardware.** Every manual hardware check in [part1-qa-checklist.md](part1-qa-checklist.md) is open.
- **Contract proposals P1–P7 are not applied** ([contract-change-proposals.md](contract-change-proposals.md)). The most visible effect is P1: the operator cooldown is in memory and resets when the app restarts.
- **No model update server.** Online updates are built and tested against a laptop server, and are off by default.
- **Accessibility.** The Desktop screen-reader notifications were not tried with Narrator or NVDA.
- **ISO/IEC 19794-5.** Uneven lighting, background and glasses glare are not measured by any part ([P3 design §12b](person3-design.md)).
- **ISO/IEC 30107 conformance** needs more subjects, more attack species and an accredited laboratory test ([P2 eval §8](part2-iso30107-evaluation.md)).

## How to verify

**Builds and tests.** JDK 11 and Maven for the engine and the Desktop; JDK 21, the Android SDK and Flutter 3.10.4 for
Android.

| What | Command | From |
|---|---|---|
| Engine, standalone (Parts 2 and 3) | `mvn -N install -Dgpg.skip`, then `mvn -f <module>/pom.xml install -Dgpg.skip` for `liveness-api`, `liveness-core`, `liveness-fake`, `liveness-tools`, `liveness-analyzer`, then `mvn -f liveness-eval/pom.xml verify -Dgpg.skip` | repository root ([README, Part 2](../README.md)) |
| Part 3 in the Registration Client build | `mvn -pl liveness-api,liveness-core,liveness-fake,liveness-tools -am verify -Dgpg.skip` | `registration-client/registration`, with this repository copied over v1.2.0.2 |
| Part 1 device pipeline | `mvn -pl liveness-api,liveness-core,registration-services -am test "-Dtest=**/liveness/**/*Test,FrameTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgpg.skip"` | same |
| Part 4 Desktop | the `install -DskipTests` and `test` commands in [P4 §10](part4-desktop-integration.md) | same |
| Part 5 Android | `.\android-integration\scripts\apply-integration.ps1 -Target <folder> -BuildApk`; then `.\gradlew.bat :liveness:testDebugUnitTest` (in `android/`) and `flutter test test/liveness` | repository root, then the client ([android-integration/README.md](../android-integration/README.md)) |
| Model bundle signature | `ModelBundleTool verify --dir models` | repository root ([README, Part 2](../README.md)) |
| PAD evaluation | `PadEvaluation --data eval-data` (needs consented local recordings, which are never committed) | repository root ([P2 eval §7](part2-iso30107-evaluation.md)) |

**Where test counts are recorded.** Current counts are `main` at 57b4c95 (2026-10-28). The engine modules and the Desktop
suites (the Part 4 test command: the liveness, audit, login and config sync tests, `RecordedVideoE2ETest` included) were
run on that commit for this version. The Android counts were measured on the client state its 58 patches reproduce
(clone 95ebc5f): the Flutter tests there on 2026-10-28, the module tests at a4cc328, with no native code change since.
The Desktop suites gave the same counts on the translation review's commit (3fa6731). A count recorded for an earlier phase is labelled with that phase.

| Suite | Count | Recorded in |
|---|---|---|
| `liveness-api`, `-core`, `-fake`, `-tools` | 288 on `main` at 57b4c95 (unchanged since 2026-10-26): api 5, core 227, fake 44, tools 12; `liveness-core` line coverage gated at 85%. At round 4 (2026-10-23): 273 (api 5, core 214, fake 44, tools 10), core line coverage 96.8% | [README, Part 3](../README.md), [P5 §10](part5-android-integration.md); round 4 in [P3 design §14](person3-design.md) and [PART3_STATUS](../PART3_STATUS.md) |
| `liveness-analyzer` | 258; coverage gated at 80% | [README, Part 2](../README.md), [P5 §10](part5-android-integration.md) |
| `liveness-eval` | 70 | same |
| `registration-services` (Registration Client v1.2.0.2 build) | 133 liveness tests: Part 1's 81 and Part 4's 52. The Part 4 test command also runs the four F16 clips and the existing audit (4), login (26) and config sync (12) suites, 179 in all | [README, Part 4](../README.md), [P4 §10](part4-desktop-integration.md) |
| `registration-client` | 43 | same |
| Part 1 device pipeline | 96 at Part 1's delivery: 72 in `registration-services` (81 with F13b's 9 `WebcamGrabbersTest`), and 24 that moved with the heuristic analyzer to `liveness-analyzer` (counted in its 258). MockMDS patch: `FaceStreamProviderTest` 7 | [README, Part 1](../README.md) |
| Android `android/liveness` module | 121 | [P5 §10](part5-android-integration.md), [android-integration/README.md](../android-integration/README.md) |
| Flutter `test/liveness` | 302 | same |
| clientmanager `GlobalParamRepositoryRefreshListenerTest` | 3 | [P5 §10](part5-android-integration.md) |
| clientmanager `SessionManagerTest` | 14 | same |
| Part 3 requirement audit | 29 DONE, 0 PARTIAL, 0 MISSING, 0 BROKEN | [PART3_STATUS](../PART3_STATUS.md) |
