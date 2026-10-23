# Part 3 status report: liveness-core, liveness-fake, config and policy

Audit date: 2026-09-23. Scope: Part 3 only. Parts 1, 2, 4 and 5 are not built; test-only stand-ins were used for their interfaces.

> **Integration note (2026-10-23).** Part 3 now lives in the team repo on top of Part 1's work, as modules of `registration-client-parent` (Java 11, JDK 11, see design decision D18). The build commands and the `--release 8` / JDK 17 enforcer details below describe the standalone build at audit time. Current build, from `registration-client/registration`: `mvn -pl liveness-api,liveness-core,liveness-fake,liveness-tools -am verify -Dgpg.skip`.

**Current result (after fixes):** 29 DONE · 0 PARTIAL · 0 MISSING · 0 BROKEN.

- **Audit:** 26 DONE and 3 PARTIAL, with no production code changed.
- **Fixes:** all code and documentation findings were then fixed (§4a). `liveness-api` is still unchanged.
- **Still open:** two items need people or other parts, not code (§6): the contract proposals and tuning on real faces.

---

## 1. What exists

The contract is `liveness-api`, package `io.mosip.registration.liveness.api`: 17 Java files plus `liveness-default.properties`, all read.

| Role | Class |
|---|---|
| `LivenessService` (real) | `liveness-core/.../core/DefaultLivenessService.java` (builder: config, analyzerFactory, auditSink) |
| `LivenessSession` (real) | `core/session/DefaultLivenessSession.java` (threads), with logic in `core/session/SessionStateMachine.java` |
| Session refused by cooldown | `core/session/CooldownRefusedSession.java` |
| Challenge selection | `core/challenge/ChallengeSelector.java` |
| Challenge execution (baseline, prompt, checks, hold) | `core/challenge/ChallengeRunner.java` |
| Action detectors | `core/detect/BlinkDetector`, `SmileDetector`, `HeadTurnDetector`, `GazePitchDetector` (shared `AxisMotionDetector`), `Detectors` factory |
| Passive decision / continuous PAD | `core/passive/PassiveEvaluator.java`, `core/passive/PadMonitor.java` |
| Policy / config | `core/policy/PolicyResolver.java`, `ServicePolicies.java`, `BundledDefaults.java`, `CooldownTracker.java` (loader `LivenessPolicy.load` is in the contract) |
| Audit | `core/audit/AuditRecorder.java` |
| `FakeLivenessService` | `liveness-fake/.../fake/FakeLivenessService.java`, `FakeLivenessSession.java`, `FakeScenario.java` |

**Test-only fakes.** Added in `liveness-core/src/test/java/.../core/statusaudit/`:
- `FakeFrameSource`
- `MapConfigProvider`
- `InMemoryAuditSink`
- `RecordingLivenessListener`

Reused from earlier test code:
- `core/scenario/ScriptedFaceAnalyzer` (a scripted `FrameAnalysis` per frame; can throw `LivenessEngineException`)
- `core/testing/Fa` (builder for face count, quality issues, score, blink/smile landmarks, yaw/pitch)
- `core/session/ScenarioDriver` (runs the real session with a manual clock)

**Clock check.**
- **Core:** the engine reads time only through the injected `TimeSource`, and all timeouts are testable without sleeping.
- **Fake:** at audit time, `FakeLivenessSession` paced its script with `System.nanoTime()`. **Fixed:** it now waits on the injected clock (`FakeLivenessSession.java:440`), so a manual clock drives it (`pacingFollowsTheInjectedClock`).

---

## 2. Requirements

| # | Requirement | Status | Evidence | What is missing |
|---|---|---|---|---|
| 1 | Fake: scripted scenarios (passive pass, pass after challenge, PAD, timeout, max retries, device error) | DONE | `fake/FakeScenario.java` (14 scenarios + `PASS_ACTIVE`, including recoverable `DEVICE_DISCONNECT` and **new** non-recoverable `DEVICE_FAILURE`); `FakeLivenessServiceTest.everyScenarioEndsInExactlyOneExpectedResult`, `deviceFailureScenarioShowsNoRetry` | — |
| 2 | Fake: event order, exactly one onComplete, retry/cancel | DONE | `FakeLivenessSession.java:130` (cancel), `:461` (retry-safe wait); tests `challengePromptsCarryIndexCountAndPolicyTimeout`, `cancelFromInsideCallbackMidChallenge…`, `cancelRacingFromOtherThreads…`, `retryIsIgnoredUnlessAnAttemptFailed` | — |
| 3 | Each of the 6 detectors detects a correct sequence | DONE | `BlinkDetector.java:40`, `SmileDetector.java:40`, `AxisMotionDetector.java:53`; `Part3RequirementsAuditTest.r3_everyDetectorDetectsItsAction`, `EngineScenarioTest.everyChallengeTypeCanBePassed` (end to end) | — |
| 4 | Wrong actions rejected (a smile is not a blink, a right turn is not a left turn) | DONE | `AxisMotionDetector.java:66` (opposite motion = WRONG_ACTION); `r4_wrongActionsAreRejected`, `AxisMotionDetectorTest.oppositeTurnIsWrongAction` | — |
| 5 | Neutral baseline first; progress 0→1; action already under way at the prompt doesn't count | DONE | `Baseline.java:27`, `ChallengeRunner.java:200` (baseline before prompt), `AxisMotionDetector.java:61` (arming); `r5_progressAndPrePromptAction`, `r5_progressEventsAreMonotonicPerChallenge` | Note: the last `CHALLENGE_PROGRESS` before detection is ≤ 0.85. The 100% signal is `ACTION_DETECTED`, whose contract factory sets `progress=0`, so the UI should treat ACTION_DETECTED as complete. |
| 6 | Null landmarks / lost face don't crash | DONE | `BlinkDetector.java:42` (NaN guard); `r6_nullLandmarksAndLostFaceAreSafe` (all 6 types, including a baseline built without landmarks) | — |
| 7 | SecureRandom, minChallenges distinct types, only from the pool | DONE | `DefaultLivenessService.java:41`, `ChallengeSelector.java:43`; `ChallengeSelectorTest.onlyUsesSupportedTypesAndIgnoresDuplicates`, `sequencesAreDistinct…` | — |
| 8 | Retry never repeats the previous sequence; the distribution isn't fixed | DONE | `ChallengeSelector.java:54`; `ChallengeSelectorTest.retrySequenceDiffersFromPrevious`, `distributionIsUniformOverValidSequences` (10,000 draws) | — |
| 9 | Passive: median over `passiveWindowFrames` **usable** frames; ≥ pass → PASSED/PASSIVE_ONLY; < padReject → PAD_FAILURE with no challenge; between → challenges | DONE (fixed) | `PassiveEvaluator.java:80` (median decision), `:75` (eligibility = `usable()`); `PassiveEvaluatorTest.*`, `EngineScenarioTest.padFailureOnPassiveWindow` | Was PARTIAL: frames with hint-only issues were counted. Now eligibility is strictly `FrameAnalysis.usable()`. Guarded by `r9_onlyUsableFramesCountTowardsTheWindow` and `PassiveEvaluatorTest.ineligibleFramesAreSkippedNotCounted`. |
| 10 | activeEnabled=false → in-between score fails | DONE | `PassiveEvaluator.java:86`; `EngineScenarioTest.inconclusiveWithActiveDisabledIsBelowThreshold` | — |
| 11 | No usable window in passiveTimeoutMs → FACE_NOT_DETECTED / POOR_QUALITY | DONE | `PassiveEvaluator.java:90` (also MULTIPLE_FACES when it dominates); `EngineScenarioTest.noFaceUntilPassiveTimeout`, `poorQualityUntilPassiveTimeout` | — |
| 12 | GUIDANCE for no face, multiple faces, each quality issue | DONE | `PositioningHintFilter.java:79`; `r12_guidanceForEveryProblem` (all 7 issues + NO_FACE, MULTIPLE_FACES, LOOK_AT_CAMERA) | By design, pose, centring and size hints are suppressed *during* challenges (they would contradict "turn your head"). |
| 13 | During challenges: score < padReject → PAD_FAILURE; face lost / 2nd face → attempt fails | DONE | `ChallengeRunner.java:139`, `:128`; `EngineScenarioTest.padFailureMidChallengeEvenIfTheActionIsPerformed`, `secondFaceAppearsMidChallenge`, `ChallengeRunnerTest.faceLostForASecondFails` | — |
| 14 | Challenge timeout → CHALLENGE_TIMEOUT; ATTEMPT_FAILED has the correct retriesLeft | DONE | `ChallengeRunner.java:250`, `AttemptTracker.java:57`; `r14_timeoutAndRetriesLeft` (3, 2, 1, then MAX_RETRIES_EXCEEDED) | — |
| 15 | After maxRetries → FAILED_FINAL + MAX_RETRIES_EXCEEDED; COOLDOWN refuses sessions for cooldownSec | DONE | `SessionStateMachine.java:428`, `DefaultLivenessService.java:54`; `r15_cooldownOnTheRealService` (refused, camera not opened, allowed again after 60 s) | The cooldown is in memory and resets on app restart (contract proposal P1). |
| 16 | retry() ignored when not valid; cancel() → CANCELLED and the source stops | DONE | `SessionStateMachine.java:218`, `DefaultLivenessSession.java:167`; `SessionStateMachineTest.retryGetsFreshSequenceAndIsIgnoredWhenNotFailed`, `EngineScenarioTest.cancelAtEachStage` | — |
| 17 | FaceAnalyzer exception → ENGINE_ERROR; FrameSource.onError → DEVICE_ERROR with the correct recoverable flag | DONE (fixed) | `SessionStateMachine.java:138` (analyzer), `:160`/`:165` (device); `r17_analyzerExceptionsBecomeEngineError`, `r17_deviceErrorsAsBuilt` | Was PARTIAL. A non-recoverable `onError` now emits `DEVICE_ERROR(recoverable=false)` and then `FAILED_FINAL`, in both the engine and the fake. Guarded by `r17_nonRecoverableDeviceErrorEmitsDeviceErrorFalse`, `SessionStateMachineTest.nonRecoverableDeviceErrorEndsWithTheDeviceCode`, and `FakeLivenessServiceTest.nonRecoverableDeviceErrorEndsTheSessionWithTheDeviceCode` (a mutation check confirmed the tests catch a revert). By design (D7), one analyzer exception is skipped; 3 in a row give ENGINE_ERROR. |
| 18 | onComplete exactly once in every path, including cancel mid-challenge | DONE | `EventDispatcher.java:74`; `EngineScenarioTest.cancelAtEachStage`, `fuzzRandomAnalysesKeepInvariants`, `EventDispatcherTest.racingEmitters…` (1,000 rounds) | — |
| 19 | One worker, newest frame only, analyzeFps respected, no deadlock | DONE | `FramePump.java:42`, `DefaultLivenessSession.java:295`; `r19_threadingUnderAFastCamera` (200 fps camera and 30 ms analyzer: 1 worker thread, 10–11 analyses, most frames dropped), `DefaultLivenessServiceTest.cancelIsImmediateEvenWhileTheAnalyzerIsBusy` | — |
| 20 | PASSED has bestFrame, passiveScore, challengesIssued, attempts, sessionId, modelVersion | DONE | `SessionStateMachine.java:414`; `r20_passedResultFields`, `EngineScenarioTest.residentGetsTwoChallengesAndPasses` | Fixed: a cooldown-refused session now reports `modelVersion = "unavailable"` (`cooldownRefusalReportsModelUnavailable`). |
| 21 | newSession → null when disabled | DONE | `DefaultLivenessService.java:52`; `DefaultLivenessServiceTest.serviceBasics`, `FakeLivenessServiceTest.disabledWorkflowReturnsNullSession` | — |
| 22 | Workflow key > default key > hard-coded; invalid values rejected | DONE | `PolicyResolver.java:38`, `:58`; `PolicyResolverTest.*` | Invalid config is rejected **and replaced by the bundled defaults** (audited as `CONFIG_LOADED{configError}`). The caller isn't told otherwise. The bundled file sits between host keys and hard-coded defaults (documented). |
| 23 | reloadConfig doesn't change a running session | DONE | `ServicePolicies.java:46` (snapshot copy); `r23_reloadDoesNotAffectRunningSession` (real service), `FakeLivenessServiceTest.reloadConfigAppliesToNewSessionsOnly` | — |
| 24 | Correct audit events with sessionId, modelVersion, scores, codes | DONE | `AuditRecorder.java:27`; `r24_r25_r26_auditOnTheRealService`, `AuditRecorderTest.*` | — |
| 25 | No image bytes, Frames or landmarks reach AuditSink | DONE | `AuditRecorderTest.publicApiCannotAcceptImagesOrLandmarks` (reflection), `r24_r25_r26…` (only known keys, no coordinate-like values) | — |
| 26 | PAD details only in audit; UI gets the generic message | DONE | `r24_r25_r26…` (`pad.*` keys in PAD_ATTACK_SUSPECTED; `ErrorCode.PAD_FAILURE.userMessage()` = VERIFICATION_NOT_COMPLETED), `FakeLivenessServiceTest.padFailureOnlyEverMapsToTheGenericMessage` | — |
| 27 | Compiles with --release 8; no records, var or Java 9+ APIs | DONE | root `pom.xml` (`maven.compiler.release=8`, animal-sniffer Java 8 **and** Android API 21 signatures); all 83 core and fake classes are major version 52 | — |
| 28 | No JavaFX, AWT, ImageIO or Swing | DONE | grep of all modules: none; animal-sniffer Android 21 check passes | — |
| 29 | Docs: decision flow, challenge mechanism, config design, error/recovery flow | DONE (fixed) | `docs/person3-design.md:95` (decision flow), `:208` (challenges), `:377` (error and recovery), `:154` (ErrorCode map) | Was PARTIAL. There is now a single **§1.3 Configuration design** covering: source, key format, lookup order, a table of every key with type/range/defaults/meaning, validation and fallback, snapshots on reload, and what is not config. |

---

## 3. Build and test results

**After the fixes:** `mvn -B verify` ran 3 times, all BUILD SUCCESS: **232 tests** (core 181, fake 41, tools 10), 0 failures, 0 skipped. Core line coverage is 96.6%, and the enforcer rule (JDK 17+ and Maven 3.9+) passes on JDK 17 and JDK 27.

The audit-time results are kept below for the record.

Commands, run from the repo root:

```bash
mvn -v                                    # Maven 3.9.16, runtime JDK 27 (Homebrew default); JDK 17 also installed
mvn -B verify                              # run 3 times, all exit 0
mvn -q -pl liveness-core -am test -Dtest=Part3RequirementsAuditTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl liveness-core -am test -Dtest='Part3RequirementsAuditTest#r9*+r17_nonRecoverable*' \
    -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition' -Dsurefire.failIfNoSpecifiedTests=false
```

- **Before the audit:** `mvn -B verify` gave BUILD SUCCESS with 211 tests: core 163, fake 38, tools 10. There were 0 failures.
- **After adding the audit tests:** BUILD SUCCESS with 229 tests (core 181, fake 38, tools 10). 227 pass, 0 fail, and 2 are skipped. The skipped ones are the @Disabled tests that document gaps R9 and R17.
- **Forced run of the 2 disabled tests:** both fail as expected.
  - R9: `r9_onlyUsableFramesCountTowardsTheWindow:238`, expected window size 0 but was 10.
  - R17: `r17_nonRecoverableDeviceErrorEmitsDeviceErrorFalse:330`, the DEVICE_ERROR event was null.
- **Coverage:** JaCoCo line coverage on `liveness-core` is **96.6%**, against a gate of 85%.
- **Stability:** 3 consecutive full builds were green, including the real-thread tests.
- **Test mistake along the way:** one audit test failed on its first run because of my test, not the engine. R19 counted analyses up to the first prompt, which included the higher 15 fps pre-prompt rate. I corrected the measurement to the passive phase only.

---

## 4. Bugs found (most serious first), all fixed (see §4a); line numbers are as at audit time

1. **A non-recoverable device error never emits `DEVICE_ERROR(recoverable=false)`** (req 17).
   - **Where:** `SessionStateMachine.java:165`, and the same in `FakeLivenessSession.java:193`.
   - **What happens:** the UI only receives `FAILED_FINAL(<device code>)`. The user message is still right (DEVICE_PROBLEM), but a UI written to the contract's "DEVICE_ERROR: `recoverable` tells the UI whether to show Retry" never sees the `false` case. My own design doc (§4) says a DEVICE_ERROR is emitted, so the code and the doc disagree.
2. **The passive window counts non-usable frames** (req 9).
   - **Where:** `PassiveEvaluator.java:37`/`:82`.
   - **What happens:** frames flagged `FACE_OFF_CENTER` or `FACE_TOO_LARGE` count towards the median, which contradicts the contract's "usable frames" and `FrameAnalysis.usable()`. A session can pass passively while the UI is still showing "centre your face".
   - **Impact:** probably small, but PAD scores on badly framed faces may be less reliable. Either tighten to `usable()` or get the contract wording changed.
3. **An automatic resume after a device blip counts as a new attempt.**
   - **Where:** `SessionStateMachine.java:111`.
   - **What happens:** the UI sees `STARTED(2)` and `result.attempts=2` although the user never pressed Retry (`probe_autoResumeAfterDeviceBlipIncrementsAttempts`). No retry is consumed, so this is cosmetic, but it muddies attempt metrics.
4. **A cooldown-refused session has `modelVersion = null`.**
   - **Where:** `DefaultLivenessService.java:54`.
   - **What happens:** the service passes `null` because no analyzer is created. Anything that reads `result.modelVersion` without a null check could break (`probe_cooldownRefusalHasNoModelVersion`).
5. **The fake's pacing is tied to the real clock.**
   - **Where:** `FakeLivenessSession.java:426`.
   - **What happens:** it can't be driven by a manual clock; only `timeScale(0)` makes it fast. This is testability only.

No crashes, deadlocks, lost results or duplicate `onComplete` calls were found.

## 4a. Fixes applied

| Finding | Fix | Where | Guarded by |
|---|---|---|---|
| Bug 1 / R17: no DEVICE_ERROR(recoverable=false) | Emit `DEVICE_ERROR(code, recoverable=false)` before `FAILED_FINAL` | `SessionStateMachine.onSourceError`, `FakeLivenessSession.runSession` | `r17_nonRecoverable…`, `SessionStateMachineTest.nonRecoverable…`, fake `nonRecoverable…`, `deviceFailureScenarioShowsNoRetry` |
| Bug 2 / R9: non-usable frames in the passive window | Eligibility = `FrameAnalysis.usable()` + score + pose | `PassiveEvaluator.isEligible` | `r9_onlyUsable…`, `PassiveEvaluatorTest.ineligibleFrames…` |
| Bug 3: device recovery counted as an attempt | Auto-resume and Retry after DEVICE_ERROR keep the attempt number; `result.attempts` counts liveness attempts only | `SessionStateMachine.onFrame/onRetry`, `FakeLivenessSession.runSession` (the `DEVICE_DISCONNECT` script now disconnects once per session) | `autoResumeAfterDeviceBlipKeepsTheAttemptNumber`, `SessionStateMachineTest.afterGraceOnlyRetryRestartsTheSource`, fake scenario table |
| Bug 4: modelVersion null on cooldown refusal | `"unavailable"` placeholder (`CooldownRefusedSession.MODEL_UNAVAILABLE`), also used before an analyzer loads | `CooldownRefusedSession`, `DefaultLivenessSession` | `cooldownRefusalReportsModelUnavailable` |
| Bug 5: fake paced by the real clock | The fake's script now waits on the **injected** clock (re-checked every 10 ms) | `FakeLivenessSession.pause` | `pacingFollowsTheInjectedClock` |
| No scripted non-recoverable device scenario | New `FakeScenario.DEVICE_FAILURE` (README updated) | `FakeScenario`, `FakeLivenessSession` | scenario table, `deviceFailureScenarioShowsNoRetry` |
| R29: config design spread out | New single §1.3 Configuration design; decisions D16/D17 recorded | `docs/person3-design.md` | — |
| Unpinned build tools | Maven Enforcer: JDK 17+ and Maven 3.9+ | root `pom.xml` | build |

Two mistakes of mine during the fixing, both caught before finishing:
1. **Lost fixes:** undoing a mutation check with `git checkout` briefly reverted my uncommitted state-machine fixes. I restored them from the backup taken before mutating, and the full build confirmed they were back.
2. **Deleted method:** the fake-pacing edit accidentally deleted `enterWaitingForRetry()`, the earlier lost-Retry fix. The compile error exposed it, and I restored the method unchanged.

---

## 5. What could not be verified without the other parts

- **Real detection accuracy and thresholds (Part 2).** Every `TUNE:` value is untested on real faces. The committed traces are synthetic. Blink detection at the real analyzer frame rate, and EAR from Part 2's real landmarks, are unknown.
- **Pose sign conventions (Part 2).** The contract defines the yaw sign but not the pitch sign; core assumes +pitch = looking up (proposal P2). Whether front-camera frames are un-mirrored before yaw is computed is also unknown.
- **Real device behaviour (Part 1).** Reconnect semantics after a recoverable `onError`, real frame rates and timestamps, and whether `stop()`/`start()` restarts cleanly.
- **Analyzer performance (Parts 2 and 5).** Whether about 15 fps during challenges is achievable on low-end Android (proposal P5).
- **UI handling (Parts 4 and 5).** Rendering and debouncing of events, marshalling callbacks to the UI thread, Retry/cancel wiring, and the preview path (note N1: the engine owns the single `FrameSource` listener).
- **Platform integration (Parts 4 and 5).** Mapping `AuditSink` into the MOSIP audit tables, synced config online and offline, and whether `bestFrame` passes MOSIP's own capture quality checks.
- **Model integrity (Part 2).** The `MODEL_INTEGRITY_FAILED` path was only tested with a factory that throws; the real checksum or signature verification is Part 2's.

---

## 6. Prioritised to-do list (what remains)

Everything below needs people or other parts. None of it can be finished by more Part 3 code alone.

1. **Decide contract proposals P1–P6** (`docs/contract-change-proposals.md`) with the affected parts.
   - Most important: P1 (a cooldown error code, and persisting the cooldown), P2 (the pitch sign, with Part 2) and P5 (the analysis rate during challenges, with Parts 2 and 5).
   - As contract approver, you decide. Once approved, applying them in `liveness-api` and removing the core workarounds is a small change.
2. **Tune on real faces.** When Part 2's analyzer runs:
   - Record team traces with `liveness-tools` `TraceRecorder` (see its README).
   - Run `DetectorTuningReport`.
   - Replace the synthetic traces, so the regression tests become real.
3. **Integration checks with Parts 1, 4 and 5** (listed in §5): real device reconnects, UI handling of `DEVICE_ERROR(recoverable=false)` (new), and the preview path (note N1).
4. **Tell Parts 4 and 5 about the behaviour changes:**
   - DEVICE_ERROR can now arrive with `recoverable=false` just before FAILED_FINAL.
   - `attempts` no longer counts device recoveries.
   - The fake has a new `DEVICE_FAILURE` scenario.
