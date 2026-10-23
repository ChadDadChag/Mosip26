package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessResult.Decision;
import io.mosip.registration.liveness.api.LivenessResult.PassPath;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.scenario.ScriptedFaceAnalyzer.Shape;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.mosip.registration.liveness.core.session.ScenarioDriver.NO_FACE;
import static io.mosip.registration.liveness.core.session.ScenarioDriver.TWO_FACES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7: synthetic scenarios through the REAL session (pump, analyzer calls, state machine, challenge runner,
 * dispatcher) with a manual clock. Every ErrorCode, every workflow policy, cancel, retry exhaustion, invariants.
 */
class EngineScenarioTest {

    /** Final-result invariants that must hold for every scenario. */
    static void wellFormed(ScenarioDriver d) {
        assertThat(d.completions).as("exactly one onComplete").isEqualTo(1);
        List<Object> t = new ArrayList<Object>(d.timeline);
        assertThat(t.get(t.size() - 1)).as("nothing after onComplete").isInstanceOf(LivenessResult.class);
        assertThat(d.source.isRunning()).as("source stopped at the end").isFalse();
        assertThat(d.analyzer.closed || d.result.decision == Decision.CANCELLED || d.analyzer.analysed.get() == 0)
                .as("analyzer closed").isTrue();
        LivenessResult r = d.result;
        if (r.decision == Decision.PASSED) {
            assertThat(r.failureCode).isNull();
            assertThat(r.bestFrame).isNotNull();
            assertThat(d.lastEvent(Type.PASSED)).isNotNull();
        } else if (r.decision == Decision.FAILED) {
            assertThat(d.lastEvent(Type.FAILED_FINAL).error).isEqualTo(r.failureCode);
        }
    }

    static final ScenarioDriver.LivenessPolicyTweak ONLY_BLINK = new ScenarioDriver.LivenessPolicyTweak() {
        @Override public void apply(LivenessPolicy p) {
            p.challengePool = new ArrayList<ChallengeType>(Arrays.asList(ChallengeType.BLINK));
            p.minChallenges = 1;
        }
    };

    static final ScenarioDriver.LivenessPolicyTweak ONLY_TURN_LEFT = new ScenarioDriver.LivenessPolicyTweak() {
        @Override public void apply(LivenessPolicy p) {
            p.challengePool = new ArrayList<ChallengeType>(Arrays.asList(ChallengeType.TURN_LEFT));
            p.minChallenges = 1;
        }
    };

    // ------------------------------------------------------------------ passing paths per workflow

    @Test
    void neutralHighScorePassesPassively() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.untilDone(d.score(0.9f));
        wellFormed(d);
        assertThat(d.result.passPath).isEqualTo(PassPath.PASSIVE_ONLY);
        assertThat(d.result.passiveScore).isEqualTo(0.9f);
        assertThat(d.result.modelVersion).isEqualTo("scripted-1");
        assertThat(d.result.durationMs).isBetween(1000L, 2000L); // 10 frames at 8 fps
        assertThat(d.types()).containsExactly(Type.STARTED, Type.CHECKING_PASSIVE, Type.PASSED);
        // 30 fps camera, 8 fps analysis: most frames are dropped, none analysed twice.
        assertThat(d.analyzer.analysed.get()).isBetween(10, 12);
    }

    @Test
    void residentGetsTwoChallengesAndPasses() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.frames(45, d.score(0.6f)).performAll();
        wellFormed(d);
        assertThat(d.result.passPath).isEqualTo(PassPath.ACTIVE_CHALLENGE);
        assertThat(d.result.challengesIssued).hasSize(2).doesNotHaveDuplicates();
        assertThat(d.count(Type.CHALLENGE_PROMPT)).isEqualTo(2);
        assertThat(d.count(Type.ACTION_DETECTED)).isEqualTo(2);
        assertThat(d.types()).containsSubsequence(Type.CHECKING_PASSIVE, Type.CHALLENGE_PROMPT,
                Type.ACTION_DETECTED, Type.CHALLENGE_PROMPT, Type.ACTION_DETECTED, Type.HOLD_STILL, Type.PASSED);
    }

    @Test
    void operatorAndSupervisorGetOneChallenge() {
        for (ScenarioDriver d : Arrays.asList(ScenarioDriver.operator(), ScenarioDriver.supervisor())) {
            d.frames(45, d.score(0.6f)).performAll();
            wellFormed(d);
            assertThat(d.result.passed()).isTrue();
            assertThat(d.result.challengesIssued).as(d.workflow().name()).hasSize(1);
        }
    }

    @Test
    void everyChallengeTypeCanBePassed() {
        for (final ChallengeType c : ChallengeType.values()) {
            ScenarioDriver d = ScenarioDriver.with(WorkflowType.RESIDENT, new ScenarioDriver.LivenessPolicyTweak() {
                @Override public void apply(LivenessPolicy p) {
                    p.challengePool = new ArrayList<ChallengeType>(Arrays.asList(c));
                    p.minChallenges = 1;
                }
            });
            d.frames(45, d.score(0.6f)).performAll();
            wellFormed(d);
            assertThat(d.result.passed()).as(c.name() + " " + d.types()).isTrue();
        }
    }

    // ------------------------------------------------------------------ every ErrorCode

    @Test
    void padFailureOnPassiveWindow() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(45, d.score(0.1f));
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error.userMessage())
                .isEqualTo(ErrorCode.UserMessage.VERIFICATION_NOT_COMPLETED);
        assertThat(d.count(Type.CHALLENGE_PROMPT)).as("attack never falls back to challenges").isZero();
        assertThat(d.audit).contains(AuditSink.Event.PAD_ATTACK_SUSPECTED);
    }

    @Test
    void padFailureMidChallengeEvenIfTheActionIsPerformed() {
        ScenarioDriver d = ScenarioDriver.with(WorkflowType.RESIDENT, ONLY_BLINK);
        d.autoRetry = false;
        d.frames(45, d.score(0.6f)).untilPrompt().perform(0.1f, false);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(d.count(Type.ACTION_DETECTED)).isZero();
    }

    @Test
    void wrongDirectionIsChallengeFailedAndRetryGetsFreshAttempt() {
        ScenarioDriver d = ScenarioDriver.with(WorkflowType.RESIDENT, ONLY_TURN_LEFT);
        d.frames(45, d.score(0.6f)).untilPrompt().performOpposite();
        assertThat(d.attemptErrors()).containsExactly(ErrorCode.CHALLENGE_FAILED);
        d.performAll(); // auto-retry: attempt 2
        wellFormed(d);
        assertThat(d.result.passed()).isTrue();
        assertThat(d.result.attempts).isEqualTo(2);
    }

    @Test
    void doingNothingIsChallengeTimeout() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(45, d.score(0.6f)).untilPrompt().frames(200, d.score(0.6f)); // 6.6 s
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
    }

    @Test
    void eyesClosedForTwoSecondsIsNotABlink() {
        ScenarioDriver d = ScenarioDriver.with(WorkflowType.RESIDENT, ONLY_BLINK);
        d.autoRetry = false;
        Shape closed = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).eyesClosed(); } };
        d.frames(45, d.score(0.6f)).untilPrompt().frames(15, d.score(0.6f)).frames(60, closed).frames(10, d.score(0.6f));
        assertThat(d.count(Type.ACTION_DETECTED)).isZero();
        d.frames(200, d.score(0.6f));
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
    }

    @Test
    void actionBeforeThePromptOnlyDoesNotPass() {
        ScenarioDriver d = ScenarioDriver.with(WorkflowType.RESIDENT, ONLY_BLINK);
        d.autoRetry = false;
        Shape closed = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).eyesClosed(); } };
        d.frames(38, d.score(0.6f));   // passive window just full -> pre-prompt jitter starts
        d.frames(2, closed);           // a blink during the pre-prompt jitter
        d.untilPrompt().frames(200, d.score(0.6f));
        assertThat(d.count(Type.ACTION_DETECTED)).isZero();
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
    }

    @Test
    void secondFaceAppearsMidChallenge() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(45, d.score(0.6f)).untilPrompt().frames(10, TWO_FACES);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.MULTIPLE_FACES);
    }

    @Test
    void multipleFacesPersistInPassive() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(60, TWO_FACES);
        assertThat(d.lastEvent(Type.GUIDANCE).guidance).isEqualTo(LivenessEvent.Guidance.MULTIPLE_FACES);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.MULTIPLE_FACES);
    }

    @Test
    void faceBoxJumpsMidChallenge() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        Shape jumped = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).shift(260, 0); } };
        d.frames(45, d.score(0.6f)).untilPrompt().frames(6, jumped);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.CHALLENGE_FAILED);
        assertThat(d.auditDetails.get(d.audit.lastIndexOf(AuditSink.Event.LIVENESS_ATTEMPT_FAILED)))
                .containsEntry("reason", "CONTINUITY_BREAK");
    }

    @Test
    void noFaceUntilPassiveTimeout() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(260, NO_FACE); // 8.6 s
        assertThat(d.lastEvent(Type.GUIDANCE).guidance).isEqualTo(LivenessEvent.Guidance.NO_FACE);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.FACE_NOT_DETECTED);
    }

    @Test
    void poorQualityUntilPassiveTimeout() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        Shape dark = new Shape() { @Override public Fa apply(Fa f) { return f.issue(FrameAnalysis.QualityIssue.TOO_DARK); } };
        d.frames(260, dark);
        assertThat(d.lastEvent(Type.GUIDANCE).guidance).isEqualTo(LivenessEvent.Guidance.IMPROVE_LIGHTING);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.POOR_QUALITY);
    }

    @Test
    void inconclusiveWithActiveDisabledIsBelowThreshold() {
        ScenarioDriver d = ScenarioDriver.with(WorkflowType.RESIDENT, new ScenarioDriver.LivenessPolicyTweak() {
            @Override public void apply(LivenessPolicy p) { p.activeEnabled = false; }
        });
        d.autoRetry = false;
        d.frames(45, d.score(0.6f));
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.LIVENESS_BELOW_THRESHOLD);
    }

    @Test
    void frozenTimestampsAreInvalidFrames() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(5, d.score(0.6f)).freeze().frames(40, d.score(0.6f));
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.INVALID_FRAME);
    }

    @Test
    void analyzerRejectingFramesIsInvalidFrame() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.frames(5, d.score(0.6f)).analyzerFails(3, ErrorCode.INVALID_FRAME);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.INVALID_FRAME);
    }

    @Test
    void analyzerCrashesAreEngineErrorWithoutUsingRetriesThenFinal() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.frames(5, d.score(0.6f)).analyzerFails(3, ErrorCode.ENGINE_ERROR);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.ENGINE_ERROR);
        assertThat(d.lastEvent(Type.ATTEMPT_FAILED).retriesLeft).isEqualTo(3);
        d.frames(3, d.score(0.6f)).analyzerFails(3, ErrorCode.ENGINE_ERROR);
        wellFormed(d);
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.ENGINE_ERROR);
    }

    @Test
    void modelIntegrityFailureAtLoadIsFinal() {
        ScenarioDriver d = new ScenarioDriver(WorkflowType.OPERATOR, null, ErrorCode.MODEL_INTEGRITY_FAILED, 1);
        d.step();
        wellFormed(d);
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED);
        assertThat(d.audit).contains(AuditSink.Event.MODEL_INTEGRITY_FAILED);
        assertThat(d.analyzer.analysed.get()).isZero();
    }

    @Test
    void analyzerThatCannotLoadIsEngineError() {
        ScenarioDriver d = new ScenarioDriver(WorkflowType.RESIDENT, null, ErrorCode.ENGINE_ERROR, 1);
        d.step();
        wellFormed(d);
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.ENGINE_ERROR);
    }

    @Test
    void noFramesAfterStartIsDeviceUnavailable() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.autoRetry = false;
        d.noFramesFor(5100);
        LivenessEvent e = d.lastEvent(Type.DEVICE_ERROR);
        assertThat(e.error).isEqualTo(ErrorCode.DEVICE_UNAVAILABLE);
        assertThat(e.recoverable).isTrue();
        assertThat(e.error.userMessage()).isEqualTo(ErrorCode.UserMessage.DEVICE_PROBLEM);
    }

    @Test
    void connectionFailureAndCameraUnavailableAreFinalWhenNotRecoverable() {
        for (ErrorCode code : Arrays.asList(ErrorCode.DEVICE_CONNECTION_FAILED, ErrorCode.CAMERA_UNAVAILABLE)) {
            ScenarioDriver d = ScenarioDriver.resident();
            d.frames(3, d.score(0.6f)).deviceError(code, false);
            wellFormed(d);
            assertThat(d.result.failureCode).isEqualTo(code);
        }
    }

    @Test
    void deviceDisconnectsThenReconnectsWithinASecond() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.frames(20, d.score(0.9f)).deviceError(ErrorCode.DEVICE_DISCONNECTED, true).noFramesFor(1000);
        assertThat(d.lastEvent(Type.DEVICE_ERROR).error).isEqualTo(ErrorCode.DEVICE_DISCONNECTED);
        d.untilDone(d.score(0.9f));
        wellFormed(d);
        assertThat(d.result.passed()).isTrue();
        assertThat(d.attemptErrors()).as("no retry consumed").isEmpty();
    }

    @Test
    void streamStallMidChallengeIsDisconnectedAndRetryRestartsTheSource() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.frames(45, d.score(0.6f)).untilPrompt().noFramesFor(2100);
        assertThat(d.lastEvent(Type.DEVICE_ERROR).error).isEqualTo(ErrorCode.DEVICE_DISCONNECTED);
        d.noFramesFor(3500); // grace expired; auto-retry on DEVICE_ERROR restarts the source
        assertThat(d.source.starts.get()).isGreaterThanOrEqualTo(2);
        d.frames(45, d.score(0.6f)).performAll();
        wellFormed(d);
        assertThat(d.result.passed()).isTrue();
    }

    @Test
    void maxRetriesExceededKeepsLastCauseAndOperatorCooldownTrips() {
        ScenarioDriver d = ScenarioDriver.operator();
        for (int i = 0; i < 4 && !d.done(); i++) d.frames(45, d.score(0.1f));
        wellFormed(d);
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(d.result.failureCode.userMessage()).isEqualTo(ErrorCode.UserMessage.MAX_ATTEMPTS_REACHED);
        assertThat(d.result.attempts).isEqualTo(4);
        assertThat(d.attemptErrors()).containsExactly(ErrorCode.PAD_FAILURE, ErrorCode.PAD_FAILURE, ErrorCode.PAD_FAILURE);
        assertThat(d.auditDetails.get(d.audit.lastIndexOf(AuditSink.Event.LIVENESS_FAILED_FINAL)))
                .containsEntry("lastCause", "PAD_FAILURE").containsEntry("cooldownSec", "60");
        assertThat(d.cooldown.isActive(WorkflowType.OPERATOR)).isTrue();
    }

    @Test
    void residentMaxRetriesDoesNotTripCooldown() {
        ScenarioDriver d = ScenarioDriver.resident();
        for (int i = 0; i < 4 && !d.done(); i++) d.frames(45, d.score(0.1f));
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(d.cooldown.isActive(WorkflowType.RESIDENT)).isFalse();
    }

    @Test
    void cancelAtEachStage() {
        String[] stages = {"starting", "passive", "challenge", "retry", "device"};
        for (String stage : stages) {
            ScenarioDriver d = ScenarioDriver.resident();
            d.autoRetry = false;
            if (stage.equals("passive")) d.frames(10, d.score(0.6f));
            if (stage.equals("challenge")) d.frames(45, d.score(0.6f)).untilPrompt();
            if (stage.equals("retry")) d.frames(45, d.score(0.1f));
            if (stage.equals("device")) d.frames(5, d.score(0.6f)).deviceError(ErrorCode.DEVICE_DISCONNECTED, true);
            int before = d.events.size();
            d.cancel().frames(20, d.score(0.9f));
            wellFormed(d);
            assertThat(d.result.decision).as(stage).isEqualTo(Decision.CANCELLED);
            assertThat(d.result.failureCode).isEqualTo(ErrorCode.CANCELLED);
            assertThat(d.result.failureCode.userMessage()).isEqualTo(ErrorCode.UserMessage.NONE);
            assertThat(d.events).as("no events after cancel at " + stage).hasSize(before);
            assertThat(d.source.stops.get()).isGreaterThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ invariants under random input

    /** A PAD attack signal throughout can never pass, whatever motions the attacker performs. */
    @Test
    void fuzzAttackScoresNeverPass() {
        Random r = new Random(42);
        for (int run = 0; run < 40; run++) {
            ScenarioDriver d = new ScenarioDriver(WorkflowType.RESIDENT, null, null, run);
            for (int i = 0; i < 1500 && !d.done(); i++) d.frame(randomShape(r, 0f, 0.29f));
            if (d.done()) assertThat(d.result.decision).isNotEqualTo(Decision.PASSED);
            assertThat(d.count(Type.PASSED)).isZero();
            assertThat(d.count(Type.CHALLENGE_PROMPT)).isZero();
        }
    }

    /**
     * Random analyses of any kind: at most one result, nothing after it, and an active pass only after every issued
     * challenge of the final attempt was detected.
     */
    @Test
    void fuzzRandomAnalysesKeepInvariants() {
        Random r = new Random(7);
        for (int run = 0; run < 60; run++) {
            ScenarioDriver d = new ScenarioDriver(WorkflowType.values()[run % 3], null, null, run);
            for (int i = 0; i < 2500 && !d.done(); i++) {
                int k = r.nextInt(1000);
                if (k < 3) d.deviceError(ErrorCode.DEVICE_DISCONNECTED, r.nextInt(4) > 0);
                else if (k < 5) d.analyzerFails(1, r.nextBoolean() ? ErrorCode.ENGINE_ERROR : ErrorCode.INVALID_FRAME);
                else if (k < 6) d.noFramesFor(r.nextInt(3000));
                else d.frame(randomShape(r, 0f, 1f));
            }
            if (!d.done()) d.cancel();
            wellFormed(d);
            LivenessResult res = d.result;
            if (res.passed() && res.passPath == PassPath.ACTIVE_CHALLENGE) {
                List<Type> t = d.types();
                int lastStart = t.lastIndexOf(Type.STARTED);
                int detected = 0;
                for (int i = lastStart; i < t.size(); i++) if (t.get(i) == Type.ACTION_DETECTED) detected++;
                assertThat(detected).as("every challenge detected before an active pass").isEqualTo(res.challengesIssued.size());
            }
            if (res.passed() && res.passPath == PassPath.PASSIVE_ONLY) {
                assertThat(res.passiveScore).isGreaterThanOrEqualTo(0.85f);
            }
        }
    }

    private static Shape randomShape(final Random r, final float minScore, final float maxScore) {
        final float score = minScore + r.nextFloat() * (maxScore - minScore);
        final int faces = r.nextInt(20) == 0 ? r.nextInt(3) : 1;
        final float yaw = r.nextInt(4) == 0 ? (r.nextFloat() - 0.5f) * 70 : 0;
        final float pitch = r.nextInt(6) == 0 ? (r.nextFloat() - 0.5f) * 40 : 0;
        final boolean closed = r.nextInt(8) == 0;
        final boolean smile = r.nextInt(8) == 0;
        final float shift = r.nextInt(30) == 0 ? 250 : 0;
        final Set<FrameAnalysis.QualityIssue> issues = r.nextInt(10) == 0
                ? EnumSet.of(FrameAnalysis.QualityIssue.values()[r.nextInt(FrameAnalysis.QualityIssue.values().length)])
                : EnumSet.noneOf(FrameAnalysis.QualityIssue.class);
        return new Shape() {
            @Override public Fa apply(Fa f) {
                Fa g = f.score(score).faces(faces).yaw(yaw).pitch(pitch).shift(shift, 0);
                if (closed) g.eyesClosed();
                if (smile) g.smile();
                for (FrameAnalysis.QualityIssue q : issues) g.issue(q);
                return g;
            }
        };
    }

    /**
     * Regression (found while integrating with Part 1, JDK 11): the cooldown and the source stop used to happen after
     * onComplete was queued, so an app starting its next session from inside onComplete could bypass the cooldown,
     * and the finished session could stop a FrameSource the app had already restarted.
     * The "never stopped again" part is deterministic here. The effect ORDER is guarded deterministically by
     * SessionStateMachineTest.completeIsTheLastEffectOnEveryTerminalPath: in this harness the effects usually finish
     * before the dispatcher delivers, so the first two checks only confirm the end-to-end behaviour.
     */
    @Test
    void insideOnCompleteTheCooldownIsActiveAndTheSourceIsReleased() {
        final ScenarioDriver d = ScenarioDriver.operator();
        final boolean[] seen = new boolean[3];
        d.onCompleteHook = new Runnable() {
            @Override public void run() {
                seen[0] = d.cooldown.isActive(WorkflowType.OPERATOR);
                seen[1] = !d.source.isRunning();
                d.source.start(null); // the app reuses the same source for its next session
                seen[2] = true;
            }
        };
        for (int i = 0; i < 4 && !d.done(); i++) d.frames(45, d.score(0.1f));
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(seen[2]).isTrue();
        assertThat(seen[0]).as("cooldown already active inside onComplete").isTrue();
        assertThat(seen[1]).as("source already stopped inside onComplete").isTrue();
        d.step(); // worker cleanup after the result
        assertThat(d.source.isRunning()).as("finished session must not stop a source the app restarted").isTrue();
        assertThat(d.source.stops.get()).isEqualTo(1);
    }

    @Test
    void theSourceIsStoppedExactlyOncePerSession() {
        ScenarioDriver pass = ScenarioDriver.resident();
        pass.untilDone(pass.score(0.9f));
        pass.step();
        assertThat(pass.source.stops.get()).isEqualTo(1);
        ScenarioDriver cancelled = ScenarioDriver.resident();
        cancelled.frames(5, cancelled.score(0.6f)).cancel().frames(5, cancelled.score(0.6f));
        assertThat(cancelled.source.stops.get()).isEqualTo(1);
    }
}
