package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.audit.AuditRecorder.ChallengeOutcome;
import io.mosip.registration.liveness.core.challenge.ChallengeSelector;
import io.mosip.registration.liveness.core.session.SessionStateMachine.State;
import io.mosip.registration.liveness.core.testing.Fa;
import io.mosip.registration.liveness.core.util.ManualTimeSource;
import io.mosip.registration.liveness.core.util.SeededRandomSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStateMachineTest {

    /** Stand-in for the challenge phase: replays scripted steps, one list per analysed frame. */
    static final class ScriptedActive implements ActivePhase {
        final Deque<List<ActiveStep>> script = new ArrayDeque<List<ActiveStep>>();
        List<ChallengeType> started;
        int seedFrames;
        Set<ChallengeType> supported = EnumSet.allOf(ChallengeType.class);

        @Override public Set<ChallengeType> supported() { return supported; }
        @Override public void start(List<ChallengeType> sequence, List<FrameAnalysis> seed, long nowMs) {
            started = sequence;
            seedFrames = seed.size();
        }
        @Override public void onFrame(FrameAnalysis a, long nowMs, List<ActiveStep> out) {
            List<ActiveStep> next = script.poll();
            if (next != null) out.addAll(next);
        }
        @Override public void onTick(long nowMs, List<ActiveStep> out) { }

        void then(ActiveStep... steps) { script.add(Arrays.asList(steps)); }
    }

    static final class Sink implements AuditSink {
        final List<Event> events = new ArrayList<Event>();
        final List<Map<String, String>> details = new ArrayList<Map<String, String>>();
        @Override public void record(Event e, WorkflowType wf, String s, Map<String, String> d) { events.add(e); details.add(d); }
    }

    private LivenessPolicy policy;
    private EngineTuning tuning;
    private ScriptedActive active;
    private Sink sink;
    private SessionStateMachine sm;
    private final List<Effect> effects = new ArrayList<Effect>();
    private long now;
    private long seq;

    @BeforeEach
    void setUp() {
        policy = new LivenessPolicy();
        tuning = EngineTuning.defaults();
        active = new ScriptedActive();
        sink = new Sink();
        rebuild();
    }

    private void rebuild() {
        sm = new SessionStateMachine(policy, tuning, "sid", "model-1",
                new AuditRecorder(sink, WorkflowType.RESIDENT, "sid", "model-1", new ManualTimeSource(0), "core"),
                new ChallengeSelector(new SeededRandomSource(5)), active);
        effects.clear();
        now = 1000;
        seq = 0;
    }

    // ------------------------------------------------------------------ driving helpers

    private List<Effect> start() { return record(sm.start(now)); }

    private List<Effect> frame(Fa f) {
        now += 125;
        return record(sm.onFrame(f.build(), now));
    }

    private Fa fa() { return Fa.at(now + 125, ++seq); }

    private void frames(int n, float score) { for (int i = 0; i < n; i++) frame(fa().score(score)); }

    private List<Effect> tick(long advanceMs, long lastArrival) {
        now += advanceMs;
        return record(sm.onTick(now, lastArrival));
    }

    private List<Effect> record(List<Effect> e) {
        effects.addAll(e);
        return e;
    }

    private List<Type> events() {
        List<Type> t = new ArrayList<Type>();
        for (Effect e : effects) if (e.kind == Effect.Kind.EMIT) t.add(e.event.type);
        return t;
    }

    private LivenessEvent last(Type type) {
        for (int i = effects.size() - 1; i >= 0; i--) {
            Effect e = effects.get(i);
            if (e.kind == Effect.Kind.EMIT && e.event.type == type) return e.event;
        }
        throw new AssertionError("no " + type + " in " + effects);
    }

    private LivenessResult result() {
        LivenessResult r = null;
        int n = 0;
        for (Effect e : effects) if (e.kind == Effect.Kind.COMPLETE) { r = e.result; n++; }
        assertThat(n).as("exactly one COMPLETE").isEqualTo(1);
        return r;
    }

    private boolean has(Effect.Kind k) {
        for (Effect e : effects) if (e.kind == k) return true;
        return false;
    }

    // ------------------------------------------------------------------ passive

    @Test
    void passivePassWithBestFrameAndAudit() {
        start();
        assertThat(sm.state()).isEqualTo(State.STARTING);
        frames(10, 0.93f);
        assertThat(sm.state()).isEqualTo(State.PASSED);
        assertThat(events()).containsExactly(Type.STARTED, Type.CHECKING_PASSIVE, Type.PASSED);
        LivenessResult r = result();
        assertThat(r.decision).isEqualTo(LivenessResult.Decision.PASSED);
        assertThat(r.passPath).isEqualTo(LivenessResult.PassPath.PASSIVE_ONLY);
        assertThat(r.passiveScore).isEqualTo(0.93f);
        assertThat(r.bestFrame).isNotNull();
        assertThat(r.challengesIssued).isEmpty();
        assertThat(r.attempts).isEqualTo(1);
        assertThat(r.durationMs).isEqualTo(1250);
        assertThat(r.modelVersion).isEqualTo("model-1");
        assertThat(has(Effect.Kind.STOP_SOURCE)).isTrue();
        assertThat(sink.events).containsExactly(AuditSink.Event.LIVENESS_STARTED, AuditSink.Event.LIVENESS_PASSED);
        assertThat(sink.details.get(1)).containsEntry(AuditRecorder.Keys.PASSIVE_OUTCOME, "PASS");
    }

    @Test
    void passiveAttackFailsWithPadAndNeverOffersAChallenge() {
        start();
        frames(10, 0.1f);
        LivenessEvent failed = last(Type.ATTEMPT_FAILED);
        assertThat(failed.error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(failed.retriesLeft).isEqualTo(3);
        assertThat(active.started).isNull();
        assertThat(sm.state()).isEqualTo(State.RETRY_AVAILABLE);
        assertThat(sink.events).contains(AuditSink.Event.PAD_ATTACK_SUSPECTED, AuditSink.Event.LIVENESS_ATTEMPT_FAILED);
    }

    @Test
    void inconclusivePassiveStartsChallengesWithSeedAndPassesViaActive() {
        start();
        frames(10, 0.6f);
        assertThat(sm.state()).isEqualTo(State.ACTIVE_CHALLENGE);
        assertThat(active.started).hasSize(2).doesNotHaveDuplicates();
        assertThat(active.seedFrames).isEqualTo(10);

        ChallengeType c1 = active.started.get(0), c2 = active.started.get(1);
        Frame best = Fa.at(99999, 99999).build().frame;
        active.then(ActiveStep.prompt(c1, 1, 2));
        active.then(ActiveStep.progress(c1, 1, 2, 0.5f));
        active.then(ActiveStep.actionDetected(c1, 1, 2, 1500), ActiveStep.prompt(c2, 2, 2));
        active.then(ActiveStep.actionDetected(c2, 2, 2, 1200), ActiveStep.holdStill());
        active.then(ActiveStep.passed(best));
        frames(5, 0.6f);

        assertThat(events()).containsSubsequence(Type.CHALLENGE_PROMPT, Type.CHALLENGE_PROGRESS, Type.ACTION_DETECTED,
                Type.CHALLENGE_PROMPT, Type.ACTION_DETECTED, Type.HOLD_STILL, Type.PASSED);
        assertThat(last(Type.CHALLENGE_PROMPT).timeoutMs).isEqualTo(6000);
        LivenessResult r = result();
        assertThat(r.passPath).isEqualTo(LivenessResult.PassPath.ACTIVE_CHALLENGE);
        assertThat(r.bestFrame).isSameAs(best);
        assertThat(r.challengesIssued).containsExactly(c1, c2);
        assertThat(r.passiveScore).isEqualTo(0.6f);
        Map<String, String> passed = sink.details.get(sink.events.indexOf(AuditSink.Event.LIVENESS_PASSED));
        assertThat(passed.get(AuditRecorder.Keys.CHALLENGES)).isEqualTo(c1 + ":DETECTED:1500," + c2 + ":DETECTED:1200");
    }

    @Test
    void padDuringChallengeFailsTheAttempt() {
        start();
        frames(10, 0.6f);
        ChallengeType c1 = active.started.get(0);
        active.then(ActiveStep.prompt(c1, 1, 2));
        active.then(ActiveStep.failed(ErrorCode.PAD_FAILURE, c1, ChallengeOutcome.PAD_FAILURE, 800, 0.12f));
        frames(2, 0.1f);
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(sink.events).contains(AuditSink.Event.PAD_ATTACK_SUSPECTED);
        assertThat(sm.state()).isEqualTo(State.RETRY_AVAILABLE);
    }

    @Test
    void activeDisabledFailsBelowThreshold() {
        policy.activeEnabled = false;
        rebuild();
        start();
        frames(10, 0.6f);
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.LIVENESS_BELOW_THRESHOLD);
        assertThat(active.started).isNull();
    }

    @Test
    void passiveTimeoutUsesDominantReason() {
        start();
        // Frames keep arriving (so this is not a stalled device), but none has a face.
        for (int i = 0; i < 63; i++) frame(fa().faces(0)); // 63 * 125 ms < 8000 ms
        assertThat(events()).doesNotContain(Type.ATTEMPT_FAILED);
        frame(fa().faces(0));
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.FACE_NOT_DETECTED);
    }

    @Test
    void guidanceIsShownThenClearedWithCheckingPassive() {
        start();
        for (int i = 0; i < 4; i++) frame(fa().issue(FrameAnalysis.QualityIssue.TOO_DARK));
        assertThat(last(Type.GUIDANCE).guidance).isEqualTo(LivenessEvent.Guidance.IMPROVE_LIGHTING);
        frames(4, 0.6f);
        assertThat(events()).containsSubsequence(Type.CHECKING_PASSIVE, Type.GUIDANCE, Type.CHECKING_PASSIVE);
    }

    @Test
    void persistentMultipleFacesFailsQuickly() {
        start();
        for (int i = 0; i < 14; i++) frame(fa().faces(2)); // 14 * 125 ms = 1750 ms >= 1500
        assertThat(events()).contains(Type.GUIDANCE);
        assertThat(last(Type.GUIDANCE).guidance).isEqualTo(LivenessEvent.Guidance.MULTIPLE_FACES);
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.MULTIPLE_FACES);
    }

    // ------------------------------------------------------------------ retries

    @Test
    void retriesThenMaxRetriesExceededWithLastCause() {
        start();
        for (int attempt = 1; attempt <= 4; attempt++) {
            frames(10, 0.1f);
            if (attempt < 4) {
                assertThat(last(Type.ATTEMPT_FAILED).retriesLeft).isEqualTo(4 - attempt);
                record(sm.onRetry(now));
                assertThat(last(Type.STARTED).attemptNumber).isEqualTo(attempt + 1);
            }
        }
        LivenessEvent fin = last(Type.FAILED_FINAL);
        assertThat(fin.error).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(fin.recoverable).isFalse();
        LivenessResult r = result();
        assertThat(r.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(r.attempts).isEqualTo(4);
        assertThat(has(Effect.Kind.TRIP_COOLDOWN)).as("resident policy is BLOCK").isFalse();
        Map<String, String> finalAudit = sink.details.get(sink.events.lastIndexOf(AuditSink.Event.LIVENESS_FAILED_FINAL));
        assertThat(finalAudit).containsEntry(AuditRecorder.Keys.LAST_CAUSE, "PAD_FAILURE");
    }

    @Test
    void operatorCooldownIsRequestedAfterMaxRetries() {
        policy.onRepeatedFailure = LivenessPolicy.OnRepeatedFailure.COOLDOWN;
        policy.maxRetries = 0;
        rebuild();
        start();
        frames(10, 0.1f);
        assertThat(result().failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(has(Effect.Kind.TRIP_COOLDOWN)).isTrue();
    }

    @Test
    void retryGetsFreshSequenceAndIsIgnoredWhenNotFailed() {
        record(sm.onRetry(now));
        start();
        record(sm.onRetry(now));
        assertThat(events()).containsExactly(Type.STARTED);
        frames(10, 0.6f);
        List<ChallengeType> first = active.started;
        active.then(ActiveStep.failed(ErrorCode.CHALLENGE_TIMEOUT, first.get(0), ChallengeOutcome.TIMEOUT, 6000, Float.NaN));
        frames(1, 0.6f);
        record(sm.onRetry(now));
        frames(10, 0.6f);
        assertThat(active.started).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ stream and analyzer problems

    @Test
    void invalidFramesFailTheAttemptAfterTheLimit() {
        start();
        frame(Fa.at(5000, 10));
        for (int i = 0; i < 3; i++) record(sm.onFrame(Fa.at(5000, 10).build(), now)); // exact replay
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.INVALID_FRAME);
    }

    @Test
    void engineErrorsFailWithoutConsumingARetryButAreCapped() {
        start();
        for (int i = 0; i < 3; i++) record(sm.onAnalyzerError(ErrorCode.ENGINE_ERROR, now));
        LivenessEvent failed = last(Type.ATTEMPT_FAILED);
        assertThat(failed.error).isEqualTo(ErrorCode.ENGINE_ERROR);
        assertThat(failed.retriesLeft).isEqualTo(3);
        record(sm.onRetry(now));
        for (int i = 0; i < 3; i++) record(sm.onAnalyzerError(ErrorCode.ENGINE_ERROR, now));
        assertThat(result().failureCode).isEqualTo(ErrorCode.ENGINE_ERROR);
    }

    @Test
    void goodFrameResetsTheEngineErrorCount() {
        start();
        for (int i = 0; i < 10; i++) {
            record(sm.onAnalyzerError(ErrorCode.ENGINE_ERROR, now));
            record(sm.onAnalyzerError(ErrorCode.ENGINE_ERROR, now));
            frame(fa().score(0.6f));
        }
        assertThat(events()).doesNotContain(Type.ATTEMPT_FAILED);
    }

    @Test
    void modelIntegrityFailureIsImmediatelyFinal() {
        start();
        record(sm.onAnalyzerError(ErrorCode.MODEL_INTEGRITY_FAILED, now));
        assertThat(result().failureCode).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED);
        assertThat(sink.events).contains(AuditSink.Event.MODEL_INTEGRITY_FAILED);
    }

    // ------------------------------------------------------------------ device

    @Test
    void recoverableDeviceErrorAutoResumesWithinGraceWithoutUsingARetry() {
        start();
        frames(3, 0.9f);
        record(sm.onSourceError(ErrorCode.DEVICE_DISCONNECTED, true, "usb", now));
        LivenessEvent dev = last(Type.DEVICE_ERROR);
        assertThat(dev.recoverable).isTrue();
        assertThat(dev.retriesLeft).isEqualTo(3);
        assertThat(sm.state()).isEqualTo(State.DEVICE_WAIT);
        now += 1000;
        frames(10, 0.9f); // stream resumed with continuing sequence numbers
        assertThat(result().passed()).isTrue();
        assertThat(has(Effect.Kind.RESTART_SOURCE)).isFalse();
        assertThat(sink.events).contains(AuditSink.Event.DEVICE_ERROR);
    }

    @Test
    void afterGraceOnlyRetryRestartsTheSource() {
        start();
        record(sm.onSourceError(ErrorCode.DEVICE_DISCONNECTED, true, "usb", now));
        now += tuning.deviceGraceMs + 1;
        frames(3, 0.9f);
        assertThat(sm.state()).isEqualTo(State.DEVICE_WAIT);
        record(sm.onRetry(now));
        assertThat(has(Effect.Kind.RESTART_SOURCE)).isTrue();
        seq = 0; // restarted source may restart its sequence numbers
        frames(10, 0.9f);
        assertThat(result().passed()).isTrue();
        assertThat(result().attempts).as("a device problem is not a liveness attempt").isEqualTo(1);
    }

    @Test
    void nonRecoverableDeviceErrorEndsWithTheDeviceCode() {
        start();
        record(sm.onSourceError(ErrorCode.CAMERA_UNAVAILABLE, false, "permission", now));
        assertThat(result().failureCode).isEqualTo(ErrorCode.CAMERA_UNAVAILABLE);
        LivenessEvent dev = last(Type.DEVICE_ERROR);
        assertThat(dev.recoverable).isFalse();
        assertThat(events().subList(events().size() - 2, events().size())).containsExactly(Type.DEVICE_ERROR, Type.FAILED_FINAL);
        assertThat(last(Type.FAILED_FINAL).error).isEqualTo(ErrorCode.CAMERA_UNAVAILABLE);
    }

    @Test
    void noFramesAfterStartIsDeviceUnavailable() {
        start();
        tick(tuning.startTimeoutMs - 1, -1);
        assertThat(events()).containsExactly(Type.STARTED);
        tick(1, -1);
        assertThat(last(Type.DEVICE_ERROR).error).isEqualTo(ErrorCode.DEVICE_UNAVAILABLE);
    }

    @Test
    void stalledStreamMidAttemptIsDisconnected() {
        start();
        frames(3, 0.6f);
        long lastArrival = now;
        tick(tuning.frameStallMs, lastArrival);
        assertThat(last(Type.DEVICE_ERROR).error).isEqualTo(ErrorCode.DEVICE_DISCONNECTED);
    }

    @Test
    void recoverableErrorWhileWaitingForRetryRestartsSourceOnRetry() {
        start();
        frames(10, 0.1f);
        record(sm.onSourceError(ErrorCode.DEVICE_DISCONNECTED, true, "usb", now));
        assertThat(sm.state()).isEqualTo(State.RETRY_AVAILABLE);
        record(sm.onRetry(now));
        assertThat(has(Effect.Kind.RESTART_SOURCE)).isTrue();
    }

    // ------------------------------------------------------------------ cancel and terminal states

    @Test
    void cancelInEveryStateCompletesOnceAndThenIgnoresEverything() {
        for (State target : new State[] {State.IDLE, State.STARTING, State.PASSIVE_CHECK, State.ACTIVE_CHALLENGE,
                State.RETRY_AVAILABLE, State.DEVICE_WAIT}) {
            active = new ScriptedActive();
            rebuild();
            driveTo(target);
            assertThat(sm.state()).isEqualTo(target);
            effects.clear();
            record(sm.onCancel(now));
            assertThat(result().decision).isEqualTo(LivenessResult.Decision.CANCELLED);
            assertThat(result().failureCode).isEqualTo(ErrorCode.CANCELLED);
            assertThat(events()).isEmpty();
            effects.clear();
            record(sm.onCancel(now));
            frames(10, 0.95f);
            record(sm.onRetry(now));
            record(sm.onSourceError(ErrorCode.DEVICE_DISCONNECTED, false, "x", now));
            record(sm.onAnalyzerError(ErrorCode.MODEL_INTEGRITY_FAILED, now));
            tick(100000, -1);
            assertThat(effects).as("terminal after cancel in " + target).isEmpty();
        }
    }

    private void driveTo(State target) {
        switch (target) {
            case IDLE: return;
            case STARTING: start(); return;
            case PASSIVE_CHECK: start(); frames(2, 0.6f); return;
            case ACTIVE_CHALLENGE: start(); frames(10, 0.6f); return;
            case RETRY_AVAILABLE: start(); frames(10, 0.1f); return;
            case DEVICE_WAIT: start(); record(sm.onSourceError(ErrorCode.DEVICE_DISCONNECTED, true, "x", now)); return;
            default: throw new IllegalArgumentException();
        }
    }

    @Test
    void noSupportedChallengesIsAnEngineError() {
        active.supported = EnumSet.of(ChallengeType.LOOK_UP); // not in the default pool
        start();
        frames(10, 0.6f);
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.ENGINE_ERROR);
    }

    /** Random inputs: at most one COMPLETE, nothing after it, and PASSED only via a legitimate path. */
    @Test
    void fuzzedInputsKeepTheInvariants() {
        Random r = new Random(1234);
        for (int run = 0; run < 300; run++) {
            active = new ScriptedActive();
            rebuild();
            start();
            boolean terminal = false;
            for (int step = 0; step < 200; step++) {
                List<Effect> out;
                int k = r.nextInt(100);
                if (k < 70) {
                    Fa f = fa().score(r.nextFloat()).faces(r.nextInt(10) == 0 ? r.nextInt(3) : 1)
                            .yaw(r.nextInt(5) == 0 ? 40 : 0);
                    if (sm.inActiveChallenge() && r.nextInt(4) == 0) {
                        // The stand-in reports random outcomes; PASSED only ever comes from the active phase.
                        ActiveStep s = r.nextBoolean() ? ActiveStep.progress(ChallengeType.BLINK, 1, 2, 0.5f)
                                : ActiveStep.failed(ErrorCode.CHALLENGE_FAILED, ChallengeType.BLINK,
                                ChallengeOutcome.WRONG_ACTION, 100, Float.NaN);
                        active.then(s);
                    }
                    out = frame(f);
                } else if (k < 80) {
                    out = tick(r.nextInt(3000), r.nextBoolean() ? now : -1);
                } else if (k < 88) {
                    out = record(sm.onRetry(now));
                } else if (k < 93) {
                    out = record(sm.onAnalyzerError(r.nextBoolean() ? ErrorCode.ENGINE_ERROR : ErrorCode.INVALID_FRAME, now));
                } else if (k < 98) {
                    out = record(sm.onSourceError(ErrorCode.DEVICE_DISCONNECTED, r.nextInt(5) > 0, "fuzz", now));
                } else {
                    out = record(sm.onCancel(now));
                }
                if (terminal) assertThat(out).as("no effects after terminal").isEmpty();
                for (Effect e : out) {
                    if (e.kind == Effect.Kind.COMPLETE && e.result.passed()) {
                        // Only a passive pass is possible here: the stand-in never reports PASSED.
                        assertThat(e.result.passPath).isEqualTo(LivenessResult.PassPath.PASSIVE_ONLY);
                        assertThat(e.result.passiveScore).isGreaterThanOrEqualTo(policy.passiveThreshold);
                    }
                }
                terminal = sm.isTerminal();
            }
            int completes = 0;
            for (Effect e : effects) if (e.kind == Effect.Kind.COMPLETE) completes++;
            assertThat(completes).isLessThanOrEqualTo(1);
            assertThat(completes == 1).isEqualTo(sm.isTerminal());
        }
    }
}
