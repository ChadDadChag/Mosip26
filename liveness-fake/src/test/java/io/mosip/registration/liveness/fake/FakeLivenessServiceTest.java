package io.mosip.registration.liveness.fake;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessResult.Decision;
import io.mosip.registration.liveness.api.LivenessResult.PassPath;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.util.SeededRandomSource;
import io.mosip.registration.liveness.core.util.TimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeLivenessServiceTest {

    private static final String P = LivenessPolicy.PREFIX;

    // ------------------------------------------------------------------ helpers

    static final class MapConfig implements ConfigProvider {
        final Map<String, String> m = new HashMap<String, String>();
        MapConfig put(String k, String v) { m.put(k, v); return this; }
        @Override public String get(String key) { return m.get(key); }
    }

    static final class ManualClock implements TimeSource {
        volatile long now = 1000;
        @Override public long nowMs() { return now; }
    }

    static final class RecordingAudit implements AuditSink {
        final List<AuditSink.Event> events = Collections.synchronizedList(new ArrayList<AuditSink.Event>());
        final List<Map<String, String>> details = Collections.synchronizedList(new ArrayList<Map<String, String>>());
        @Override
        public void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> d) {
            events.add(event);
            details.add(d);
        }
    }

    private static FakeLivenessService service(FakeScenario scenario) {
        return FakeLivenessService.builder(new MapConfig()).scenario(scenario).timeScale(0)
                .random(new SeededRandomSource(11)).build();
    }

    private static RecordingListener run(LivenessSession session, boolean autoRetry) {
        RecordingListener l = new RecordingListener();
        l.session = session;
        l.autoRetry = autoRetry;
        session.start(null, l);
        return l;
    }

    /** Invariants every session must satisfy, whatever the scenario. */
    private static void assertWellFormed(RecordingListener l, LivenessSession s) throws InterruptedException {
        assertThat(((FakeLivenessSession) s).awaitTermination(5000)).isTrue();
        assertThat(l.completions.get()).as("exactly one onComplete").isEqualTo(1);
        List<Object> timeline = new ArrayList<Object>(l.timeline);
        assertThat(timeline.get(timeline.size() - 1)).as("nothing after onComplete").isInstanceOf(LivenessResult.class);
        LivenessResult r = l.result;
        assertThat(r.sessionId).isEqualTo(s.sessionId());
        if (r.decision == Decision.PASSED) {
            assertThat(r.failureCode).isNull();
            assertThat(r.bestFrame).isNotNull();
            assertThat(lastEvent(timeline).type).isEqualTo(Type.PASSED);
        } else if (r.decision == Decision.FAILED) {
            assertThat(r.failureCode).isNotNull();
            LivenessEvent last = lastEvent(timeline);
            assertThat(last.type).isEqualTo(Type.FAILED_FINAL);
            assertThat(last.error).isEqualTo(r.failureCode);
            assertThat(last.recoverable).isFalse();
        }
        // A cancel that lands before the worker's first event legitimately produces no events at all.
        if (!l.types().isEmpty()) assertThat(l.types().get(0)).isEqualTo(Type.STARTED);
        if (l.callbackThread != null) assertThat(l.callbackThread).startsWith("liveness-events-");
    }

    private static LivenessEvent lastEvent(List<Object> timeline) {
        for (int i = timeline.size() - 1; i >= 0; i--) {
            if (timeline.get(i) instanceof LivenessEvent) return (LivenessEvent) timeline.get(i);
        }
        throw new AssertionError("no events");
    }

    // ------------------------------------------------------------------ every scenario

    static Stream<Arguments> scenarios() {
        // scenario, decision, failureCode, attempts, passPath, challengesIssued, ATTEMPT_FAILED error (if any)
        return Stream.of(
                Arguments.of(FakeScenario.PASS_PASSIVE, Decision.PASSED, null, 1, PassPath.PASSIVE_ONLY, 0, null),
                Arguments.of(FakeScenario.PASS_ACTIVE, Decision.PASSED, null, 1, PassPath.ACTIVE_CHALLENGE, 2, null),
                Arguments.of(FakeScenario.PASS_AFTER_1_CHALLENGE, Decision.PASSED, null, 1, PassPath.ACTIVE_CHALLENGE, 1, null),
                Arguments.of(FakeScenario.PASS_AFTER_2_CHALLENGES, Decision.PASSED, null, 1, PassPath.ACTIVE_CHALLENGE, 2, null),
                Arguments.of(FakeScenario.FAIL_CHALLENGE_THEN_PASS_ON_RETRY, Decision.PASSED, null, 2, PassPath.ACTIVE_CHALLENGE, 2, ErrorCode.CHALLENGE_FAILED),
                Arguments.of(FakeScenario.PAD_ATTACK, Decision.FAILED, ErrorCode.MAX_RETRIES_EXCEEDED, 4, PassPath.NONE, 0, ErrorCode.PAD_FAILURE),
                Arguments.of(FakeScenario.CHALLENGE_TIMEOUT, Decision.PASSED, null, 2, PassPath.ACTIVE_CHALLENGE, 2, ErrorCode.CHALLENGE_TIMEOUT),
                Arguments.of(FakeScenario.NO_FACE_THEN_PASS, Decision.PASSED, null, 1, PassPath.PASSIVE_ONLY, 0, null),
                Arguments.of(FakeScenario.MULTIPLE_FACES, Decision.PASSED, null, 2, PassPath.ACTIVE_CHALLENGE, 2, ErrorCode.MULTIPLE_FACES),
                Arguments.of(FakeScenario.POOR_LIGHTING_HINTS, Decision.PASSED, null, 1, PassPath.ACTIVE_CHALLENGE, 2, null),
                Arguments.of(FakeScenario.DEVICE_DISCONNECT, Decision.PASSED, null, 1, PassPath.ACTIVE_CHALLENGE, 2, null),
                Arguments.of(FakeScenario.DEVICE_FAILURE, Decision.FAILED, ErrorCode.DEVICE_CONNECTION_FAILED, 1, PassPath.NONE, 0, null),
                Arguments.of(FakeScenario.MAX_RETRIES_EXCEEDED, Decision.FAILED, ErrorCode.MAX_RETRIES_EXCEEDED, 4, PassPath.NONE, 2, ErrorCode.CHALLENGE_FAILED),
                Arguments.of(FakeScenario.ENGINE_ERROR, Decision.FAILED, ErrorCode.ENGINE_ERROR, 2, PassPath.NONE, 0, ErrorCode.ENGINE_ERROR));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyScenarioEndsInExactlyOneExpectedResult(FakeScenario scenario, Decision decision, ErrorCode code,
                                                     int attempts, PassPath path, int challenges,
                                                     ErrorCode firstAttemptError) throws Exception {
        LivenessSession s = service(scenario).newSession(WorkflowType.RESIDENT);
        RecordingListener l = run(s, true);
        LivenessResult r = l.await();
        assertWellFormed(l, s);

        assertThat(r.decision).isEqualTo(decision);
        assertThat(r.failureCode).isEqualTo(code);
        assertThat(r.attempts).isEqualTo(attempts);
        assertThat(r.passPath).isEqualTo(path);
        assertThat(r.challengesIssued).hasSize(challenges);
        assertThat(r.modelVersion).isEqualTo(FakeLivenessService.MODEL_VERSION);

        List<LivenessEvent> failures = l.ofType(Type.ATTEMPT_FAILED);
        if (firstAttemptError == null) {
            assertThat(failures).isEmpty();
        } else {
            assertThat(failures.get(0).error).isEqualTo(firstAttemptError);
            assertThat(failures.get(0).retriesLeft).isEqualTo(3);
            assertThat(failures.get(0).recoverable).isTrue();
        }
    }

    @Test
    void padFailureOnlyEverMapsToTheGenericMessage() throws Exception {
        RecordingListener l = run(service(FakeScenario.PAD_ATTACK).newSession(WorkflowType.RESIDENT), true);
        l.await();
        List<LivenessEvent> failures = l.ofType(Type.ATTEMPT_FAILED);
        assertThat(failures).hasSize(3);
        for (LivenessEvent e : failures) {
            assertThat(e.error.userMessage()).isEqualTo(ErrorCode.UserMessage.VERIFICATION_NOT_COMPLETED);
        }
        assertThat(failures).extracting("retriesLeft").containsExactly(3, 2, 1);
    }

    @Test
    void challengePromptsCarryIndexCountAndPolicyTimeout() throws Exception {
        RecordingListener l = run(service(FakeScenario.PASS_ACTIVE).newSession(WorkflowType.RESIDENT), true);
        l.await();
        List<LivenessEvent> prompts = l.ofType(Type.CHALLENGE_PROMPT);
        assertThat(prompts).extracting("challengeIndex").containsExactly(1, 2);
        assertThat(prompts).extracting("challengeCount").containsExactly(2, 2);
        assertThat(prompts).extracting("timeoutMs").containsExactly(6000L, 6000L);
        assertThat(prompts.get(0).challenge).isNotEqualTo(prompts.get(1).challenge);
        assertThat(l.types()).containsSubsequence(Type.STARTED, Type.CHECKING_PASSIVE, Type.CHALLENGE_PROMPT,
                Type.CHALLENGE_PROGRESS, Type.ACTION_DETECTED, Type.CHALLENGE_PROMPT, Type.ACTION_DETECTED,
                Type.HOLD_STILL, Type.PASSED);
    }

    @Test
    void retryGetsAFreshChallengeSequence() throws Exception {
        LivenessSession s = service(FakeScenario.FAIL_CHALLENGE_THEN_PASS_ON_RETRY).newSession(WorkflowType.RESIDENT);
        RecordingListener l = run(s, true);
        l.await();
        List<LivenessEvent> prompts = l.ofType(Type.CHALLENGE_PROMPT);
        // attempt 1: first prompt of its sequence (then failed); attempt 2: two prompts
        assertThat(prompts).hasSize(3);
        assertThat(l.ofType(Type.STARTED)).extracting("attemptNumber").containsExactly(1, 2);
    }

    // ------------------------------------------------------------------ policy per workflow

    @Test
    void operatorAndSupervisorGetOneChallengeResidentTwo() throws Exception {
        FakeLivenessService svc = service(FakeScenario.PASS_ACTIVE);
        assertThat(run(svc.newSession(WorkflowType.RESIDENT), true).await().challengesIssued).hasSize(2);
        assertThat(run(svc.newSession(WorkflowType.OPERATOR), true).await().challengesIssued).hasSize(1);
        assertThat(run(svc.newSession(WorkflowType.SUPERVISOR), true).await().challengesIssued).hasSize(1);
    }

    @Test
    void disabledWorkflowReturnsNullSession() {
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig().put(P + "supervisor.enabled", "false"))
                .timeScale(0).build();
        assertThat(svc.newSession(WorkflowType.SUPERVISOR)).isNull();
        assertThat(svc.newSession(WorkflowType.RESIDENT)).isNotNull();
    }

    @Test
    void reloadConfigAppliesToNewSessionsOnly() throws Exception {
        FakeLivenessService svc = service(FakeScenario.PASS_ACTIVE);
        LivenessSession before = svc.newSession(WorkflowType.RESIDENT);
        svc.reloadConfig(new MapConfig().put(P + "resident.minChallenges", "3"));
        assertThat(svc.policy(WorkflowType.RESIDENT).minChallenges).isEqualTo(3);
        assertThat(run(before, true).await().challengesIssued).hasSize(2);
        assertThat(run(svc.newSession(WorkflowType.RESIDENT), true).await().challengesIssued).hasSize(3);
    }

    @Test
    void policyReturnsADefensiveCopy() {
        FakeLivenessService svc = service(FakeScenario.PASS_ACTIVE);
        svc.policy(WorkflowType.RESIDENT).maxRetries = 0;
        assertThat(svc.policy(WorkflowType.RESIDENT).maxRetries).isEqualTo(3);
    }

    @Test
    void activeDisabledMeansInconclusivePassiveFails() throws Exception {
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig().put(P + "default.activeEnabled", "false"))
                .scenario(FakeScenario.PASS_ACTIVE).timeScale(0).build();
        RecordingListener l = run(svc.newSession(WorkflowType.RESIDENT), true);
        LivenessResult r = l.await();
        assertThat(r.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(l.ofType(Type.ATTEMPT_FAILED).get(0).error).isEqualTo(ErrorCode.LIVENESS_BELOW_THRESHOLD);
        assertThat(l.ofType(Type.CHALLENGE_PROMPT)).isEmpty();
    }

    @Test
    void operatorCooldownRefusesSessionsUntilItExpires() throws Exception {
        ManualClock clock = new ManualClock();
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig())
                .scenario(FakeScenario.MAX_RETRIES_EXCEEDED).timeScale(0).clock(clock)
                .random(new SeededRandomSource(3)).build();

        assertThat(run(svc.newSession(WorkflowType.OPERATOR), true).await().failureCode)
                .isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);

        // Refused: never opens the camera, final immediately.
        svc.setScenario(FakeScenario.PASS_PASSIVE);
        TestFrameSource src = new TestFrameSource();
        LivenessSession refused = svc.newSession(WorkflowType.OPERATOR);
        RecordingListener l = new RecordingListener();
        refused.start(src, l);
        LivenessResult r = l.await();
        assertThat(r.decision).isEqualTo(Decision.FAILED);
        assertThat(r.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(r.attempts).isZero();
        assertThat(l.types()).containsExactly(Type.FAILED_FINAL);
        assertThat(src.starts.get()).isZero();

        // Resident (BLOCK) is unaffected; operator is fine again after cooldownSec.
        assertThat(run(svc.newSession(WorkflowType.RESIDENT), true).await().passed()).isTrue();
        clock.now += 60000;
        assertThat(run(svc.newSession(WorkflowType.OPERATOR), true).await().passed()).isTrue();
    }

    @Test
    void residentMaxRetriesDoesNotTriggerCooldown() throws Exception {
        FakeLivenessService svc = service(FakeScenario.MAX_RETRIES_EXCEEDED);
        run(svc.newSession(WorkflowType.RESIDENT), true).await();
        svc.setScenario(FakeScenario.PASS_PASSIVE);
        assertThat(run(svc.newSession(WorkflowType.RESIDENT), true).await().passed()).isTrue();
    }

    // ------------------------------------------------------------------ cancel and retry

    @Test
    void cancelFromInsideCallbackMidChallengeDeliversCancelledAndNothingAfter() throws Exception {
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig()).scenario(FakeScenario.PASS_ACTIVE)
                .timeScale(1.0).build();
        final LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        final TestFrameSource src = new TestFrameSource();
        RecordingListener l = new RecordingListener() {
            @Override
            public void onEvent(LivenessEvent e) {
                super.onEvent(e);
                if (e.type == Type.CHALLENGE_PROMPT) s.cancel();
            }
        };
        long t0 = System.nanoTime();
        s.start(src, l);
        LivenessResult r = l.await();
        assertWellFormed(l, s);
        assertThat(r.decision).isEqualTo(Decision.CANCELLED);
        assertThat(r.failureCode).isEqualTo(ErrorCode.CANCELLED);
        assertThat(src.stops.get()).isGreaterThanOrEqualTo(1);
        assertThat(lastEvent(new ArrayList<Object>(l.timeline)).type).isEqualTo(Type.CHALLENGE_PROMPT);
        // Checking (1.5 s) + jitter + prompt, not the whole 2-challenge script (~7 s): cancel wakes the worker.
        assertThat((System.nanoTime() - t0) / 1000000L).isLessThan(4000);
    }

    @Test
    void cancelWhileWaitingForRetry() throws Exception {
        LivenessSession s = service(FakeScenario.PAD_ATTACK).newSession(WorkflowType.RESIDENT);
        RecordingListener l = run(s, false);
        while (l.ofType(Type.ATTEMPT_FAILED).isEmpty()) Thread.yield();
        s.cancel();
        s.cancel();
        s.retry();
        assertThat(l.await().decision).isEqualTo(Decision.CANCELLED);
        assertWellFormed(l, s);
        assertThat(l.ofType(Type.STARTED)).hasSize(1);
    }

    @Test
    void cancelBeforeStartCompletesOnStartWithoutOpeningTheCamera() throws Exception {
        LivenessSession s = service(FakeScenario.PASS_PASSIVE).newSession(WorkflowType.RESIDENT);
        s.cancel();
        TestFrameSource src = new TestFrameSource();
        RecordingListener l = new RecordingListener();
        s.start(src, l);
        assertThat(l.await().decision).isEqualTo(Decision.CANCELLED);
        assertThat(l.events).isEmpty();
        assertThat(src.starts.get()).isZero();
    }

    @Test
    void cancelAfterCompletionIsANoOp() throws Exception {
        LivenessSession s = service(FakeScenario.PASS_PASSIVE).newSession(WorkflowType.RESIDENT);
        RecordingListener l = run(s, true);
        assertThat(l.await().passed()).isTrue();
        s.cancel();
        assertWellFormed(l, s);
        assertThat(l.result.decision).isEqualTo(Decision.PASSED);
    }

    @Test
    void cancelRacingFromOtherThreadsAlwaysYieldsExactlyOneResult() throws Exception {
        for (int i = 0; i < 100; i++) {
            final LivenessSession s = service(i % 2 == 0 ? FakeScenario.PASS_PASSIVE : FakeScenario.PAD_ATTACK)
                    .newSession(WorkflowType.RESIDENT);
            RecordingListener l = run(s, true);
            Thread a = new Thread(new Runnable() { @Override public void run() { s.cancel(); } });
            Thread b = new Thread(new Runnable() { @Override public void run() { s.cancel(); } });
            a.start(); b.start(); a.join(); b.join();
            l.await();
            assertWellFormed(l, s);
        }
    }

    @Test
    void retryIsIgnoredUnlessAnAttemptFailed() throws Exception {
        LivenessSession s = service(FakeScenario.PASS_PASSIVE).newSession(WorkflowType.RESIDENT);
        RecordingListener l = run(s, false);
        s.retry();
        s.retry();
        assertThat(l.await().attempts).isEqualTo(1);
        assertThat(l.ofType(Type.STARTED)).hasSize(1);
    }

    @Test
    void startTwiceIsRejected() {
        LivenessSession s = service(FakeScenario.PASS_PASSIVE).newSession(WorkflowType.RESIDENT);
        s.start(null, new RecordingListener());
        assertThatThrownBy(() -> s.start(null, new RecordingListener())).isInstanceOf(IllegalStateException.class);
        s.cancel();
    }

    // ------------------------------------------------------------------ real FrameSource

    @Test
    void realSourceIsStartedStoppedAndItsLatestFrameIsTheBestFrame() throws Exception {
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig()).scenario(FakeScenario.PASS_PASSIVE)
                .timeScale(0.1).build();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        TestFrameSource src = new TestFrameSource();
        RecordingListener l = new RecordingListener();
        s.start(src, l);
        src.push(1);
        Frame last = src.push(2);
        LivenessResult r = l.await();
        assertThat(r.bestFrame).isSameAs(last);
        assertThat(src.starts.get()).isEqualTo(1);
        assertThat(src.stops.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void recoverableDeviceErrorFromRealSourceDoesNotConsumeARetry() throws Exception {
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig()).scenario(FakeScenario.PASS_PASSIVE)
                .timeScale(0.1).build();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        TestFrameSource src = new TestFrameSource();
        RecordingListener l = new RecordingListener();
        l.session = s;
        s.start(src, l);
        src.fail(ErrorCode.DEVICE_DISCONNECTED, true);
        LivenessResult r = l.await();
        assertThat(r.passed()).isTrue();
        LivenessEvent dev = l.ofType(Type.DEVICE_ERROR).get(0);
        assertThat(dev.error).isEqualTo(ErrorCode.DEVICE_DISCONNECTED);
        assertThat(dev.recoverable).isTrue();
        assertThat(dev.retriesLeft).isEqualTo(3);
        assertThat(l.ofType(Type.ATTEMPT_FAILED)).isEmpty();
        assertThat(src.starts.get()).as("source restarted on Retry").isEqualTo(2);
    }

    @Test
    void nonRecoverableDeviceErrorEndsTheSessionWithTheDeviceCode() throws Exception {
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig()).scenario(FakeScenario.PASS_PASSIVE)
                .timeScale(0.1).build();
        LivenessSession s = svc.newSession(WorkflowType.OPERATOR);
        TestFrameSource src = new TestFrameSource();
        RecordingListener l = new RecordingListener();
        s.start(src, l);
        src.fail(ErrorCode.CAMERA_UNAVAILABLE, false);
        LivenessResult r = l.await();
        assertWellFormed(l, s);
        assertThat(r.failureCode).isEqualTo(ErrorCode.CAMERA_UNAVAILABLE);
        assertThat(r.failureCode.userMessage()).isEqualTo(ErrorCode.UserMessage.DEVICE_PROBLEM);
        LivenessEvent dev = l.ofType(Type.DEVICE_ERROR).get(0);
        assertThat(dev.recoverable).isFalse();
        assertThat(dev.error).isEqualTo(ErrorCode.CAMERA_UNAVAILABLE);
    }

    // ------------------------------------------------------------------ scenario selection and audit

    @Test
    void scenarioComesFromSystemPropertyThenConfigThenDefault() {
        MapConfig cfg = new MapConfig();
        FakeLivenessService svc = FakeLivenessService.builder(cfg).timeScale(0).build();
        String old = System.getProperty(FakeScenario.SYSTEM_PROPERTY);
        try {
            System.clearProperty(FakeScenario.SYSTEM_PROPERTY);
            assertThat(svc.configuredScenario()).isEqualTo(FakeScenario.PASS_ACTIVE);
            cfg.put(FakeScenario.CONFIG_KEY, "pad_attack");
            assertThat(svc.configuredScenario()).isEqualTo(FakeScenario.PAD_ATTACK);
            System.setProperty(FakeScenario.SYSTEM_PROPERTY, "NO_FACE_THEN_PASS");
            assertThat(svc.configuredScenario()).isEqualTo(FakeScenario.NO_FACE_THEN_PASS);
            svc.setScenario(FakeScenario.ENGINE_ERROR);
            assertThat(svc.configuredScenario()).isEqualTo(FakeScenario.ENGINE_ERROR);
        } finally {
            if (old == null) System.clearProperty(FakeScenario.SYSTEM_PROPERTY);
            else System.setProperty(FakeScenario.SYSTEM_PROPERTY, old);
        }
    }

    @ParameterizedTest
    @EnumSource(WorkflowType.class)
    void randomScenarioNeverResolvesToRandom(WorkflowType wf) {
        FakeLivenessService svc = service(FakeScenario.RANDOM);
        for (int i = 0; i < 200; i++) {
            FakeLivenessSession s = (FakeLivenessSession) svc.newSession(wf);
            assertThat(s.scenario()).isNotEqualTo(FakeScenario.RANDOM);
        }
    }

    @Test
    void auditHasOutcomesButNothingImageLike() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig()).scenario(FakeScenario.PAD_ATTACK)
                .timeScale(0).auditSink(audit).build();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        run(s, true).await();
        ((FakeLivenessSession) s).awaitTermination(5000); // final audit is written after onComplete is queued

        assertThat(audit.events).contains(AuditSink.Event.CONFIG_LOADED, AuditSink.Event.LIVENESS_STARTED,
                AuditSink.Event.PAD_ATTACK_SUSPECTED, AuditSink.Event.LIVENESS_ATTEMPT_FAILED,
                AuditSink.Event.LIVENESS_FAILED_FINAL);
        List<String> allowed = new ArrayList<String>();
        for (java.lang.reflect.Field f : io.mosip.registration.liveness.core.audit.AuditRecorder.Keys.class.getFields()) {
            allowed.add((String) f.get(null));
        }
        synchronized (audit.details) {
            for (Map<String, String> d : audit.details) {
                for (String k : d.keySet()) {
                    assertThat(allowed.contains(k) || k.startsWith("pad.")).as("audit key " + k).isTrue();
                }
                // An encoded face image would be tens of KB; the longest legitimate value is the policy summary.
                for (String v : d.values()) assertThat(v.length()).isLessThan(512);
            }
        }
    }

    @Test
    void pacingFollowsTheInjectedClock() throws Exception {
        ManualClock clock = new ManualClock();
        FakeLivenessService svc = FakeLivenessService.builder(new MapConfig()).scenario(FakeScenario.PASS_PASSIVE)
                .timeScale(1.0).clock(clock).build();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        RecordingListener l = run(s, false);
        // The script waits 1.5 s of CLOCK time: with the clock frozen, nothing completes.
        assertThat(l.done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
        clock.now += FakeLivenessSession.CHECKING_MS;
        assertThat(l.await().passed()).isTrue();
    }

    @Test
    void deviceFailureScenarioShowsNoRetry() throws Exception {
        RecordingListener l = run(service(FakeScenario.DEVICE_FAILURE).newSession(WorkflowType.OPERATOR), true);
        LivenessResult r = l.await();
        assertThat(r.failureCode.userMessage()).isEqualTo(ErrorCode.UserMessage.DEVICE_PROBLEM);
        assertThat(l.types()).containsSubsequence(Type.DEVICE_ERROR, Type.FAILED_FINAL);
        assertThat(l.ofType(Type.DEVICE_ERROR).get(0).recoverable).isFalse();
        assertThat(l.ofType(Type.ATTEMPT_FAILED)).isEmpty();
    }

    @Test
    void insideOnCompleteTheCooldownIsActiveAndTheSourceIsReleased() throws Exception {
        final FakeLivenessService svc = FakeLivenessService.builder(new MapConfig())
                .scenario(FakeScenario.MAX_RETRIES_EXCEEDED).timeScale(0).build();
        final TestFrameSource src = new TestFrameSource();
        final LivenessSession[] next = new LivenessSession[1];
        final boolean[] stopped = new boolean[1];
        LivenessSession s = svc.newSession(WorkflowType.OPERATOR);
        RecordingListener l = new RecordingListener() {
            @Override public void onComplete(LivenessResult r) {
                stopped[0] = !src.isRunning();
                next[0] = svc.newSession(WorkflowType.OPERATOR); // app retries immediately
                super.onComplete(r);
            }
        };
        l.session = s;
        s.start(src, l);
        assertThat(l.await().failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(stopped[0]).as("source stopped before onComplete").isTrue();
        assertThat(next[0]).as("cooldown already active inside onComplete")
                .isInstanceOf(io.mosip.registration.liveness.core.session.CooldownRefusedSession.class);
        assertThat(src.stops.get()).isEqualTo(1);
    }
}
