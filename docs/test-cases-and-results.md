# Test cases and results

Face liveness detection and presentation attack detection for the MOSIP (Modular Open Source Identity Platform)
Registration Clients. MOSIP Decode 2026, problem 04.

State tested: this repository at commit `57b2624`, and the MOSIP Android Registration Client v1.1.1 with the patches in
`android-integration/patches/` applied (client commit `4d5e6b9`). The Desktop code runs inside Registration Client
v1.2.0.2.

## 1. Summary

**What was tested.** The liveness engine, the device and camera path, the Desktop and Android client integrations, the signed model files, face matching, audit and privacy. We used automated suites (section 2), 120 test cases (section 3), live sessions on one laptop webcam and one phone, and a recorded evaluation based on ISO/IEC 30107-3, the international standard for testing presentation attack detection (PAD) (section 4).

**What passed.**
- In the four-subject evaluation of 2026-10-28, none of the 131 attack takes passed without a challenge, and none of the 47 real-face takes was rejected as an attack. These figures are provisional.
- With the print guards on, 0 of 29 live print attack sessions passed. 0 of 8 live tablet replay sessions passed.
- Every required error case has an automated test. Both clients refuse tampered and unsigned model files.

**Main limits.**
- The evaluation used four real people, one webcam and one room, and most attacks show one person. It shows tendencies, not field error rates.
- Never tested: real L0/L1 Secure Biometric Interface (SBI) hardware, a live MOSIP backend end to end, low-end phones, replay on a desktop monitor, and masks.
- 21 attack takes reached the challenge stage, and a recorded evaluation cannot test challenges. 60 of the 131 attack takes gave no decision.
- A printed cut-out passed twice live before the print guards were added.
- The identity check rejects the right person in 42 of 155 pairs (27.1%) when the stored face is an old photo.
- No full live regression on the final build is recorded (section 8.6).

## 2. Automated test totals

These numbers come from a clean run on 2026-10-30. The first eight rows were built from a fresh clone of this repository at `57b2624` with an empty Maven cache, the last two on the client at `4d5e6b9`. In all, 1,267 tests passed and none failed. The only skipped tests are the 20 Flutter tests that render documentation screenshots. This table is the only place in this document that states test totals.

| Module | What it covers | Tests | Failures | How to run it |
|---|---|---|---|---|
| `liveness-api` | The shared contract: frames, frame formats, device information | 5 | 0 | `mvn -f liveness-api/pom.xml install "-Dgpg.skip"` |
| `liveness-core` | Session state machine, passive decision, challenges, action detectors, retries, cooldown, policy, audit, metrics, with a scripted analyzer | 228 | 0 | `mvn -f liveness-core/pom.xml install "-Dgpg.skip"` |
| `liveness-fake` | The scripted fake engine used by screen tests and demos: scenarios, event order, cancel, retry | 44 | 0 | `mvn -f liveness-fake/pom.xml install "-Dgpg.skip"` |
| `liveness-tools` | Trace recording, replay and the detector tuning report, on synthetic traces | 12 | 0 | `mvn -f liveness-tools/pom.xml install "-Dgpg.skip"` |
| `liveness-analyzer` | The ONNX (Open Neural Network Exchange) pipeline: face detector, landmarks, pose, PAD models, quality checks, session caps, model signature and store, model update, face matcher, parity with the Python reference | 258 | 0 | `mvn -f liveness-analyzer/pom.xml install "-Dgpg.skip"` |
| `liveness-eval` | Evaluation and bundle tools: PAD metrics, PAD and binding evaluation, dataset recorder, bundle signing tool, benchmark bookkeeping | 77 | 0 | `mvn -f liveness-eval/pom.xml verify "-Dgpg.skip"` |
| `registration-services` (liveness tests) | SBI source, mock device, unplug and replug, recorded clips, Desktop service, config, audit sink, model install and update, face and identity binding | 137 | 0 | `mvn -pl registration-services test "-Dtest=**/liveness/**/*Test" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false" "-Dgpg.skip"` |
| `registration-client` (liveness tests) | Gate rules, screen model, texts in each language, screen layout | 44 | 0 | `mvn -pl registration-client test "-Dtest=**/liveness/**/*Test" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false" "-Dgpg.skip"` |
| Android liveness module | Audit sink, config, camera conversion, pacing, rotation, exposure assist, binding on the real matcher, offline model install, metrics store | 123 | 0 | `.\gradlew.bat :liveness:testDebugUnitTest` (in `android/`) |
| Flutter `test/liveness` | Liveness page, controller, gate, binding hooks, translations, layouts, demo page | 339 | 0 | `flutter test test/liveness` |

Notes on the table:
- **Build order.** Section 9 gives the order. The first six rows need the parent install first. The two Registration
  Client rows run inside the v1.2.0.2 build. The last two rows run in the Android client checkout.
- **Tests that can skip.** A skip is not a pass, so read the skipped count of the clean run too.
  - `LivenessViewLayoutTest` skips without a JavaFX toolkit.
  - `RealFaceBindingTest` and `RealIdentityBindingTest` skip if the golden images are not next to the module.
  - The Flutter tests that render documentation screenshots skip unless `LIVENESS_SCREENSHOTS` names a folder.
- **Coverage gates.** The build enforces 85% line coverage on `liveness-core` and 80% on `liveness-analyzer`.
- **Upstream failures.** The Android `:clientmanager:testDebugUnitTest` task is not in this table. Section 8.1 explains
  why.

## 3. Test case catalogue

### 3.0 How to read this section

**Words used here.**
- **Bona fide presentation:** a real, live face in front of the camera.
- **Presentation attack:** something shown to the camera to pass as a live person, such as a photo, a print or a video.
- **Attack species:** one kind of attack. Section 4.3 explains each one.
- **Session:** one full liveness check. By default it allows 4 attempts: the first and 3 retries.
- **Passive check:** the engine scores frames with no action from the person. It takes the median of 10 usable frames.
  At 0.85 or above the person passes. Below 0.30 the attempt fails as an attack. In between, a challenge starts.
- **Challenge:** a random action the person must perform, such as a blink, a smile or a head turn.
- **Take:** one short recording used in the evaluation.
- **s01 to s04:** codes for the four people who recorded real-face takes.

**The Type column.**
- **automated:** a test in one of the suites of section 2.
- **live:** a real session with a person or an attack in front of a real camera, on the date shown.
- **evaluation:** a measurement over recorded data or a benchmark, on the date shown.

**The Actual result column.**
- For an automated case, "Pass" means two things. The named test exists and asserts the expected result. Its module is
  in section 2. A failure in section 2 overrides the "Pass" here.
- The last all-passing records before the clean run are these:

| Modules | Last recorded all-passing run |
|---|---|
| Engine modules and Desktop suites | 2026-10-28, on the code now at `main`, `docs/requirements-traceability.md`, "Where test counts are recorded" |
| Android liveness module and Flutter | 2026-10-28, client commit `4d5e6b9`, `docs/part5-android-integration.md`, section 10 |

- For a live or evaluation case, the actual result is the recorded number. These cases were not repeated for this
  document.

**The Evidence column.** Test names are class and method. A name that starts with a dot uses the class named just
before it. "(Desktop)" and "(Android)" mark classes that exist on both clients. Android tests are in the client under
`android/liveness/src/test`. Flutter tests are under `test/liveness`.

### 3.1 Device and frame pipeline

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| DEV-01 | The SBI stream reaches the engine as frames | Two JPEG frames in the exact framing of MOSIP's mock device service (MockMDS), through a mocked biometric service | Both frames arrive as RGB frames, in order | Pass | automated | `SbiStreamFrameSourceTest.convertsStreamFramesIntoLivenessFrames`, `MjpegFrameReaderTest.readsExactMockMdsWireFormat` |
| DEV-02 | Device paths are interchangeable | The same 20-frame recording through the mock device and the SBI adapter; then a live scene and an attack scene through both | Byte-identical frames in the same order; the same liveness decision | Pass | automated | `DevicePathInteropTest.mockDeviceAndSbiAdapterDeliverIdenticalFramesInTheSameOrder`, `.bothDevicePathsReachTheSameLivenessDecision` |
| DEV-03 | MOSIP's MockMDS as a real process | Unpatched, then patched MockMDS; the probe tool and the client's own stream request | The patched service streams every frame | Unpatched returned error 605. Patched: 45 of 45 frames, in order and byte-identical. MockMDS only, no vendor device | live, 2026-10-23 (date of the commit) | `README.md`, the section on MockMDS |
| DEV-04 | Frame rate control | Stride of 3 on 9 device frames; a 200 frames per second camera with a 30 ms analyzer | Every third frame delivered; one worker thread, 10 to 11 analyses, most frames dropped and not queued, no deadlock | Pass | automated | `SbiStreamFrameSourceStrideTest.strideOfThreeDeliversEveryThirdPhysicalFrame`, `Part3RequirementsAuditTest.r19_threadingUnderAFastCamera` |
| DEV-05 | Other frame formats | A greyscale JPEG; a one-channel GRAY8 frame | Decoded with equal channels; expanded to RGB once | Pass | automated | `JpegToRgb888ConverterTest.grayscaleJpegDecodesWithEqualChannels`, `FrameTest.gray8IsExpandedToRgbOnceOnDemand` |
| DEV-06 | The live preview cannot disturb the engine | Three preview listeners, one of which throws | The engine and the other previews get each frame; device errors reach the engine only | Pass | automated | `PreviewFrameSourceTest.everyFrameReachesTheEngineAndEveryPreviewAndErrorsOnlyTheEngine` |
| DEV-07 | Android camera conversion, pacing and rotation target | YUV camera frames with row padding at 0, 90, 180 and 270 degrees; 2 s of frames at 30 per second paced to 15; window at 270 with display at 90, then both changing | RGB matches the reference at each rotation; 29 to 31 frames delivered, skipped frames never converted; the target follows the app window, not the display | Pass | automated | `CameraXFrameSourceConversionTest.planarChromaWithRowPaddingMatchesTheReference`, `CameraXFrameSourcePacingTest.thirtyFpsIsPacedToAboutFifteen`, `CameraXFrameSourceRotationTest.bindUsesTheWindowsRotationNotTheDisplays`, `.aDisplayChangeGivesTheBoundUseCasesTheWindowsNewRotation` |
| DEV-08 | Rotation on a real phone | Samsung Galaxy S23 FE: sessions started at 0, 90 and 270 degrees; the phone flipped 180 degrees during a session | Sessions pass; the target follows the display | Sessions at 0, 90 and 270 passed. The target followed each display rotation within about 15 ms. **Limit:** if the screen has not turned, the frames arrive sideways or upside down and no face is found. Once the display stayed at 90 for about 40 s after a flip. 2 of 3 landscape sessions went to a challenge | live, 2026-10-29 | `docs/part5-android-integration.md`, section 11 |
| DEV-09 | Dark faces on the phone | Dark room, same position, exposure assist off, then on | Off: a lighting hint and a failed attempt. On: a pass | Off: face brightness 20 to 26 of 255, "improve the lighting", POOR_QUALITY, cancelled by the tester at 19.6 s. On: exposure raised in about 4 s, passed passively at 0.9955 in 6.1 s. **Caveat:** the tester also raised the screen brightness slightly in that run | live, 2026-10-24 | `docs/part5-android-benchmark.md`, section D; `ExposureAssistTest.darkForASecondStepsUpAtMostEvery500msToTheMaximum` |

### 3.2 Passive liveness

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| PAS-01 | A high score passes with no challenge | Scripted score of 0.9 on every frame | PASSED, passive only, no prompt, 10 to 12 frames analysed | Pass | automated | `EngineScenarioTest.neutralHighScorePassesPassively` |
| PAS-02 | The decision rule at its edges | Ten frames at exactly 0.85; ten at exactly 0.30; two perfect frames in a window of 0.6; two bad frames in a window of 0.9 | 0.85 passes; 0.30 goes to a challenge; the median decides, not the best or worst frame | Pass | automated | `PassiveEvaluatorTest.boundaryValues`, `.medianNotMaxDecides` |
| PAS-03 | Only usable frames count | Blurry, off-centre, turned and unscored frames; a lost face or a second face near the end of the window | Those frames are not counted; a lost face or second face restarts the window | Pass | automated | `Part3RequirementsAuditTest.r9_onlyUsableFramesCountTowardsTheWindow`, `PassiveEvaluatorTest.ineligibleFramesAreSkippedNotCounted`, `.faceLossOrSecondFaceRestartsTheWindow` |
| PAS-04 | Real models give the reference result | A public-domain portrait through the real analyzer, then through a full session | Score within 0.0001 of the Python reference; session PASSED, passive only, at 0.85 or above | Pass | automated | `OnnxFaceAnalyzerTest.portraitIsUsableWithAScoreThatMatchesTheReference`, `Part3IntegrationTest.aResidentSessionPassesPassivelyWithTheRealAnalyzer` |
| PAS-05 | Each quality problem is named and gets a hint | Synthetic frames: too dark, too bright, flat, too small, too large, off-centre, cut off | Exactly one issue per frame; each issue maps to its hint | Pass | automated | `QualityAssessorTest.eachSyntheticFrameRaisesExactlyOneIssue`, `Part3RequirementsAuditTest.r12_guidanceForEveryProblem` |
| PAS-06 | A real face, live | Default policy, s01, on the laptop webcam and on the phone's front camera | Passes with no challenge | Laptop: 0.9994 in 2.0 s. Phone: 0.9996 in 2.3 s. One subject | live, 2026-10-24 | `docs/part2-findings-for-other-parts.md`, "Live verification after the changes"; `docs/part5-android-integration.md`, section 10 |

### 3.3 Presentation attack detection

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| PAD-01 | An attack score stops the attempt | Scripted score of 0.1 for a full window | PAD_FAILURE, no challenge offered, PAD_ATTACK_SUSPECTED in the audit | Pass | automated | `EngineScenarioTest.padFailureOnPassiveWindow` |
| PAD-02 | PAD keeps running during challenges | The action performed at score 0.1; attack frames in the final hold; one noisy frame, then two low frames in a row | PAD_FAILURE and no pass for the attacks; one noisy frame does not fail a person | Pass | automated | `EngineScenarioTest.padFailureMidChallengeEvenIfTheActionIsPerformed`, `ChallengeRunnerTest.padIsCheckedDuringTheFinalHoldToo`, `PadMonitorTest.singleNoisyFrameDoesNotFailButSustainedLowScoresDo` |
| PAD-03 | The moiré cap at its edge. Moiré is the fine pattern a camera picks up from a screen's pixels | A sequence with a rolling median of 1.39, 1.395 and exactly 1.40 | No cap below 1.40; capped at 0.60 at 1.40, from the third frame; a face change resets it | Pass | automated | `MoireCapTest.capAppliesFromTheThirdFrameAtOrAbove140AndResetsOnFaceChanges` |
| PAD-04 | The print guards | PAD latch: a 3-frame median below 0.30, then high scores, then a retry. Volatility cap: mixed scores | The latch caps at 0.60 for the rest of the session and across retries; only a new analyzer resets it; exactly 0.30 does not latch. The volatility cap needs 5 values and uses the second-lowest of the last 10 | Pass | automated | `PadGuardsTest.theLatchHoldsForTheRestOfTheSessionAndTheVolatilityCapRollsOff`, `.theLatchNeedsThreeScoresAndSkipsUnscoredFrames`, `.volatilityNeedsFiveValuesAndUsesTheSecondLowest`, `OnnxFaceAnalyzerTest.thePadLatchHoldsAcrossRetriesAndOnlyANewAnalyzerResetsIt` |
| PAD-05 | Random attacks and replays never pass | 40 fuzz runs of random frames scoring below 0.30; 30 seeds on each workflow of a replay with natural blinks at a middle score | No PASSED; no prompt in the fuzz runs; BLINK is never the only challenge | Pass | automated | `EngineScenarioTest.fuzzAttackScoresNeverPass`, `.replayedVideoOfNaturalBlinkingNeverPasses` |
| PAD-06 | Recorded clips through the real device path and models | One synthetic no-face clip and four clips of a public-domain portrait, one attempt each | Each ends FAILED with the code in its file name | Pass: phone close, PAD_FAILURE; print at normal distance, PAD_FAILURE; phone at normal distance, POOR_QUALITY; print close, CHALLENGE_TIMEOUT; no face, FACE_NOT_DETECTED. **Only two of the four attack clips are stopped by PAD.** One fails on quality. One reaches a challenge, which a recording cannot answer | automated | `RecordedVideoE2ETest.clipReachesTheExpectedDecision`; `docs/part1-test-data-protocol.md`, "Bundled regression clips" |
| PAD-07 | Close-up phone photos and the moiré cap | 12 recorded takes of a phone held close to the webcam, s01; two live runs at about 10 cm | None passes passively | Before the cap: 4 of 12 passed, at 0.89 to 0.95. With the cap: 0 of 12 [≤ 22.1%], 6 went to a challenge. Live: held at exactly 0.600, sent to a challenge, failed there, in both runs | evaluation and live, 2026-10-24 | `docs/part2-iso30107-evaluation.md`, sections 4.1 and 4.2 |
| PAD-08 | A printed cut-out at real-face size, before the print guards | One cut-out held about 20 cm away, default policy | Does not pass | **Failed: it passed twice.** 0.9998 in a discarded run, and 0.944 on attempt 4 of a targeted retest. This failure led to the print guards | live, 2026-10-27 | `docs/part2-iso30107-evaluation.md`, section 4.5 |
| PAD-09 | Print attacks with the guards on | 29 attack sessions: cut-outs still and moving, an A4 print, and four sessions forcing challenges with rotate and tilt tricks | None passes | 0 of 29 passed. 17 were judged; in the others the face was too small and the session timed out. The guards blocked 1 attempt of 63 that would have passed, at 0.923. **The rotate and tilt tricks were not exercised:** PAD stopped those cut-outs before any challenge appeared | live, 2026-10-27 | `docs/part2-iso30107-evaluation.md`, section 4.5 |
| PAD-10 | The cost of the guards on real faces | 10 real-face sessions, s01: normal, close, far, approaching, moving | No rejection | 6 passive passes, 3 passed a challenge, 1 timed out at about 1 m. 0 PAD rejections. The guards moved 1 of 7 passive passes to a challenge | live, 2026-10-27 | `docs/part2-iso30107-evaluation.md`, section 4.5 |
| PAD-11 | Video replay on a tablet | The subject's 2-minute video on a tablet of about 11 inches: 6 sessions at 40 to 60 cm, 2 sessions at real-face size | None passes | 0 of 8 passed. At 40 to 60 cm, all 18 attempts that reported a code ended in PAD_FAILURE. At real-face size both sessions ended in MAX_RETRIES_EXCEEDED. The moiré cap did not fire: the models, the PAD latch and the PAD monitor decided | live, 2026-10-27 and 2026-10-28 | `docs/part2-iso30107-evaluation.md`, section 4.6 |
| PAD-12 | A face photo on a laptop screen, seen by the phone | Back camera, three runs | Rejected on every attempt | Rejected on all 4 attempts in each run; final screen with recovery text. This is the only attack result recorded on a phone. It predates the print guards | live, 2026-10-24 | `docs/part5-android-integration.md`, section 10 |
| PAD-13 | Recorded evaluation of all species | 131 attack takes and 47 real-face takes from four subjects | No attack passes without a challenge; no real face is rejected | See section 4 | evaluation, 2026-10-28 | `docs/part2-iso30107-evaluation.md`, section 4.7 |

### 3.4 Active challenges and dynamic selection

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| ACT-01 | A middle score starts challenges by itself | Resident, score 0.6 | Two different challenges, a hold, then PASSED by the active path | Pass | automated | `EngineScenarioTest.residentGetsTwoChallengesAndPasses` |
| ACT-02 | Each challenge type can be completed | BLINK, SMILE, TURN_LEFT, TURN_RIGHT, LOOK_UP and LOOK_DOWN, each as the only type in the pool | Each is detected and the session passes | Pass. LOOK_UP and LOOK_DOWN are not in the default pool | automated | `EngineScenarioTest.everyChallengeTypeCanBePassed`, `Part3RequirementsAuditTest.r3_everyDetectorDetectsItsAction` |
| ACT-03 | Wrong actions do not count | A smile for a blink, a blink for a smile, the opposite turn or look, quick head wiggling | Not detected; an opposite movement is a wrong action | Pass | automated | `Part3RequirementsAuditTest.r4_wrongActionsAreRejected`, `AxisMotionDetectorTest.replayWiggleNeverHoldsAndSwingingToTheOtherSideIsWrongAction` |
| ACT-04 | The user cannot predict or choose the challenge | 10,000 draws; 500 retries in a row; prompt delays over 30 seeds; a pool of BLINK and SMILE with one challenge; duplicates, an unsupported type and a pool smaller than the count | Uniform over valid sequences; a retry never repeats the last sequence; delays vary; BLINK is alone only if it is the whole pool; only supported types are used; an impossible count is refused | Pass. The tests use a seeded generator. Production uses SecureRandom | automated | `ChallengeSelectorTest.distributionIsUniformOverValidSequences`, `.retrySequenceDiffersFromPrevious`, `.blinkAloneOnlyWhenItIsTheWholePool`, `.onlyUsesSupportedTypesAndIgnoresDuplicates`, `.rejectsCountLargerThanUsablePool`, `ChallengeRunnerTest.promptTimingIsJittered` |
| ACT-05 | The action must follow the prompt | A blink before the prompt; eyes already closed at the prompt; eyes closed for two seconds; a photo with closed eyes | No detection; CHALLENGE_TIMEOUT | Pass | automated | `EngineScenarioTest.actionBeforeThePromptOnlyDoesNotPass`, `.eyesClosedForTwoSecondsIsNotABlink`, `BlinkDetectorTest.eyesAlreadyClosedAtThePromptMustOpenFirst`, `.closedEyePhotoNeverBlinks` |
| ACT-06 | Tilting a photo cannot fake a blink or a smile | Eye shape shrinking during a 30 degree tilt; a smile shape at 20 degrees of yaw (head turn); moving closer to the camera | Not detected; a real blink afterwards still counts | Pass | automated | `BlinkDetectorTest.anEarDropDuringA30DegreeTiltIsNotABlink`, `SmileDetectorTest.aSmileWith20DegreesOfYawDriftIsNotASmile`, `.movingCloserToTheCameraIsNotASmile` |
| ACT-07 | The tilt guard, live | A phone photo about 10 cm from the webcam, with PAD thresholds set so that only the detectors decide; a real face for comparison | The real face passes; the photo does not | Real face: PASSED, BLINK in 0.98 s, SMILE in 0.96 s. Photo tipped gently: 0 of 8 challenges detected. Tipped hard: 0 of 8. Before the guard, a tilted close-up photo passed BLINK and SMILE once. One subject | live, 2026-10-25 | `PART3_STATUS.md`, section 9 |
| ACT-08 | A turn needs no return | A turn held at 45 degrees | Detected while turned; a look-at-camera cue follows; the session passes | Pass | automated | `EngineScenarioTest.turnWithoutComingBackPassesAndAsksToLookAtTheCamera` |
| ACT-09 | Challenges on a real face, live | s01, forced challenges, product prompts only, no coaching | Passes on the first attempt | 3 of 3 passed on the first attempt, in 5.8 to 6.6 s. BLINK 0.90 to 1.01 s, SMILE 0.90 to 1.36 s, TURN_LEFT 2.02 to 2.13 s. One subject | live, 2026-10-24 | `docs/part2-findings-for-other-parts.md`, "Live verification after the changes" |
| ACT-10 | People understand the turn prompt | The product prompt with no coaching | The person rotates the head | **Open problem.** Testers twice read "turn" as "tilt". In one run 3 turn prompts timed out, because yaw moved only 3 to 7 degrees. Before the turn rule changed, 0 of 9 turns passed. The prompt keeps the problem statement's wording | live, 2026-10-24 | `docs/part2-findings-for-other-parts.md`, same section; `docs/part4-desktop-integration.md`, section 11 |

### 3.5 Resident flow

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| RES-01 | Desktop service with the real engine | Signed models, a recorded no-face clip through the deferred source, one attempt | FAILED, MAX_RETRIES_EXCEEDED, audited through the client's audit module; the signed bundle is used | Pass. The service was tested. The Desktop client was not launched (section 8.3) | automated | `DesktopLivenessServiceTest.realEngineOnARecordedClipFailsAndAuditsThroughTheAuditModule` |
| RES-02 | Android gate outcomes | The gate with a fake service: passed, Cancel, failed, a page closed some other way, a bridge error; the page with a two-challenge script | Proceed with the best frame; cancelled; blocked; cancelled; blocked with ENGINE_ERROR. The page follows the script, then closes as passed | Pass | automated | `liveness_gate_test.dart`: "passed: proceed with the outcome and the best frame", "failed: blocked with the outcome", "an error fails closed as blocked"; `liveness_page_test.dart`: "PASS_ACTIVE: the banner follows the script, then the page pops passed" |
| RES-03 | No capture after a failed gate | A cancelled gate and a blocked gate | Nothing armed, nothing captured | Pass | automated | `face_binding_test.dart`: "a cancelled or failed gate captures nothing" |
| RES-04 | Recovery at the final failure | A final failure in the resident workflow | Maximum-attempts text, the resident recovery text (biometric exception process), Close only | Pass | automated | `LivenessScreenModelTest.finalFailureShowsRecoveryGuidanceAndOnlyClose`; `liveness_controller_test.dart`: "FAILED_FINAL: max attempts plus each workflow's recovery text, Close only" |
| RES-05 | All five Android hook gates on a phone | Debug page "Hook gates", real engine: resident capture, operator login, onboarding, packet authentication, supervisor approval | All proceed | All five proceeded, at scores 0.994 to 0.9998. These use the same gate call as the screens. **They did not run inside the real screens:** there was no backend | live, 2026-10-24 | `docs/part5-android-integration.md`, sections 10 and 11 |

### 3.6 Operator flow

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| OPR-01 | Operators and supervisors get one challenge | Score 0.6 in each workflow | One challenge; PASSED | Pass | automated | `EngineScenarioTest.operatorAndSupervisorGetOneChallenge` |
| OPR-02 | Desktop password login is gated | No later step; OTP (one-time password) and fingerprint steps; a face step; the key switched off | Gated in the first two; not gated twice when a face step follows; not gated when off | Pass | automated | `LivenessGateTest.passwordLoginIsGatedWhenEnabledAndNoFaceStepFollows`, `.notGatedTwiceWhenAFaceLoginStepFollows`, `.notGatedWhenSwitchedOff` |
| OPR-03 | Operator policy on the phone | Forced challenges, operator workflow | Exactly one challenge | One challenge, passed. See also RES-05 | live, 2026-10-24 | `docs/part5-android-integration.md`, section 10 |

### 3.7 Supervisor flow

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| SUP-01 | Supervisor session on the real service | Score 0.93 | PASSED, passive only, with best frame, score, session id, model version and one attempt | Pass | automated | `Part3RequirementsAuditTest.r20_passedResultFields` |
| SUP-02 | Supervisor session through the device path | The headless harness on a recorded live scene, with a preview | PASSED passively; preview frames outnumber analysed frames | Pass | automated | `HarnessModelTest.headlessHarnessRunsTheRealEngineAndPressesRetry` |
| SUP-03 | Liveness from inside the approval dialog | The Android gate opened from a dialog | The page opens on the root navigator; the dialog stays | Pass. See also RES-05 | automated | `liveness_gate_test.dart`: "from inside a dialog: the page opens on the root navigator, the dialog stays" |

### 3.8 Retry, cooldown and maximum attempts

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| RET-01 | Attempts and retries left | Do nothing on every challenge; `maxRetries` of 0 | Retries left 3, 2, 1, then MAX_RETRIES_EXCEEDED; with 0, the first failure is final | Pass | automated | `Part3RequirementsAuditTest.r14_timeoutAndRetriesLeft`, `AttemptTrackerTest.allowsOnePlusMaxRetriesAttemptsThenMaxRetriesExceeded`, `.zeroRetriesFailsFinalOnFirstFailure` |
| RET-02 | Retry rules | Retry when nothing failed; Retry after a failure | Ignored; a fresh attempt with a different sequence | Pass | automated | `SessionStateMachineTest.retryGetsFreshSequenceAndIsIgnoredWhenNotFailed`, `EngineScenarioTest.wrongDirectionIsChallengeFailedAndRetryGetsFreshAttempt` |
| RET-03 | Device problems use no retry | Five device errors with one retry allowed; a short disconnect during a session | Not final; the attempt number stays 1 | Pass | automated | `AttemptTrackerTest.deviceErrorsNeverConsumeRetries`, `Part3RequirementsAuditTest.autoResumeAfterDeviceBlipKeepsTheAttemptNumber` |
| RET-04 | Operator cooldown and its edges | An operator fails, then starts a new session; the clock moves on 60,001 ms; a resident at maximum retries; a cooldown of 0; a check from inside the final callback | Refused with 0 attempts and the camera not opened; a supervisor is not affected; allowed after 60 s; no cooldown for a resident; 0 never blocks; the cooldown is already active in the callback | Pass. **Limit:** the cooldown is in memory and resets when the app restarts | automated | `Part3RequirementsAuditTest.r15_cooldownOnTheRealService`, `CooldownTrackerTest.blocksOnlyTheTrippedWorkflowUntilTheCooldownPasses`, `.zeroCooldownNeverBlocks`, `EngineScenarioTest.residentMaxRetriesDoesNotTripCooldown`, `.insideOnCompleteTheCooldownIsActiveAndTheSourceIsReleased` |
| RET-05 | Cancel at every stage | Cancel while starting, during the passive check, a challenge, a retry wait and a device error; cancel before start | CANCELLED once, no later events, source stopped; before start, the camera is never opened | Pass | automated | `EngineScenarioTest.cancelAtEachStage`, `DefaultLivenessServiceTest.cancelBeforeStartNeverOpensTheCamera` |

### 3.9 Required error cases

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| ERR-01 | Device unavailable | The device is gone at creation; opening fails, then works; no frame for 5 s after start | Fast refusal; a recoverable DEVICE_UNAVAILABLE with Retry, which opens the device again | Pass | automated | `SbiStreamFrameSourceTest.factoryFailsFastWhenDeviceIsNoLongerAvailable`, `DeferredFrameSourceTest.anUnavailableDeviceIsARecoverableErrorAndRetryOpensItAgain`, `EngineScenarioTest.noFramesAfterStartIsDeviceUnavailable` |
| ERR-02 | Connection failure | A drop in mid-stream; reconnects used up; a stall of 2.1 s during a challenge; repeated device errors | Reconnect resumes the frames; Retry is offered after the source gives up; the fourth device error is final, with the device code | Pass | automated | `SbiStreamFrameSourceReconnectTest.reconnectsAfterMidStreamDisconnectAndResumesDeliveryWithContinuousSequence`, `.reportsAFinalNonRecoverableFailureOnceReconnectAttemptsAreExhausted`, `EngineScenarioTest.streamStallMidChallengeIsDisconnectedAndRetryRestartsTheSource`, `SessionStateMachineTest.tooManyDeviceErrorsEndTheSessionWithTheDeviceCode` |
| ERR-03 | Unplug and replug | Real SBI source and real engine, with the device service as a test double: the cable is pulled, then plugged back, then Retry; a camera that never comes back | A device error with Retry; the session waits; Retry passes with no attempt used; the other case ends cleanly | Pass. This is simulated. The manual USB check was not done (section 7) | automated | `UnplugReplugIntegrationTest.unplugMidCaptureShowsDeviceErrorWithRetryAndReplugPasses`, `.cameraThatNeverComesBackEndsCleanlyAfterTheDeviceRetryLimit` |
| ERR-04 | Camera unavailable | A camera that throws at once; a runtime error from a working camera; repeated failures | CAMERA_UNAVAILABLE reaches the session; the session ends only after repeated retries | Pass | automated | `MockFrameSourceTest.webcamModeReportsErrorWhenGrabberThrows`, `WebcamGrabbersTest.aRuntimeErrorOfAWorkingCameraReachesTheSessionAsCameraUnavailable`, `EngineScenarioTest.connectionFailureAndCameraUnavailableEndTheSessionOnlyAfterRepeatedRetries` |
| ERR-05 | Camera permission refused on Android | Refused once; refused permanently; on the phone, permission granted afterwards | Device text, recovery text and Close; Open settings as well when permanent | Pass. Phone: Open settings shown; after granting, the next gate passed at 0.9944 | automated and live, 2026-10-24 | `liveness_page_test.dart`: "camera permission refused: device text, recovery text and Close", "camera permission permanently refused: Open settings as well"; `docs/part5-android-integration.md`, section 10 |
| ERR-06 | No face | No face for 8.6 s; a face lost for about a second during a challenge | A no-face hint, then FACE_NOT_DETECTED | Pass | automated | `EngineScenarioTest.noFaceUntilPassiveTimeout`, `ChallengeRunnerTest.faceLostForASecondFails` |
| ERR-07 | Multiple faces | Two faces for 60 frames; a second face during a challenge | A hint, then MULTIPLE_FACES | Pass | automated | `EngineScenarioTest.multipleFacesPersistInPassive`, `.secondFaceAppearsMidChallenge` |
| ERR-08 | Multiple faces, live | Two cut-outs side by side (1 session); a group-photo print (2 sessions); a live face with one cut-out beside it | The first two fail | The first two failed all 4 attempts with MULTIPLE_FACES, hint at about 1.3 s. The live face with a cut-out PASSED on the live face (0.9991, 2.8 s) with no hint. **This is by design:** a face under 20% of the largest face's area counts as background | live, 2026-10-27 | `docs/part2-pad-architecture.md`, section 13; `YuNetDecodeTest.countFacesUsesTheAreaRatioAgainstTheLargestFace` |
| ERR-09 | Poor quality | A too-dark face for 8.6 s | A lighting hint, then POOR_QUALITY | Pass. Live case: DEV-09 | automated | `EngineScenarioTest.poorQualityUntilPassiveTimeout` |
| ERR-10 | Liveness below threshold | Score 0.6 with `activeEnabled` false | LIVENESS_BELOW_THRESHOLD | Pass | automated | `EngineScenarioTest.inconclusiveWithActiveDisabledIsBelowThreshold` |
| ERR-11 | PAD failure | A scripted attack through the fake engine | Every failed attempt maps to the generic message | Pass. Engine case: PAD-01. Text: UIX-02 | automated | `FakeLivenessServiceTest.padFailureOnlyEverMapsToTheGenericMessage` |
| ERR-12 | Challenge failure | The opposite turn; the face box jumps 260 pixels during a challenge | CHALLENGE_FAILED; the audit reason is CONTINUITY_BREAK for the jump | Pass | automated | `EngineScenarioTest.wrongDirectionIsChallengeFailedAndRetryGetsFreshAttempt`, `.faceBoxJumpsMidChallenge` |
| ERR-13 | Challenge timeout | No action for 6.6 s; no frames arriving; a user who keeps looking away | CHALLENGE_TIMEOUT; for the last case the reason is an unstable baseline and the challenge is never issued | Pass | automated | `EngineScenarioTest.doingNothingIsChallengeTimeout`, `ChallengeRunnerTest.timeoutAlsoFiresOnTicksWithoutFrames`, `.unstableBaselineGetsGuidanceThenTimesOut` |
| ERR-14 | Maximum retries | An operator with attacks on all 4 attempts | MAX_RETRIES_EXCEEDED, 4 attempts, last cause PAD_FAILURE, the maximum-attempts message | Pass | automated | `EngineScenarioTest.maxRetriesExceededKeepsLastCauseAndOperatorCooldownTrips` |
| ERR-15 | Invalid frames | Frozen timestamps; an analyzer that rejects 3 frames; repeated, reordered or too-close frames; bad-JPEG reports from the source | INVALID_FRAME at the limit of 3; bad-JPEG reports are not device errors and do not reset the passive window | Pass | automated | `EngineScenarioTest.frozenTimestampsAreInvalidFrames`, `.analyzerRejectingFramesIsInvalidFrame`, `FrameSanityTest.rejectsRepeatedReorderedAndTooCloseFrames`, `SessionStateMachineTest.corruptFramesReportedByTheSourceAreNotDeviceErrors` |
| ERR-16 | Incomplete or corrupt stream data | A corrupt JPEG body with good framing; garbage with no JPEG marker; a declared length of 2,000,000,000 bytes; a cut-off payload | The corrupt frame is skipped with no reconnect; the garbage surfaces an error with no hang; the oversize is refused without allocating; the cut-off payload is refused | Pass. **Known limit:** after garbage with no marker, the next frame is not guaranteed. The test says so | automated | `SbiStreamFrameSourceCorruptFrameTest.aFrameWithARealSoiMarkerButUndecodableBodyIsSkippedWithoutReconnecting`, `.garbageWithNoSoiMarkerAtAllDesyncsFramingButDoesNotHangOrCrash`, `MjpegFrameReaderTest.rejectsOversizedContentLengthWithoutAllocatingIt`, `.rejectsTruncatedPayload` |
| ERR-17 | Engine errors | One analyzer exception; three in a row, twice; closed model sessions; models that do not load; a model integrity failure | One is skipped; three give ENGINE_ERROR with no retry used, and the second time is final; a model integrity failure is final, with no frame analysed | Pass | automated | `Part3RequirementsAuditTest.r17_analyzerExceptionsBecomeEngineError`, `EngineScenarioTest.analyzerCrashesAreEngineErrorWithoutUsingRetriesThenFinal`, `.modelIntegrityFailureAtLoadIsFinal`, `.analyzerThatCannotLoadIsEngineError`, `OnnxFaceAnalyzerTest.runtimeFailuresBecomeEngineErrors` |

### 3.10 Configuration

There are six key families. All keys start with `mosip.registration.liveness.`.

| Family | Keys | Cases |
|---|---|---|
| Policy, per workflow | `face.default.*`, `face.resident.*`, `face.operator.*`, `face.supervisor.*` | CFG-01 to CFG-04 |
| Analyzer | `face.analyzer.*` | CFG-06 |
| Binding | `face.binding.*` | CFG-07 |
| Desktop | `face.desktop.*` | CFG-05 |
| Android | `face.android.*` | CFG-08 |
| Model | `model.*` | CFG-08 |

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| CFG-01 | Per-workflow defaults and lookup order | Empty host config, null host config; a host config with default and workflow keys | Resident 2 challenges and BLOCK, operator 1 and COOLDOWN, supervisor 1; 0.85, 0.30, 6000 ms and 3 retries; a workflow key beats a default key | Pass | automated | `PolicyResolverTest.bundledFileSuppliesPerWorkflowOverridesWhenHostConfigIsEmpty`, `.nullHostConfigUsesBundledDefaults`, `.precedenceMatchesHavingMergedTheBundledFileIntoHostConfig` |
| CFG-02 | A bad config never turns liveness off | `passiveThreshold` 1.5 with `resident.enabled` false; "three" retries; an unknown type; BLINK twice with 2 challenges; window 0, timeout 0, cooldown -1 | The error is reported; the bundled defaults apply; liveness stays on | Pass | automated | `PolicyResolverTest.invalidHostConfigFallsBackToBundledDefaultsAndReportsError`, `.unparsableValuesFallBack`, `.duplicatePoolEntriesCannotSatisfyMinChallenges`, `.extraValidationRejectsImpossibleValues` |
| CFG-03 | Enable and disable per workflow | `supervisor.enabled` false; `operator.enabled` false by synced config on Desktop; the same on Android and on the phone | No session and the legacy flow for that workflow; other workflows stay on and fail closed | Pass. Phone: the gate proceeded as disabled, with no page and no session | automated and live, 2026-10-24 | `DefaultLivenessServiceTest.serviceBasics`, `DesktopLivenessServiceTest.aWorkflowSwitchedOffBySyncedConfigKeepsTheLegacyFlow`, `LivenessServiceHolderTest.aWorkflowCanBeDisabled`; `docs/part5-android-integration.md`, section 10 |
| CFG-04 | A config change reaches the next session only | A reload after a session was created; a Desktop sync; an Android refresh | The running session keeps its values; new sessions use the new ones; Android applies them after the refresh listener runs | Pass | automated | `Part3RequirementsAuditTest.r23_reloadDoesNotAffectRunningSession`, `DesktopLivenessServiceTest.aConfigSyncTakesEffectForTheNextSession`, `LivenessServiceHolderTest.aSyncedValueAppliesAfterTheRefreshListenerRuns` |
| CFG-05 | Desktop keys | Synced values over bundled defaults; blank values; workflow policies in synced config; a server pushing the scripted engine | Synced values win; a blank counts as absent; a config push can never select the scripted engine | Pass | automated | `DesktopLivenessConfigTest.syncedConfigWinsOverBundledDefaults`, `.blankValuesCountAsAbsentSoTheEngineDefaultsApply`, `.workflowPoliciesComeFromTheSyncedGlobalConfig`, `.developmentSwitchesAreReadFromJvmPropertiesOnly`, `.theBundledDefaultsFileIsOnTheClasspath` |
| CFG-06 | Analyzer keys | Every optional key; out-of-range values; print guard settings; "GPU" as the Android accelerator | Keys are read; bad values name the key; Android falls back to its defaults | Pass. **Limit:** on Android one invalid analyzer value resets every analyzer setting to the defaults | automated | `AnalyzerConfigTest.readsEveryOptionalKey`, `.validateRejectsOutOfRangeValues`, `.thePrintGuardsAreOnByDefaultAndCanBeSetOrSwitchedOff`, `LivenessServiceHolderTest.anInvalidAnalyzerConfigFallsBackToTheAndroidDefaults` |
| CFG-07 | Binding keys | `cosineThreshold` 0.363; `identity.threshold` 0.42; "high" and "low"; switches as yes, no, 1, 0, on, off | The two thresholds stay separate; a bad value is refused; the switches read every spelling | Pass | automated | `AnalyzerConfigTest.theBindingThresholdHasItsOwnKey`, `.theIdentityThresholdHasItsOwnKey`, `IdentityBindingTest.bothSwitchesReadYesNoOneZeroOnOffAndKeepTheirDefaultOtherwise` (Desktop and Android) |
| CFG-08 | Android and model keys | Camera `back`, and an invalid `rear`; exposure assist false; update settings; offline install switched off | The camera key is read, and invalid falls back to front; the assist turns off; updates are off by default and daily when on; offline install is hidden and refused | Pass | automated | `LivenessServiceHolderTest.theCameraComesFromTheConfig`, `.theExposureAssistIsOnUnlessSetToFalse`, `ModelUpdatesTest.updatesAreOffByDefaultAndCheckDaily`, `LivenessOfflineModelInstallTest.theConfigSwitchHidesAndRefusesIt` |

### 3.11 Online and offline

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| ONL-01 | Liveness with no network | Phone in airplane mode with Wi-Fi off; cold start; the real and the fake resident gate | Models verify; gates finish; no network errors | Models loaded in 259 ms. Real gate passed, 0.967 in 2.0 s. Fake gate finished as scripted. No network errors in the liveness logs. **Resident gate only** | live, 2026-10-24 | `docs/part5-android-benchmark.md`, section F |
| ONL-02 | No calls home from the runtime | ONNX Runtime telemetry | Switched off; the Android telemetry component is removed | Pass. Phone: the merged manifest no longer has it, and models still load | automated and live, 2026-10-24 | `OnnxModelsTest.telemetryIsSwitchedOffAndCreateStillWorks`; `docs/part5-android-benchmark.md`, section F |
| ONL-03 | A missing or failing network does not hurt a sync | Updates off; updates on with no URL; an update server that answers 404 | No request when off or with no URL; the sync returns in under 1 s; the models stay; the failure is audited | Pass | automated | `DesktopModelUpdateTest.disabledOrWithoutAUrlTheSyncMakesNoNetworkCall`, `.aFailedUpdateNeverFailsTheSyncAndKeepsTheModels` |

### 3.12 Model integrity and updates

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| MOD-01 | The committed bundle verifies | The bundle in the repository with the built-in key | Signature and every SHA-256 (Secure Hash Algorithm) hash verify | Pass. **The key is a development key** and must be replaced before release | automated | `BundledModelsTest.theCommittedBundleVerifiesWithTheBuiltInKey` |
| MOD-02 | Tampered bundle | One byte changed in a model; a wrong size; a missing file; one bit flipped in a PAD model in a real session | Refused and the file named; the session ends with MODEL_INTEGRITY_FAILED; no fallback to unverified models | Pass | automated | `VerifiedBundleTest.sizeAndHashMismatchesAndMissingFiles`, `OnnxFaceAnalyzerFactoryTest.aTamperedSourceFailsOnEveryCreate`, `Part3IntegrationTest.aTamperedBundleEndsTheSessionWithModelIntegrityFailed`, `DesktopModelPreloadTest.aBundleThatDoesNotVerifyIsStillAnIntegrityFailure` |
| MOD-03 | Unsigned bundle or wrong key | An unknown key id; another key; a flipped manifest byte; a garbage signature; a zip with no signature; a forged signature | Refused; the store is unchanged | Pass | automated | `VerifiedBundleTest.unknownKeyIdAndBadSignatures`, `ModelUpdaterTest.aValidZipWithABadSignatureFailsInTheInstall`, `DesktopOfflineModelInstallTest.aTamperedOrUnsignedZipIsRefusedAndTheStoreIsUnchanged`, `LivenessServiceHolderTest.aBundleThatDoesNotVerifyLeavesTheStoreUntouched` |
| MOD-04 | Anti-rollback | The same version; an older version; a tampered zip that claims an older version | "Not newer" and the store unchanged; the tampered zip is invalid, because verification runs before the version check | Pass | automated | `ModelStoreTest.sameOrOlderVersionsAreRejectedAndTheStoreIsUnchanged`, `DesktopOfflineModelInstallTest.aSameOrOlderZipIsRefused`, `.aTamperedZipIsInvalidEvenWhenItsVersionIsNotNewer`, `LivenessOfflineModelInstallTest.aTamperedZipThatClaimsAnOlderVersionIsInvalidNotNotNewer` |
| MOD-05 | Rollback as an explicit action | Rollback with nothing installed; after an install; again | Refused; back to the bundled models, audited with the user id; then nothing earlier | Pass | automated | `ModelStoreTest.rollbackIsAnExplicitOperatorAction`, `DesktopOfflineModelInstallTest.aRollbackReturnsToTheBundledModelsAndThenHasNothingEarlier`, `LivenessServiceHolderTest.rollbackWithNothingInstalledIsRefused` |
| MOD-06 | Offline install: who may do it | Supervisor, administrator, officer; the switch off; a release build with the debug override; no known user | Supervisors and administrators only; everyone else gets NOT_ALLOWED, audited as REFUSED; new sessions use the new models | Pass | automated | `DesktopOfflineModelInstallTest.aSupervisorInstallsANewerZipAndNewSessionsUseIt`, `.onlySupervisorsAndAdminsMayActAndTheConfigCanHideIt`, `LivenessOfflineModelInstallTest.onlySupervisorsAndAdminsMayActAndNobodyBeforeTheAppSaysWho`, `.releaseBuildsIgnoreTheDiagnosticRoleOverride` |
| MOD-07 | Hostile zip files | `../evil.onnx`, an absolute path, extra, duplicate and oversize entries, too many files | Refused; nothing written outside the temporary folder | Pass | automated | `ModelUpdaterTest.anOfflineZipSlipOversizeOrOverlongZipIsRefused`, `.anExtraADuplicateOrAnOversizeEntryIsRefused`, `DesktopOfflineModelInstallTest.aZipSlipEntryIsRefusedAndNothingIsWrittenOutside` |
| MOD-08 | Crashes and disk damage | A pointer switch interrupted by a crash; a half-written temporary folder; a byte flipped on disk | The switch is completed; the leftover is ignored, then cleaned; the damaged bundle fails closed | Pass | automated | `ModelStoreTest.anInterruptedPointerSwitchIsCompleted`, `.leftoverTemporaryDirectoriesAreIgnoredThenCleanedUp`, `.aByteFlippedOnDiskFailsClosed`, `OnnxFaceAnalyzerFactoryTest.aStoreThatFailsVerificationFailsClosed` |
| MOD-09 | Online update | A newer bundle from a local HTTP server after a sync; a wrong hash; a refused connection, a timeout, an HTTP error; plain HTTP; an oversize index; a session running during the swap | INSTALLED with the bundled base kept; every failure has a reason and leaves the store unchanged; the running session keeps its models | Pass | automated | `DesktopModelUpdateTest.anEnabledNewerBundleIsInstalledAfterASyncAndNewSessionsUseIt`, `.aRunningSessionKeepsItsModelsUntilItEnds`, `ModelUpdaterTest.aWrongZipShaFailsAndLeavesTheStoreUnchanged`, `.connectionRefusedATimeoutOrAnHttpErrorFails`, `.plainHttpOnlyToLocalhostAndOnlyWithTheFlag`, `.anOversizeIndexOrZipFails` |
| MOD-10 | Install and rollback on the phone | Debug page: a newer bundle, the same bundle again, a copy with 1 byte changed, Rollback, restarts | Each behaves as designed | Installed `pad-2026.10.2`, reloaded in 158 ms. Same bundle refused, "not newer", tapped 4 times. Tampered copy refused before the store was touched, "sha256 mismatch for face_mesh_v2.onnx". Rollback in 235 ms. Restarts kept the installed state | live, 2026-10-24 | `docs/part5-android-benchmark.md`, section E |
| MOD-11 | Online and offline install on the phone | Online: a laptop server through `adb reverse`, with a good zip, a flipped byte, a manifest edited after signing and a wrong URL. Offline: a zip from Downloads through the system file picker | Good bundle installs; each bad case fails; the store is unchanged | Online: INSTALLED in 1.9 s, then UP_TO_DATE; ZIP_HASH_MISMATCH; BUNDLE_INVALID; HTTP_STATUS 404; store unchanged each time; real gate then passed at 0.9974. Offline: `pad-2026.10.5` in use, `pad-2026.10.8` installed, then rolled back. **No MOSIP update server exists, so updates are off by default. The offline page was opened from a debug build, not from a logged-in client** | live, 2026-10-26 (online), 2026-10-27 (offline) | `docs/part5-android-integration.md`, section 10; `docs/requirements-traceability.md`, the row on secure offline model updates |

### 3.13 Face binding and identity

Face binding compares the live face with the face captured for storage. Identity binding compares the live face with
the user's onboarded face at gates that have only a password. Section 5 has the error rates.

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| BND-01 | The real matcher | A portrait against its mirror; against another person; images with no face or four faces | MATCH; NO_MATCH; NO_FACE and MULTIPLE_FACES for side A or B | Pass | automated | `FaceMatcherTest.thePortraitMatchesItsMirror`, `.anotherPersonDoesNotMatch`, `.eachImageMustHoldExactlyOneFace` |
| BND-02 | Desktop binding accepts only a match, once | A match, then a second capture; another person; never armed; another workflow; expired; undecodable; a matcher exception; a failed session | Accepted once and audited; the second capture is NOT_ARMED; every other path fails closed | Pass | automated | `FaceBindingTest.aMatchingCaptureIsAcceptedOnceAndAudited`, `.anotherPersonIsRejected`, `.everyOtherFailureFailsClosed`, `.aFailedSessionNeverArms` (Desktop) |
| BND-03 | Real ISO/IEC 19794-5 face records | Records made by MOSIP's encoder: the same person, a group photo, garbage | MATCH; MULTIPLE_FACES_B; UNDECODABLE | Pass | automated | `RealFaceBindingTest.theSamePersonMatchesAndAGroupPhotoIsRejected` |
| BND-04 | Android binding completes exactly once | MATCH, NO_MATCH, no face, a decode error, a rejected worker, a timeout with a late result; a frame dropped by time-out or pause | One callback each; only MATCH stores; the late result is dropped; an armed capture with no frame fails closed | Pass | automated | `FaceBindingTest.aCaptureIsCompletedExactlyOnceOnEveryPath`, `.aTimeoutCompletesOnceAndTheLateResultIsDropped`, `.theFrameIsDroppedAfterTheTtlOnANewSessionAndOnPause`, `.anArmedCaptureWhoseFrameWasDroppedFailsClosed` (Android) |
| BND-05 | Identity binding on the real model | The onboarded user; another person; several templates, some unreadable | MATCH, audited with 2-decimal similarity; NO_MATCH and retryable; the best template decides; unreadable ones are counted | Pass | automated | `IdentityBindingTest.theOnboardedUserMatchesOnceAndIsAudited`, `.anotherPersonIsRejectedAndMayRetry` (Desktop), `RealIdentityBindingTest.theOnboardedPersonMatchesAndOthersDoNot`, `IdentityBindingTest.theOnboardedFaceMatchesAndTheFrameIsUsedOnce` (Android) |
| BND-06 | A user with no readable template | No template; unreadable templates; `requireTemplate` true | By default the user gets in on presence alone, audited; with the key on, the user is refused | Pass. **This is a weak path by design:** with the default setting, a user with no onboarded face is not compared with anyone | automated | `IdentityBindingTest.withoutAReadableTemplateTheUserGetsInOnPresenceUnlessTemplatesAreRequired` (Desktop and Android) |
| BND-07 | One retry, then refusal | NO_MATCH, then a new session; a second NO_MATCH; Cancel on the retry question; a required template that is missing | One retry only; the generic text and never a score; a rejection on the first password attempt counts once as a failed login | Pass | automated | `LivenessGateTest.identityBindingAllowsOneRetryAndOnlyWhenItCanHelp`, `IdentityBindingTest.aRejectionOnTheFirstPasswordLoginAttemptCountsAsOneFailedLogin` (Android); `identity_binding_test.dart` |
| BND-08 | A live frame from the wrong workflow | A supervisor's frame for an operator login; an operator's frame for a supervisor approval; an unknown gate | NO_LIVE_FRAME; the frame is dropped | Pass | automated | `IdentityBindingTest.aFrameHeldForAnotherWorkflowIsRefusedAndNoOutcomeLeavesAFrameHeld` (Android) |
| BND-09 | Identity check, live on both clients | Desktop: screen demo on the webcam against the operator's test template. Phone: test template enrolled for s01, then s01 and a second person checked | The right person is accepted; a second person is refused | Desktop: operator MATCH, 0.64; second person NO_MATCH, 0.07; threshold 0.40. Phone: s01 MATCH, then 0.81 after a restart; second person NO_MATCH, and that build did not log the similarity. **Not run in a logged-in client** | live, 2026-10-28 | `docs/part4-desktop-integration.md`, section 10; `docs/part5-android-integration.md`, section 10 |
| BND-10 | Face binding test on the phone | The tester's face in the gate; then a still of the tester, a portrait on a laptop screen, an empty wall | MATCH; NO_MATCH; NO_FACE_B | Tester: MATCH, 0.92. Portrait: NO_MATCH, 0.15. Wall: NO_FACE_B. With the int8 model now in use: MATCH, 0.90. **No real SBI capture was used** | live, 2026-10-25 | `docs/part5-android-integration.md`, section 10 |

### 3.14 UI and text

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| UIX-01 | English texts match the specification | `messages_en.properties`, the built-in catalogue and the block in `docs/ui-liveness-spec.md` | The same keys and the same words | Pass | automated | `LivenessMessagesTest.messagesEnHasEveryKeyWithTheSameText`, `.theSharedSpecBlockIsTheSameCatalogue` |
| UIX-02 | The generic PAD message | A PAD failure beside an engine error; every text in the catalogue | The screen cannot tell the two apart; no tips; no text says score, PAD, model, threshold, attack or spoof | Pass | automated | `LivenessScreenModelTest.padFailureLooksLikeAnyVerificationFailure`, `LivenessMessagesTest.noTextExposesTechnicalDetails`; `liveness_controller_test.dart`: "PAD_FAILURE and ENGINE_ERROR show the generic text and no tips" |
| UIX-03 | Every state on the screen | Every error code in every workflow; a device error with and without Retry | Each has an English text; Retry appears only when it can help | Pass | automated | `LivenessScreenModelTest.everyKeyTheModelCanProduceHasAnEnglishText`, `.deviceErrorRetryDependsOnRecoverable` |
| UIX-04 | Desktop languages | Arabic and French bundles; attempts left at 0, 1 and 3 | Every key, the English placeholders, real plurals | Pass. Desktop has no Hindi, Kannada or Tamil: Registration Client v1.2.0.2 ships no such files | automated | `LivenessMessagesTest.arabicAndFrenchHaveEveryKeyAsInTheSpecAppendixWithTheEnglishPlaceholders`, `.theAttemptsLeftArePluralsInEnglishAndFrench` |
| UIX-05 | Android languages | English, Arabic, French, Hindi, Kannada, Tamil | Every liveness key with its placeholders; the review status recorded; plurals in English and French; every state renders with no layout error | Pass. The five translations were reviewed by native speakers on 2026-10-28 | automated | `liveness_l10n_test.dart`; `liveness_page_locale_test.dart`: "every state renders without a layout error" |
| UIX-06 | Right to left | Arabic on Desktop and Android | Mirrored, except the camera card, and the preview stays a selfie view; the turn arrows keep the user's physical direction | Pass | automated | `LivenessViewLayoutTest.arabicIsMirroredExceptTheCameraCard`; `liveness_page_locale_test.dart`: "Arabic: right to left, and the turn arrows keep the user's physical direction" |
| UIX-07 | Layout at many sizes | Android: every state at 360×640 and 412×915, in Arabic, in Hindi and at 1.3 text scale. Desktop: 720×820, 1280×800, a short screen, 125% to 175% scaling | No overflow; buttons on screen; no text cut short | Pass. The Desktop layout tests skip without a JavaFX toolkit (section 2) | automated | `liveness_page_layout_test.dart`; `LivenessViewLayoutTest.everyStateFitsTheClientWindow`, `.everyStateFitsALargeScreen`, `.theWindowFitsAShortScreenAndEveryStateFitsThere`, `.noTextIsCutShortAtFractionalScaling` |
| UIX-08 | Languages on a real phone | App in Arabic, Hindi and Tamil; fake gates with a placeholder preview | Every text renders with no missing glyphs | As expected. Arabic is right to left, and TURN_RIGHT keeps its arrow. No camera was opened | live, 2026-10-26 | `docs/part5-android-integration.md`, section 10 |

### 3.15 Audit and privacy

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| AUD-01 | Images cannot reach the audit trail or the result panel | Reflection over the audit API; a failed session on the real service; the capture audit; the session summary; the demo result panel | No method accepts an image or landmarks; only known keys and short values; no image on screen | Pass | automated | `AuditRecorderTest.publicApiCannotAcceptImagesOrLandmarks`, `Part3RequirementsAuditTest.r24_r25_r26_auditOnTheRealService`, `FaceBindingTest.noImageReachesTheAudit` (Desktop), `LivenessMapsTest.describeNeverContainsTheImage`; `liveness_demo_page_test.dart`: "the result panel never shows an image, the best frame included" |
| AUD-02 | Audit values are cleaned | A base64 blob as a value, bad keys, a newline injection; more than 20 PAD keys | Dropped or cleaned; at most 8 PAD keys, scores only | Pass | automated | `DesktopAuditSinkTest.dropsAnythingThatLooksLikeImageDataOrBadKeys`, `AuditRecorderTest.padDetailsAreScoresOnlyCappedAndSanitised` |
| AUD-03 | Audit failures and event mapping | An audit sink that throws; every engine event | The session is not affected; each event has its own audit event on both clients | Pass | automated | `AuditRecorderTest.failingSinkNeverPropagates`, `DesktopAuditSinkTest.anAuditFailureNeverReachesTheEngine`, `.everyEngineEventHasItsOwnDesktopEvent`, `AndroidAuditSinkTest.auditFailuresNeverReachTheEngine`, `.everyEngineEventHasItsOwnAuditEventInTheFreeBlock` |
| AUD-04 | Metrics are anonymous | Stored keys and values; on the phone, the stored counts against the session logs | Counts and averages only; no session ids or times | Pass. Phone: 10 sessions across 7 app processes matched the logs one for one | automated and live, 2026-10-24 | `AuditRecorderTest.metricsAreAnonymousCountsAndAverages`, `LivenessMetricsStoreTest.storesNoIdentifiersOrTimes`; `docs/part5-android-benchmark.md`, section C |
| AUD-05 | Diagnostic mode | Off, then on | Off: no `diag.*` keys. On: per-attempt statistics only | Pass | automated | `AuditRecorderTest.diagnosticModeAddsPerAttemptStatisticsAndNothingElse` |
| AUD-06 | The recording tools protect people | No consent flag; a name as the subject; a test template outside `eval-data/`; the evaluation report | Refused, with nothing written; a code, never a name; the report holds aggregates only | Pass | automated | `DatasetRecorderTest.refusesWithoutConsent`, `DatasetSessionTest.theSubjectCodeIsRequiredAndIsACodeNotAName`, `LivenessScreenDemoTest.theTestTemplateStaysUnderEvalData`, `PadEvaluationTest.reportHasAggregatesOnlyAndMarksMissingSpecies` |

### 3.16 Performance

| ID | What is tested | Input or steps | Expected result | Actual result | Type | Evidence |
|---|---|---|---|---|---|---|
| PRF-01 | Time per frame on the laptop | 20 warm-up and 300 timed calls, 2 threads | Fast enough for 15 frames per second | Median 18.0 ms, 95th percentile 21.9 ms. Predates face binding | evaluation, 2026-10-23 | `liveness-eval/reports/benchmark-20261023.md` |
| PRF-02 | Time per frame on the phone, and accelerator parity | 60 captured frames, profile build, CPU, XNNPACK and NNAPI | Fast enough, and each provider gives the reference score | CPU with 2 threads: median 26.6 ms, 95th percentile 32.3 ms. Every provider matched the reference (difference below 0.00005). XNNPACK about 2 times slower, NNAPI about 4 times slower | evaluation, 2026-10-24 | `docs/part5-android-benchmark.md`, section A; `OnnxModelsAcceleratorTest.nnapiOnTheDesktopFallsBackToCpu` |
| PRF-03 | Real sessions, memory and size on the phone | Five sessions, resident, front camera; `dumpsys meminfo`; split APK (Android Package) builds | About 8 analysed frames per second in the passive check; memory and size recorded | 42 to 57 ms per frame at the median. 8.0 frames per second passive, 11.9 in the session with challenges, 5.9 in the dark session. 236 MB idle, 367 MB peak in a session. arm64 split APK 151.3 MiB | evaluation, 2026-10-24 (sessions, memory) and 2026-10-25 (split size) | `docs/part5-android-benchmark.md`, "Real sessions" and section G |

## 4. Presentation attack evaluation (ISO/IEC 30107-3)

**Everything in this section is provisional.** It uses four real people, one laptop webcam (640 × 480) and one room.
The attack instruments were one phone, one tablet and one printer. The results show tendencies and failure modes. They
do not estimate field error rates, and this is not a conformance claim.

**Recordings of people are not published.** A face recording is biometric data. Each participant agreed that their
recordings stay on the recording laptop, are never committed or uploaded, and can be deleted on request, and that only
aggregate numbers are reported. The numbers below are those aggregates.

### 4.1 Terms

| Term | Meaning |
|---|---|
| Presentation | One take shown to the camera: normally 6 seconds, 60 frames at 10 frames per second. For the many-identity species it is one photo's run in a continuous recording, 12 to 163 frames |
| APCER (Attack Presentation Classification Error Rate) | For one species: attack presentations classed as bona fide, divided by attack presentations of that species that gave a decision |
| Maximum APCER | The highest APCER over all species. This is the headline security figure |
| BPCER (Bona Fide Presentation Classification Error Rate) | Bona fide presentations classed as attacks, divided by bona fide presentations that gave a decision |
| Non-response | A presentation with no decision, because no full window of 10 usable frames formed. It is reported apart, and it is never an acceptance. The rates are APNRR (Attack Presentation Non-Response Rate) and BPNRR (Bona Fide Presentation Non-Response Rate) |
| [≤ x%] | The one-sided 95% Clopper-Pearson upper bound. For example 0/10 [≤ 25.9%] means that with 10 samples the true rate is probably below about 26%. Read every 0/N through its bound |

**How a take is decided.** Each take gets a fresh analyzer, as each session does. The score is the median of the first
full window, which is the value production decides on. Then:

| Score | Zone | For a real face | For an attack |
|---|---|---|---|
| 0.85 or above | Passive pass | Normal | **Security failure** |
| 0.30 to below 0.85 | Challenge | Costs a few seconds | Must still beat a random challenge and the PAD monitor |
| Below 0.30 | PAD reject | **Usability failure** | Normal |

**What the APCER below does and does not count.** It counts passive passes only. Challenges are not part of it,
because a recording cannot answer a random prompt. An attack in the challenge zone has not been shown to fail in this
evaluation. It has only been shown not to pass without a challenge. The live sessions in sections 4.5 and 4.6 add some
evidence on this.

**BPCER has two readings.** The evaluation report's candidate table uses BPCER at 0.85: the share of real faces that
did not pass without a challenge, 4/36 (11.1%). The security-relevant reading is BPCER at the reject threshold of 0.30:
real faces rejected as attacks, 0/36 [≤ 8.0%]. Both are given below, with the threshold named.

### 4.2 Dataset of the run of 2026-10-28

178 takes and 9,098 frames, model bundle `pad-2026.10.5`, production settings with the print guards on.

| Class | Takes | People or codes | Frames |
|---|---|---|---|
| Bona fide | 47 | 4 (s01 to s04) | 2,820 |
| `screen_photo` | 6 | 1 | 360 |
| `screen_photo_close` | 12 | 1 | 720 |
| `screen_replay` | 6 | 1 | 360 |
| `screen_replay_monitor` | 12 | 1 | 720 |
| `print_flat` | 6 | 1 | 360 |
| `print_curved` | 6 | 1 | 360 |
| `screen_photo_multi` | 38 | 14 | 1,050 |
| `print_multi` | 45 | 13 | 2,348 |

Qualifiers:
- There are 30 subject codes in all, attack takes included. One person can have two codes, so the codes are not a
  count of people.
- s01 has 11 bona fide takes, because the dim take was skipped. s02, s03 and s04 have 12 each.
- The single-species attacks show s01 or a public-domain portrait.
- Decision rules for this run were fixed before the three new subjects' data was recorded, and were applied as written.

### 4.3 The attack species in words

| Species | What is shown to the camera |
|---|---|
| `screen_photo` | A photo on a phone at normal distance, with the phone's edges visible |
| `screen_photo_close` | A photo on a phone held close, including about 10 cm away with the face at 20 to 31% of the picture |
| `screen_replay` | The subject's selfie video playing on a phone |
| `screen_replay_monitor` | The subject's 2-minute selfie video on a tablet of about 11 inches. **Despite the name, no desktop monitor was tested** |
| `print_flat` | A colour A4 print held flat |
| `print_curved` | The same print, bent |
| `screen_photo_multi` | A phone slideshow of photos of 14 consented people |
| `print_multi` | Printed cut-outs of 13 people, held about 20 cm away, flat and curved |

### 4.4 Results per species at passive 0.85 and reject 0.30

| Species | Takes | Gave a decision | APCER at 0.85 | Challenge | PAD reject | Non-response | PAD monitor below 0.30 at some point |
|---|---|---|---|---|---|---|---|
| `screen_photo` | 6 | 0 | no decision, so no APCER | 0 | 0 | 6 | 6 |
| `screen_photo_close` | 12 | 10 | 0/10 [≤ 25.9%] | 6 | 4 | 2 | 6 |
| `screen_replay` | 6 | 6 | 0/6 [≤ 39.3%] | 0 | 6 | 0 | 6 |
| `screen_replay_monitor` | 12 | 7 | 0/7 [≤ 34.8%] | 5 | 2 | 5 | 11 |
| `print_flat` | 6 | 6 | 0/6 [≤ 39.3%] | 1 | 5 | 0 | 6 |
| `print_curved` | 6 | 6 | 0/6 [≤ 39.3%] | 0 | 6 | 0 | 6 |
| `screen_photo_multi` | 38 | 8 | 0/8 [≤ 31.2%] | 0 | 8 | 30 | 29 |
| `print_multi` | 45 | 28 | 0/28 [≤ 10.1%] | 9 | 19 | 17 | 34 |

- **Maximum APCER at 0.85: 0.** The evaluation report's headline species is `screen_photo_close`, 0/10 [≤ 25.9%]. The
  widest bound in the table is 0/6 [≤ 39.3%].
- **Attack non-response (APNRR): 60/131 (45.8%).** Most of these faces were too small to judge. In production that is a
  timeout with a "move closer" hint, never an acceptance. An attacker who brings the instrument closer turns a
  non-response into a judged attempt.
- **The margins are thin.**
  - The highest window median of a cut-out was 0.82, which is 0.03 below the threshold.
  - The moiré cap's separation on s01's close-ups was 1.37 for real faces against 1.435 for attacks.
  - At a threshold of 0.60, `screen_photo_close` APCER is 6/10 (60.0%), because the cap holds those takes at exactly
    0.60. They then rely on the challenge.

### 4.5 Print guards

Two session guards were added after a printed cut-out passed live (PAD-08). Each caps the score at 0.60. A cap forces
a challenge and never causes a rejection.
- **PAD latch:** once a 3-frame median falls below 0.30, the cap holds for the rest of the session, across retries.
- **Volatility cap:** the cap applies while the second-lowest of the last 10 model scores is below 0.85.

| Check | Result |
|---|---|
| Replay of the attempt that passed, 0.944 | Goes to a challenge at 0.600, with both guards or either one |
| Recorded takes, 130 at that date | Real faces: 7 to 6 passive passes, 0 to 0 rejections. All 119 attack takes: 0 passive passes before and after |
| Live attack sessions, s01's prints and cut-outs | 0 of 29 passed. 1 of 63 attempts that reached a window would have passed without the guards, at 0.923 |
| Live real-face sessions, s01 | 10 sessions: 6 passive passes, 3 passed a challenge, 1 timed out, 0 PAD rejections |
| Cost over four subjects, recorded | 2 of 34 real-face passive passes (5.9%) became challenges: s01 1/7, s02 1/9, s03 0/8, s04 0/10. At or below the 25% limit set in advance, so the volatility cap stays at 0.85 |
| Attack replay, 295 attempts, at 0.85 and at 0.80 | 0 passes at both. No verdict changes |

**Limits.** The guards were tuned on the cut-out that passed. The latch lasts one session, so a new session starts
clean. The operator cooldown limits that; the resident and supervisor workflows have no cooldown by default. Rotate and tilt tricks against
challenges were not exercised, because PAD stopped those cut-outs first.

### 4.6 Tablet replay

One subject (s01), one tablet of about 11 inches at maximum brightness, one 2-minute video.

| Run | Distance | Recorded takes | Result on takes | Live sessions | Result live |
|---|---|---|---|---|---|
| 2026-10-27 | 40 to 60 cm, face about 3.4% of the picture | 4 | All non-responses, face too small | 6 | 0 of 6 passed. Every attempt that reported a code ended in PAD_FAILURE |
| 2026-10-28 | Real-face size, face 20 to 35% of the picture | 8 | 7 gave a decision: 0/7 [≤ 34.8%], 5 challenges, 2 PAD rejects, 1 non-response | 2 | 0 of 2 passed, both MAX_RETRIES_EXCEEDED |

All 12 takes: 0/12 passive passes [≤ 22.1%], 5 challenges, 2 PAD rejects, 5 non-responses.

- **The models were often fooled.** At real-face size, the median per-frame score before the guards was 0.83 in four of
  the retake's takes. In one uncounted run at 12% face size, the models scored the replay as live (medians 1.00 and 0.99), and the
  volatility cap held it at 0.60. The guards and the PAD monitor did the work.
- **The moiré cap decided nothing.** The per-take moiré medians were 1.03 to 1.19, below its 1.40.
- **Three more runs that day are not counted.** The tablet was held too far away. All three failed.
- **Not covered:** a desktop monitor, other tablets and phones, other panel types, and a face over 35% of the picture.

### 4.7 Real faces

| Subject | Takes | Passive pass | Challenge | PAD reject (BPCER at 0.30) | Non-response |
|---|---|---|---|---|---|
| s01 | 11 | 6/8 | 2/8 | 0/8 | 3 |
| s02 | 12 | 8/10 | 2/10 | 0/10 | 2 |
| s03 | 12 | 8/8 | 0/8 | 0/8 | 4 |
| s04 | 12 | 10/10 | 0/10 | 0/10 | 2 |
| **All** | **47** | **32/36 (88.9%)** | **4/36 (11.1%)** | **0/36 [≤ 8.0%]** | **11/47 (23.4%)** |

- **BPCER at 0.85 is 4/36 (11.1%):** 4 real faces needed a challenge. **BPCER at 0.30 is 0/36 [≤ 8.0%]:** none was
  rejected.
- **The moiré cap and the guards moved 2 of 36 responding takes (5.6%)** from a passive pass to a challenge, and
  rejected none.
- **Bona fide non-response (BPNRR): 11/47 (23.4%).** By condition: far 6/8, moving 3/8, close 2/8, and normal 0/12,
  close with the whole face 0/8, dim 0/3. Face size caused 8 of them. Live, a small face gets a "move closer" hint. The
  recordings gave no feedback to the person.
- **The moiré cap fires far more often on the new subjects.** Share of scored real-face frames capped: s01 12/581
  (2.1%), s02 116/675 (17.2%), s03 122/676 (18.0%), s04 97/656 (14.8%). The rule set in advance flags s02, s03 and s04
  (above 10%). Its threshold came from s01's data. The cap was not changed.

### 4.8 Thresholds in use

| Setting | Value | Effect |
|---|---|---|
| Passive threshold | 0.85 | Median of a window at or above this passes with no challenge |
| PAD reject threshold | 0.30 | Median below this is PAD_FAILURE, with no challenge |
| Passive window | 10 usable frames | One face, no quality issue, a score, yaw and pitch within 25 degrees |
| PAD monitor | 3-frame rolling median below 0.30 | Ends the attempt during a challenge |
| Moiré cap | Rolling median of the last 10 values at or above 1.40, from at least 3 values | Score capped at 0.60 |
| PAD latch | 3-frame median below 0.30 | Score capped at 0.60 for the rest of the session |
| Volatility cap | Second-lowest of the last 10 model scores below 0.85 | Score capped at 0.60 |
| Face binding | Cosine similarity 0.45 | Below this the capture is rejected |
| Identity binding | Cosine similarity 0.40, provisional | Below this the live face is not the onboarded user |

The threshold rule, run on this data, gives 0.67 and 0.50. **That output was reported and not adopted.** The caps pin
its passive value. The defaults stay until there are at least 10 bona fide subjects.

## 5. Face binding results

**The two checks.**
- **Face binding** runs after liveness passes. It compares the live best frame with the face captured for storage.
  Threshold 0.45. Anything but a match rejects the capture.
- **Identity binding** runs at gates that have only a password. It compares the live best frame with the user's
  onboarded face. Threshold 0.40, provisional. It is a second factor after the password. A false match needs the
  password as well as a similar face.

**The two error rates.**
- **FNMR (false non-match rate):** the share of same-person pairs below the threshold. The right person is refused.
- **FMR (false match rate):** the share of different-person pairs at or above the threshold. Someone else is accepted.

**Source.** The run of 2026-10-28 with four subjects: the SFace int8 model in bundle `pad-2026.10.5`, cosine
similarity, aggregates only. It also used 42 labelled photos of 15 people, from other days and cameras, with labels
confirmed by the photos' owner. All figures are provisional. The face binding rows come from the local aggregate report
of that run, which is not published with the recordings. The identity figures are also in
`docs/part2-pad-architecture.md`, section 12.1.

### 5.1 Same-person pairs (false non-match)

| Pairs | Number | FNMR at 0.40 | FNMR at 0.45 | Lowest | Median |
|---|---|---|---|---|---|
| Webcam take against webcam take of the same person | 253 | 11/253 (4.3%) | 12/253 (4.7%) | 0.271 | 0.741 |
| Enrolment take against the same person's other takes, same session | 43 | 1/43 (2.3%) | 1/43 (2.3%) | 0.372 | 0.767 |
| **A photo from another day and camera against the person's webcam takes** | 155 | **42/155 (27.1%)** | **59/155 (38.1%)** | 0.235 | 0.532 |
| Photo against photo of the same person | 62 | 6/62 (9.7%) | 15/62 (24.2%) | 0.305 | 0.548 |
| s01 against screen photos and prints of s01's selfie (only s01 has such takes) | 165 | 11/165 (6.7%) | 13/165 (7.9%) | 0.268 | 0.575 |

### 5.2 Different-person pairs (false match)

| Pairs | Number | FMR at 0.40 | FMR at 0.45 | Highest |
|---|---|---|---|---|
| **Webcam against webcam, different subjects, same room** | 828 | **45/828 (5.4%)** | **13/828 (1.6%)** | 0.493 |
| Enrolment take against other subjects' takes, same room | 141 | 5/141 (3.5%) | 0/141 (0.0%) | 0.447 |
| Labelled photos of different people | 799 | 23/799 (2.9%) | 5/799 (0.6%) | 0.503 |
| A labelled photo against other people's webcam takes | 1,819 | 8/1,819 (0.4%) | 1/1,819 (0.1%) | 0.452 |
| A subject against photos of another subject's selfie | 540 | 0/540 | 0/540 | 0.361 |
| Subjects against screen photos and prints of public-domain portraits | 705 | 0/705 | 0/705 | 0.198 |
| Labelled photos against public-domain portraits | 2,100 | 0/2,100 | 0/2,100 | 0.323 |
| Subjects against public portraits | 2,303 | 0/2,303 | 0/2,303 | 0.304 |

### 5.3 Reading these numbers

**Face binding, threshold 0.45.**
- The pair closest to real use is webcam against webcam: FNMR 12/253 (4.7%), FMR 13/828 (1.6%).
- **The highest impostor score, 0.493, is above the threshold.** The earlier single-subject evaluation found no
  impostor above 0.364. That result does not hold with four subjects.
- **Binding is not attack detection.** 152 of 165 pairs of s01 against a photo or print of s01 still match at
  0.45. Binding must run after liveness, never in its place.
- No real SBI capture was tested. A lower-quality device image would lower the same-person scores.

**Identity binding, threshold 0.40.**
- With a template from the same session and camera, FNMR is 1/43 (2.3%).
- **With a single photo from another day and camera as the template, FNMR is 42/155 (27.1%).** Per subject: s01 0/11,
  s02 4/24 (16.7%), s03 18/36 (50.0%), s04 20/84 (23.8%).
- 4 of the 13 photos cause 34 of those 42 false non-matches. Reasons include a pose of 13 and 18 degrees, a tight crop,
  and a dark, small face. Six of the 13 photos had no false non-match.
- With the best of a person's photos, FNMR is 2/47 (4.3%). **The FMR cost of best-of-several matching is not measured.**
- The false match figure most relevant for security is 5/141 (3.5%), highest 0.447, for a webcam enrolment against
  other people's webcam takes in the same room. All labelled impostors together: 31/2,618 (1.2%), highest 0.503.
- **The threshold rule found no value.** Fixed in advance, it asks for the lowest labelled-impostor FMR with FNMR of at
  most 5% on the photo-template pairs. From 0.36 to 0.50, no value qualified, so 0.40 stays. At 0.36 the FNMR is still
  20/155 (12.9%).
- **No real onboarding template was tested.** Neither pair set represents one. The phone never read a stored template,
  because nobody can onboard without a backend. Whether a real bio-SDK (software development kit) template decodes was
  not checked.

## 6. Performance

### 6.1 Desktop

**Device.** Development laptop: AMD Ryzen AI 7 350, 16 logical cores, Windows 11, Java Development Kit (JDK) 11, ONNX
Runtime 1.31.0 on the CPU. Input: one golden portrait of 554 × 640 pixels. 20 warm-up and 300 timed calls per setting.
Date 2026-10-23. **These figures predate face binding.**

| Threads | Preload (verify and build sessions) | First `analyze()` | Total median | Total 95th percentile | Total 99th percentile |
|---|---|---|---|---|---|
| 1 | 1064.6 ms (fresh Java process) | 63.4 ms | 18.14 ms | 23.86 ms | 27.80 ms |
| 2 (default) | 174.6 ms | 23.3 ms | 18.03 ms | 21.86 ms | 24.41 ms |
| 4 | 226.5 ms | 40.8 ms | 18.24 ms | 21.40 ms | 28.62 ms |

At 2 threads, median per stage: detection 4.88 ms, landmarks 7.50 ms, both PAD models 4.86 ms, cues 0.55 ms, quality
0.23 ms, pose 0.01 ms.

Face matching on the same laptop, with the int8 model. It runs once per capture, not per frame.

| Measure | Earlier three-run measurement | Three reports of 2026-10-28 |
|---|---|---|
| Preload in a fresh Java process | 1.6 to 2.1 s | 2.0 to 2.5 s |
| Comparison of two images, warm | 404 to 422 ms | 423.7 to 483.4 ms (mean of 20) |
| One face, warm | 204 to 211 ms | 216.0 to 237.4 ms (mean of 20) |

**Not measured on Desktop:** a full client session, client memory use, and an installer size. The signed model bundle
is 17.7 MiB. The Desktop client was not launched (section 8.3).

### 6.2 Android

**Device.** One Samsung Galaxy S23 FE (SM-S711B), Exynos 2200, 8 GB memory, Android 16, onnxruntime-android 1.31.0.
Profile build, front camera, 640 × 480 analysis stream. One tester. Date 2026-10-24, on the four-model bundle
`pad-2026.10.1`, except where stated. **This is a mid-to-high-end phone. Do not assume these times on a low-end device.**

Time per frame, benchmark of 60 captured frames, median and 95th percentile:

| Setting | Total | Matches CPU with 1 thread |
|---|---|---|
| CPU, 1 thread | 27.3 / 29.1 ms | reference |
| **CPU, 2 threads (default)** | **26.6 / 32.3 ms** | yes |
| CPU, 4 threads | 56.1 / 82.1 ms | yes |
| XNNPACK, 1, 2, 4 threads | 53.7 / 58.0, 58.0 / 68.5, 144.9 / 200.5 ms | yes |
| NNAPI (Android Neural Networks API), 1, 2, 4 threads | 124.3 / 130.9, 113.4 / 120.8, 113.6 / 130.5 ms | yes |

Default setting per stage, median: detection 10.7 ms, landmarks 8.9 ms, both PAD models 5.6 ms, cues 0.7 ms, quality
0.4 ms.

Real sessions with the real engine, resident workflow:

| Session | Frames analysed | Time | Rate | Per frame, median / 95th percentile | Result |
|---|---|---|---|---|---|
| Normal light | 10 | 1.1 s | 8.0 per second | 57.5 / 73.5 ms | Passed, no challenge |
| Dim light, exposure assist off | 60 | 5.0 s | 11.9 per second | 42.1 / 63.7 ms | Passed after 2 challenges |
| Dark, exposure assist off | 108 | 18.1 s | 5.9 per second | 49.2 / 77.0 ms | Cancelled by the tester |
| Dark, exposure assist on | 45 | 5.5 s | 8.0 per second | 52.9 / 66.1 ms | Passed, no challenge |
| First session of a new app process | 12 | 1.3 s | 8.3 per second | 46.0 / 218.7 ms | Passed, no challenge |

Memory, whole app, measured as proportional set size (PSS):

| State | Total | Native heap |
|---|---|---|
| Idle, models loaded | about 236 MB | about 60 MB |
| During a real session, peak | 367 MB | 88 MB |
| During the benchmark, 60 frames in memory | up to 490 MB | up to 155 MB |

Other Android figures:
- **Model loading at app start:** 140 to 266 ms over 7 cold starts.
- **Camera conversion:** 3.3 to 4.5 ms per frame, 3.9 to 6.4% of one core.
- **Face matching with the int8 model, debug build:** one face 36.8 ms, warm comparison 76 ms.
- **INT8 versions of the liveness models were measured and rejected.** Total per frame rose from 26.6 to 40.4 ms on
  CPU with 2 threads, and scores moved by up to 0.24.

Application size:

| Build | Size |
|---|---|
| arm64-v8a split APK, five-model bundle | 158,607,245 B (151.3 MiB) |
| armeabi-v7a split APK | 145,315,101 B (138.6 MiB) |
| x86_64 split APK | 167,078,781 B (159.3 MiB) |
| Single APK aimed at arm64, four-model bundle | 256.1 MiB |
| Debug APK, all processor types | 431.3 MiB |

ONNX Runtime is 32.6 MiB of the arm64 split. A reduced runtime was not built.

## 7. Stability and recovery checks

| Check | What was done | Result | Evidence |
|---|---|---|---|
| Unplug and replug, simulated | Real SBI source and real engine, device service as a test double (ERR-03) | Pass. This test found a real engine bug: a frame left over from before the unplug swallowed the user's Retry. It is fixed | `UnplugReplugIntegrationTest`; `PART3_STATUS.md`, section 7 |
| Unplug and replug, real USB | Unplug a USB camera during capture | **Not done.** The manual check is open | `docs/part1-qa-checklist.md` |
| Stopping MockMDS during capture | Manual check | **Not done.** The manual check is open | `docs/part1-qa-checklist.md` |
| Camera restarts, soak | `CaptureSoak`, capture plus the analyzer, a restart every 10 s, 2026-10-27 | **sarxos, the first capture library, died with a native heap corruption (0xC0000374) after about 25 restarts.** OpenCV ran 89 restarts with no error, and is now the tools' default | `docs/part2-dataset-protocol.md` |
| Continuous capture, soak | 30 minutes, 2026-10-27 | Clean on both libraries: sarxos 14.9, OpenCV 14.8 frames per second | `docs/part2-dataset-protocol.md` |
| Clean exit of the development tools | A close that hangs; a hung shutdown; two live runs and a delete on 2026-10-28 | Tests pass. Live runs exited with no hang. Which library ran was not captured | `WebcamGrabbersTest.aHungShutdownIsHaltedAfterTheGracePeriod`; `docs/part4-desktop-integration.md`, section 10 |
| Repeated sessions on a phone | 10 sessions across 7 app processes, 2026-10-24 | Stored counts matched the session logs one for one; 0 final failures, 1 cancelled | `docs/part5-android-benchmark.md`, section C |
| Repeated cold starts | 7 cold starts, 2026-10-24 | Models loaded in 140 to 266 ms each | `docs/part5-android-benchmark.md`, "Real sessions" |
| Camera release on a phone | Cancel and back during challenges, 2026-10-24 | CANCELLED; camera released each time, in the camera service log | `docs/part5-android-integration.md`, section 10 |
| Restart keeps model state | Install, restart, rollback, restart, 2026-10-24 and 2026-10-26 | The new process loaded the right bundle each time | `docs/part5-android-benchmark.md`, section E; `docs/part5-android-integration.md`, section 10 |
| Races | 1,000 rounds of racing emitters; 20,000 frames through the frame pump; 100 rounds of racing cancels | One final result each time; no frame analysed twice | `EventDispatcherTest.racingEmittersAndCompletersDeliverExactlyOneFinalAndNothingAfter`, `FramePumpTest.fastProducerSlowConsumerNeverAnalysesAFrameTwiceAndLosesNothingUnaccounted`, `FakeLivenessServiceTest.cancelRacingFromOtherThreadsAlwaysYieldsExactlyOneResult` |
| Random input | 60 runs of up to 2,500 random frames, device errors and analyzer failures across the workflows | Invariants hold | `EngineScenarioTest.fuzzRandomAnalysesKeepInvariants` |
| A cancel during a device restart | The cancel lands between the restart's stop and start | The source ends stopped. This test failed before the fix of 2026-10-28, when the camera stayed on after the session | `EngineScenarioTest.aCancelBetweenADeviceRestartsStopAndStartLeavesTheSourceStopped` |
| Regression clips | Each attack clip played three times before it was committed | Same failure code in 3 of 3 runs | `docs/part1-test-data-protocol.md`, "Bundled regression clips" |
| Memory | Peak on the phone (section 6.2) | 367 MB in a session | `docs/part5-android-benchmark.md`, "Real sessions" |

**Not done:**
- no soak of full liveness sessions on Desktop or on a phone;
- no measurement of memory growth over repeated sessions;
- no memory measurement of the Desktop client.

## 8. Known failures and limits

### 8.1 Tests that fail upstream

The Android task `:clientmanager:testDebugUnitTest` has **14 failing tests in upstream v1.1.1. They are not ours:**
- 9 in `Biometrics095ServiceTest`;
- 2 in `RegistrationServiceImplTest` (GPS);
- 3 in `UserOnboardServiceTest`, only in the full run.

They fail identically on the unmodified baseline, v1.1.1 plus the build fix (commit `f591f41`), before any liveness
change. Source: `docs/part5-android-integration.md`, section 10. The clientmanager classes this work added or changed,
`GlobalParamRepositoryRefreshListenerTest` and `SessionManagerTest`, are recorded as passing in the same section. This
task is outside the table in section 2, which covers the liveness suites only.

### 8.2 Failures found during testing

| Failure | Status |
|---|---|
| A printed cut-out at real-face size passed live, twice (PAD-08) | Print guards added. 0 of 29 sessions since. The margin stays thin |
| Close-up phone photos passed in 4 of 12 takes | Moiré cap added. 0 of 12 since. It is the only screen cue. Three prototypes of a second cue gave negative results, and fine-tuning the models on the four-subject data was not adopted |
| A tilted phone photo passed BLINK and SMILE once | Pose-drift guard added (ACT-07) |
| 0 of 9 turns passed with the product prompt | Turn rule changed (ACT-08). Reading "turn" as "tilt" is still open (ACT-10) |
| The camera took the display's rotation, not the app's. Faces arrived sideways on 2026-10-28 | Fixed and unit tested. **The original mismatch did not recur in the live checks.** The platform behaviour was not reproduced by a test (DEV-08) |
| A frame left over from before an unplug swallowed Retry | Fixed |
| A cancel during a device restart left the camera on | Fixed |
| sarxos crashed after about 25 camera restarts | Tools moved to OpenCV. sarxos remains a fallback |

### 8.3 Never tested

- **Real L0/L1 SBI hardware.** L0 and L1 are MOSIP's device trust levels. All device tests used a mock device or
  MockMDS. No vendor device was used. Every manual hardware check in `docs/part1-qa-checklist.md` is open.
- **A live MOSIP backend end to end.** Neither client ran against one.
  - The Desktop client was compiled and its tests run, but it was **not launched**. It needs a registered machine, a
    database and a server. The hooks were checked by compilation, code review and rendered screens.
  - The Android app stops at the Login page with no backend. The five hooks ran through the debug page's gate buttons
    (RES-05), widget tests and code review, not inside the real screens.
  - No SBI capture was made on Android.
  - The identity check and the production model page were not tried in a logged-in client.
- **Low-end phones.** Only one mid-to-high-end phone was tested. The times and the 367 MB peak may not hold elsewhere.
- **Replay on a desktop monitor.** Only one tablet. No other screens.
- **Masks.** No masks, 3D attacks, partial attacks or deepfake replays.
- **Digital injection.** Virtual cameras and stream tampering are out of scope for PAD and were not tested.
- **An independent laboratory.** ISO/IEC 30107 conformance needs an accredited test, more subjects and more species.
  This work makes no conformance claim.

### 8.4 Tested only in part

- **Android attack coverage.** The only attack result recorded on a phone is a face photo on a laptop screen (PAD-12). Prints and replays
  were evaluated on the laptop webcam only.
- **Android frames come from the phone camera,** not the L0/L1 device. Android SBI has no stream. Face binding links
  the SBI capture to the live face.
- **Offline.** Airplane mode was run on Android with the resident gate only. The Desktop client was not run offline.
- **Online model update.** Tested against a local server and a laptop server. MOSIP has no update server for these
  bundles, so the feature is off by default.
- **Operator cooldown.** It is in memory and resets when the app restarts.
- **Signing key.** The model bundle is signed with a development key.
- **Screen readers.** Desktop notifications were not tried with Narrator or NVDA.
- **ISO/IEC 19794-5 face image quality.** No part measures uneven lighting, background or glasses glare.
- **MockMDS webcam mode.** It compiles but was not run with a camera.
- **Translations on Desktop.** English, Arabic and French only.

### 8.5 The small sample

- **Four bona fide subjects,** not the 10 to 20 the thresholds wait for. It is not a demographic sample.
- **One webcam, one room, one phone, one tablet, one printer and one paper.**
- **The single-species attacks show one subject or a public-domain portrait.** The attacks were made by the subject,
  who knew the system, holding the instrument by hand. That is low attack effort.
- **Many cases rest on s01 alone,** including every live session before the four-subject run and every phone result
  marked s01.
- **0/N is not zero.** 0/6 means at most about 39%.

### 8.6 Live regression status

**No full live regression on the final state is recorded.** That means no single run of every workflow and attack on
this repository at `57b2624` with client commit `4d5e6b9`.

The live evidence here is a set of dated checks, each on the build of its day:
- The last live check on the phone was rotation, on 2026-10-29, on the debug build from client commit `4d5e6b9`. That
  is the APK in the submission pack.
- The last live identity checks were on 2026-10-28.
- The last live attack sessions were on the laptop on 2026-10-28, the tablet replay.
- The last live attack on a phone was on 2026-10-24, before the print guards existed. It was not repeated with them.

The last code changes, on 2026-10-28, are in that build, but only automated tests cover them: the cancel during a device
restart, an Arabic text, and three texts on MOSIP's own screens.

## 9. How to reproduce

The commands are for PowerShell. Keep the quotes around `-D` arguments, or PowerShell splits them.

**Toolchain.** JDK 11 and Maven for the engine and the Desktop. JDK 21, Android SDK platform 34 and Flutter 3.10.4 for
Android.

### 9.1 Engine modules, from the repository root

```powershell
mvn -N install "-Dgpg.skip"
mvn -f liveness-api/pom.xml install "-Dgpg.skip"
mvn -f liveness-core/pom.xml install "-Dgpg.skip"
mvn -f liveness-fake/pom.xml install "-Dgpg.skip"
mvn -f liveness-tools/pom.xml install "-Dgpg.skip"
mvn -f liveness-analyzer/pom.xml install "-Dgpg.skip"
mvn -f liveness-eval/pom.xml verify "-Dgpg.skip"
```

### 9.2 Desktop suites

Copy this repository over `registration-client/registration` of Registration Client v1.2.0.2. Then, from that folder:

```powershell
mvn -pl "liveness-api,liveness-core,liveness-fake,liveness-analyzer,registration-services,registration-client" -am install "-DskipTests" "-Dgpg.skip"
mvn -pl "registration-services,registration-client" test "-Dtest=**/liveness/**/*Test,AuditFactoryTest,LoginServiceTest,GlobalParamServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false" "-Dgpg.skip"
```

- The second command also runs upstream's audit, login and config sync suites.
- Outside a git checkout, add `"-Dmaven.gitcommitid.skip=true"` to both.
- To run the device pipeline alone:

```powershell
mvn -pl "liveness-api,liveness-core,registration-services" -am test "-Dtest=**/liveness/**/*Test,FrameTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgpg.skip"
```

### 9.3 Android suites

```powershell
.\android-integration\scripts\apply-integration.ps1 -Target <folder> -BuildApk
cd <folder>\android
.\gradlew.bat :liveness:testDebugUnitTest
.\gradlew.bat :clientmanager:testDebugUnitTest   # 14 upstream failures, section 8.1
cd ..
flutter test test/liveness
```

### 9.4 Evaluation and benchmarks

From the repository root, after the install in section 9.1:

```powershell
mvn -f liveness-eval/pom.xml dependency:build-classpath "-Dmdep.outputFile=liveness-eval/target/eval-cp.txt"
$cp = "liveness-eval\target\classes;" + (Get-Content liveness-eval\target\eval-cp.txt -Raw).Trim()

# The signed model bundle
java -cp $cp io.mosip.registration.liveness.eval.ModelBundleTool verify --dir models

# Laptop timing report, written to liveness-eval/reports/
java -cp $cp io.mosip.registration.liveness.eval.AnalyzerBenchmark

# PAD evaluation: section 4
java -cp $cp io.mosip.registration.liveness.eval.PadEvaluation --data eval-data

# Face binding and the identity threshold rule: section 5
java -cp $cp io.mosip.registration.liveness.eval.BindingEvaluation --labelled "test images/labels.csv" --labels-confirmed --identity --subject-map "s02=p05,s03=p06,s04=p02"

# The four-subject re-evaluation as one command: consent check, both evaluations, guard replays, decision rules
.\scripts\f9-reeval.ps1 -Subjects s01,s02,s03,s04 -SubjectMap "s02=p05,s03=p06,s04=p02" -LabelsConfirmed
```

- `PadEvaluation`, `BindingEvaluation` and the re-evaluation script need `eval-data/`, which holds the consented
  recordings. **It is not published,** so these numbers cannot be reproduced from this repository alone. The method can
  be repeated with new consented recordings: `docs/part2-dataset-protocol.md` has the protocol and the consent text.
- The golden images and the regression clips in the repository show no recorded participant. The clips are public-domain
  portraits or synthetic, so `RecordedVideoE2ETest` runs anywhere.
- Quote the subject map, or PowerShell reads it as an array.

### 9.5 Live checks

- **Laptop:** `scripts\session-series.ps1` runs the live session series. `docs/part2-session-runsheet.md` has the steps.
- **Phone:** the debug page's Benchmark, Hook gates and model buttons. `docs/part5-android-benchmark.md`, "Reproduce",
  has the steps.
