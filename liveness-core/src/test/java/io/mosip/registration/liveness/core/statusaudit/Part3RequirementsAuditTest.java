package io.mosip.registration.liveness.core.statusaudit;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.detect.ActionDetector;
import io.mosip.registration.liveness.core.detect.Baseline;
import io.mosip.registration.liveness.core.detect.DetectorStatus;
import io.mosip.registration.liveness.core.detect.Detectors;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;
import io.mosip.registration.liveness.core.scenario.ScriptedFaceAnalyzer.Shape;
import io.mosip.registration.liveness.core.session.CooldownRefusedSession;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;
import io.mosip.registration.liveness.core.session.ScenarioDriver;
import io.mosip.registration.liveness.core.testing.Fa;
import io.mosip.registration.liveness.core.util.TimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Requirement-by-requirement audit tests (PART3_STATUS.md). Each test is named after the requirement number it
 * proves. The gaps found by the audit (R9, R17 and the minor issues) are fixed and these tests now guard them.
 */
class Part3RequirementsAuditTest {

    private final ScheduledExecutorService camera = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        camera.shutdownNow();
    }

    // ------------------------------------------------------------------ helpers

    interface ShapeFn { Fa apply(Fa f); }

    static Baseline neutralBaseline() {
        List<FrameAnalysis> b = new ArrayList<FrameAnalysis>();
        for (int i = 0; i < 5; i++) b.add(Fa.at(i, i).build());
        return Baseline.of(b);
    }

    /** Feeds a detector a 15 fps sequence (prompt at t=0); returns the statuses. */
    static List<DetectorStatus> feed(ActionDetector d, int neutralFirst, int n, ShapeFn action, int neutralAfter) {
        d.reset(neutralBaseline(), 0);
        List<DetectorStatus> out = new ArrayList<DetectorStatus>();
        long t = 0, seq = 0;
        for (int i = 0; i < neutralFirst; i++) out.add(d.update(Fa.at(t += 67, ++seq).build(), t));
        for (int i = 0; i < n; i++) out.add(d.update(action.apply(Fa.at(t += 67, ++seq)).build(), t));
        for (int i = 0; i < neutralAfter; i++) out.add(d.update(Fa.at(t += 67, ++seq).build(), t));
        return out;
    }

    static final ShapeFn CLOSED = new ShapeFn() { public Fa apply(Fa f) { return f.eyesClosed(); } };
    static final ShapeFn SMILE = new ShapeFn() { public Fa apply(Fa f) { return f.smile(); } };

    static ShapeFn yaw(final float d) { return new ShapeFn() { public Fa apply(Fa f) { return f.yaw(d); } }; }

    static ShapeFn pitch(final float d) { return new ShapeFn() { public Fa apply(Fa f) { return f.pitch(d); } }; }

    /** Analyzer returning a constant score (optionally slow), counting calls. */
    static final class ConstantAnalyzer implements FaceAnalyzer {
        final float score;
        final long workMs;
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> threads = java.util.Collections.synchronizedSet(new HashSet<String>());
        volatile boolean closed;

        ConstantAnalyzer(float score, long workMs) { this.score = score; this.workMs = workMs; }

        @Override public FrameAnalysis analyze(Frame f) throws LivenessEngineException {
            calls.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            if (workMs > 0) {
                long end = System.nanoTime() + workMs * 1000000L;
                while (System.nanoTime() < end) Thread.yield(); // simulated inference cost
            }
            FrameAnalysis a = Fa.at(f.timestampMs(), f.sequence()).score(score).build();
            return new FrameAnalysis(f, 1, a.faceBox, a.landmarks, 0, 0, 0, a.qualityIssues, score,
                    java.util.Collections.singletonMap("minifas", score), 10);
        }
        @Override public String modelVersion() { return "audit-model-1"; }
        @Override public void close() { closed = true; }
    }

    static FaceAnalyzerFactory factory(final FaceAnalyzer a) {
        return new FaceAnalyzerFactory() { @Override public FaceAnalyzer create() { return a; } };
    }

    /** Real time plus a controllable offset: lets tests jump over the cooldown without sleeping. */
    static final class OffsetClock implements TimeSource {
        final AtomicLong offset = new AtomicLong();
        @Override public long nowMs() { return System.nanoTime() / 1000000L + offset.get(); }
    }

    /** Streams frames at a fixed real-time rate from a scheduler thread (the "camera"). */
    void stream(final FakeFrameSource src, long periodMs) {
        final AtomicLong seq = new AtomicLong();
        camera.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { src.push(System.nanoTime() / 1000000L, seq.incrementAndGet()); }
        }, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ B. detectors

    /** R3: each type detects a correct sequence (unit level; EngineScenarioTest.everyChallengeTypeCanBePassed end to end). */
    @Test
    void r3_everyDetectorDetectsItsAction() {
        EngineTuning t = EngineTuning.defaults();
        assertThat(feed(Detectors.create(ChallengeType.BLINK, t), 4, 1, CLOSED, 2)).contains(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.SMILE, t), 4, 6, SMILE, 0)).contains(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.TURN_LEFT, t), 4, 5, yaw(26), 2)).contains(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.TURN_RIGHT, t), 4, 5, yaw(-26), 2)).contains(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.LOOK_UP, t), 4, 5, pitch(18), 2)).contains(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.LOOK_DOWN, t), 4, 5, pitch(-18), 2)).contains(DetectorStatus.DETECTED);
    }

    /** R4: wrong actions never satisfy a challenge; opposite motions are WRONG_ACTION. */
    @Test
    void r4_wrongActionsAreRejected() {
        EngineTuning t = EngineTuning.defaults();
        assertThat(feed(Detectors.create(ChallengeType.BLINK, t), 4, 10, SMILE, 4)).doesNotContain(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.SMILE, t), 4, 2, CLOSED, 10)).doesNotContain(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.TURN_LEFT, t), 4, 5, yaw(-26), 2)).contains(DetectorStatus.WRONG_ACTION)
                .doesNotContain(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.TURN_RIGHT, t), 4, 5, yaw(26), 2)).contains(DetectorStatus.WRONG_ACTION);
        assertThat(feed(Detectors.create(ChallengeType.LOOK_UP, t), 4, 5, pitch(-18), 2)).contains(DetectorStatus.WRONG_ACTION);
        assertThat(feed(Detectors.create(ChallengeType.LOOK_DOWN, t), 4, 5, pitch(18), 2)).contains(DetectorStatus.WRONG_ACTION);
        // A head turn is not a blink or a smile, and a smile is not a turn.
        assertThat(feed(Detectors.create(ChallengeType.BLINK, t), 4, 6, yaw(26), 4)).doesNotContain(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.SMILE, t), 4, 6, yaw(26), 4)).doesNotContain(DetectorStatus.DETECTED);
        assertThat(feed(Detectors.create(ChallengeType.TURN_LEFT, t), 4, 10, SMILE, 4)).doesNotContain(DetectorStatus.DETECTED);
    }

    /** R5: progress starts at 0 and reaches 1 on detection; an action in progress at the prompt does not count. */
    @Test
    void r5_progressAndPrePromptAction() {
        EngineTuning t = EngineTuning.defaults();
        for (ChallengeType c : ChallengeType.values()) {
            ActionDetector d = Detectors.create(c, t);
            d.reset(neutralBaseline(), 0);
            assertThat(d.progress()).as(c + " initial").isEqualTo(0f);
        }
        ActionDetector blink = Detectors.create(ChallengeType.BLINK, t);
        feed(blink, 4, 1, CLOSED, 1);
        assertThat(blink.progress()).isEqualTo(1f);
        // Turned before (and at) the prompt, never back to neutral: never counts.
        assertThat(feed(Detectors.create(ChallengeType.TURN_LEFT, t), 0, 60, yaw(28), 0)).containsOnly(DetectorStatus.ARMING);
        // Smiling already at the prompt: never counts until neutral first.
        assertThat(feed(Detectors.create(ChallengeType.SMILE, t), 0, 40, SMILE, 0)).containsOnly(DetectorStatus.ARMING);
    }

    /** R5 (UI level): CHALLENGE_PROGRESS values for one challenge should never go backwards. */
    @Test
    void r5_progressEventsAreMonotonicPerChallenge() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.frames(45, d.score(0.6f)).untilPrompt();
        // Partial turn (shows progress), then back to neutral and wait: heartbeats follow.
        Shape partial = new Shape() { public Fa apply(Fa f) { return f.score(0.6f).yaw(15).smile().ear(0.25f); } };
        d.frames(12, d.score(0.6f)).frames(4, partial).frames(60, d.score(0.6f));
        float last = -1f;
        int prompts = 0;
        synchronized (d.events) {
            for (LivenessEvent e : d.events) {
                if (e.type == Type.CHALLENGE_PROMPT) { prompts++; last = -1f; }
                if (e.type == Type.CHALLENGE_PROGRESS && prompts > 0) {
                    assertThat(e.progress).as("progress went backwards: " + d.events).isGreaterThanOrEqualTo(last);
                    last = e.progress;
                }
            }
        }
    }

    /** R6: null landmarks and a lost face never crash any detector. */
    @Test
    void r6_nullLandmarksAndLostFaceAreSafe() {
        final EngineTuning t = EngineTuning.defaults();
        for (final ChallengeType c : ChallengeType.values()) {
            assertThatCode(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
                @Override public void call() {
                    ActionDetector d = Detectors.create(c, t);
                    d.reset(neutralBaseline(), 0);
                    for (int i = 1; i < 40; i++) {
                        d.update(Fa.at(i * 67, i).noLandmarks().build(), i * 67);
                        d.update(Fa.at(i * 67 + 1, 1000 + i).faces(0).build(), i * 67 + 1);
                    }
                    // Baseline built without landmarks (NaN ratios) must also be safe.
                    List<FrameAnalysis> none = new ArrayList<FrameAnalysis>();
                    for (int i = 0; i < 5; i++) none.add(Fa.at(i, i).noLandmarks().build());
                    d.reset(Baseline.of(none), 0);
                    for (int i = 1; i < 40; i++) d.update(Fa.at(i * 67, i).build(), i * 67);
                }
            }).as(c.name()).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ D. orchestrator

    /** R9: only FrameAnalysis.usable() frames count towards passiveWindowFrames (contract wording). */
    @Test
    void r9_onlyUsableFramesCountTowardsTheWindow() {
        PassiveEvaluator ev = new PassiveEvaluator(new LivenessPolicy(), EngineTuning.defaults());
        ev.reset(0);
        for (int i = 0; i < 10; i++) ev.offer(Fa.at(i * 125, i).score(0.95f).issue(QualityIssue.FACE_OFF_CENTER).build(), i * 125);
        assertThat(ev.windowSize()).isZero();
        ev.offer(Fa.at(5000, 50).score(0.95f).build(), 5000);
        assertThat(ev.windowSize()).isEqualTo(1);
    }

    /** R12: a GUIDANCE event for no face, multiple faces and each quality issue. */
    @Test
    void r12_guidanceForEveryProblem() {
        Map<QualityIssue, Guidance> expected = new java.util.EnumMap<QualityIssue, Guidance>(QualityIssue.class);
        expected.put(QualityIssue.TOO_DARK, Guidance.IMPROVE_LIGHTING);
        expected.put(QualityIssue.TOO_BRIGHT, Guidance.IMPROVE_LIGHTING);
        expected.put(QualityIssue.BLURRY, Guidance.HOLD_STEADY);
        expected.put(QualityIssue.FACE_TOO_SMALL, Guidance.MOVE_CLOSER);
        expected.put(QualityIssue.FACE_TOO_LARGE, Guidance.MOVE_BACK);
        expected.put(QualityIssue.FACE_OFF_CENTER, Guidance.CENTER_FACE);
        expected.put(QualityIssue.FACE_CUT_OFF, Guidance.CENTER_FACE);
        assertThat(expected.keySet()).containsExactlyInAnyOrder(QualityIssue.values());
        for (final Map.Entry<QualityIssue, Guidance> e : expected.entrySet()) {
            ScenarioDriver d = ScenarioDriver.resident();
            d.frames(20, new Shape() { public Fa apply(Fa f) { return f.score(0.6f).issue(e.getKey()); } });
            assertThat(d.lastEvent(Type.GUIDANCE)).as(e.getKey().name()).isNotNull();
            assertThat(d.lastEvent(Type.GUIDANCE).guidance).as(e.getKey().name()).isEqualTo(e.getValue());
        }
        ScenarioDriver none = ScenarioDriver.resident();
        none.frames(20, ScenarioDriver.NO_FACE);
        assertThat(none.lastEvent(Type.GUIDANCE).guidance).isEqualTo(Guidance.NO_FACE);
        ScenarioDriver two = ScenarioDriver.resident();
        two.frames(20, ScenarioDriver.TWO_FACES);
        assertThat(two.lastEvent(Type.GUIDANCE).guidance).isEqualTo(Guidance.MULTIPLE_FACES);
        ScenarioDriver turned = ScenarioDriver.resident();
        turned.frames(20, new Shape() { public Fa apply(Fa f) { return f.score(0.6f).yaw(40); } });
        assertThat(turned.lastEvent(Type.GUIDANCE).guidance).isEqualTo(Guidance.LOOK_AT_CAMERA);
    }

    /** R14: per-challenge timeout, ATTEMPT_FAILED carries retriesLeft counting down. */
    @Test
    void r14_timeoutAndRetriesLeft() {
        ScenarioDriver d = ScenarioDriver.resident();
        List<Integer> left = new ArrayList<Integer>();
        for (int attempt = 0; attempt < 4 && !d.done(); attempt++) {
            d.frames(45, d.score(0.6f)).untilPrompt().frames(200, d.score(0.6f));
        }
        synchronized (d.events) {
            for (LivenessEvent e : d.events) {
                if (e.type == Type.ATTEMPT_FAILED) {
                    assertThat(e.error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
                    left.add(e.retriesLeft);
                }
            }
        }
        assertThat(left).containsExactly(3, 2, 1);
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
    }

    /** R15: COOLDOWN refuses new sessions for cooldownSec on the REAL service, then allows them again. */
    @Test
    void r15_cooldownOnTheRealService() throws Exception {
        OffsetClock clock = new OffsetClock();
        MapConfigProvider cfg = new MapConfigProvider().put(LivenessPolicy.PREFIX + "operator.maxRetries", "0");
        DefaultLivenessService svc = DefaultLivenessService.builder().config(cfg).clock(clock)
                .analyzerFactory(factory(new ConstantAnalyzer(0.1f, 0))).build();
        FakeFrameSource src = new FakeFrameSource();
        RecordingLivenessListener l = new RecordingLivenessListener();
        svc.newSession(WorkflowType.OPERATOR).start(src, l);
        stream(src, 20);
        assertThat(l.await(10).failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);

        FakeFrameSource src2 = new FakeFrameSource();
        RecordingLivenessListener refused = new RecordingLivenessListener();
        svc.newSession(WorkflowType.OPERATOR).start(src2, refused);
        LivenessResult r = refused.await(5);
        assertThat(r.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(r.attempts).isZero();
        assertThat(src2.starts.get()).as("camera not opened while in cooldown").isZero();
        assertThat(svc.newSession(WorkflowType.SUPERVISOR)).isNotNull();

        clock.offset.addAndGet(60001);
        FakeFrameSource src3 = new FakeFrameSource();
        RecordingLivenessListener again = new RecordingLivenessListener();
        LivenessSession s3 = svc.newSession(WorkflowType.OPERATOR);
        s3.start(src3, again);
        assertThat(src3.starts.get()).as("allowed again after cooldownSec").isEqualTo(1);
        s3.cancel();
        again.await(5);
    }

    /** R17: FrameSource.onError -> DEVICE_ERROR carrying the source's recoverable flag, then FAILED_FINAL. */
    @Test
    void r17_nonRecoverableDeviceErrorEmitsDeviceErrorFalse() {
        ScenarioDriver d = ScenarioDriver.resident(); // Retry is offered until the per-session device error limit
        for (int i = 0; i < 10 && !d.done(); i++) d.frames(3, d.score(0.6f)).deviceError(ErrorCode.CAMERA_UNAVAILABLE, false);
        LivenessEvent e = d.lastEvent(Type.DEVICE_ERROR);
        assertThat(e).isNotNull();
        assertThat(e.recoverable).isFalse();
        assertThat(e.error).isEqualTo(ErrorCode.CAMERA_UNAVAILABLE);
        List<Type> t = d.types();
        assertThat(t.subList(t.size() - 2, t.size())).containsExactly(Type.DEVICE_ERROR, Type.FAILED_FINAL);
        assertThat(d.result.failureCode).isEqualTo(ErrorCode.CAMERA_UNAVAILABLE);
    }

    /** R17 (as built): recoverable flag passes through; non-recoverable ends with the device code. */
    @Test
    void r17_deviceErrorsAsBuilt() {
        ScenarioDriver rec = ScenarioDriver.resident();
        rec.autoRetry = false;
        rec.frames(3, rec.score(0.6f)).deviceError(ErrorCode.DEVICE_DISCONNECTED, true);
        assertThat(rec.lastEvent(Type.DEVICE_ERROR).recoverable).isTrue();
        ScenarioDriver non = ScenarioDriver.resident();
        non.autoRetry = false;
        non.frames(3, non.score(0.6f)).deviceError(ErrorCode.CAMERA_UNAVAILABLE, false);
        assertThat(non.lastEvent(Type.DEVICE_ERROR).recoverable).as("source gave up, user may still Retry").isTrue();
        assertThat(non.done()).isFalse();
    }

    /** R17: a single FaceAnalyzer exception is tolerated; 3 in a row -> ENGINE_ERROR (design D7). */
    @Test
    void r17_analyzerExceptionsBecomeEngineError() {
        ScenarioDriver one = ScenarioDriver.resident();
        one.autoRetry = false;
        one.frames(3, one.score(0.6f)).analyzerFails(1, ErrorCode.ENGINE_ERROR).frames(3, one.score(0.6f));
        assertThat(one.lastEvent(Type.ATTEMPT_FAILED)).as("one bad frame is skipped").isNull();
        ScenarioDriver three = ScenarioDriver.resident();
        three.autoRetry = false;
        three.frames(3, three.score(0.6f)).analyzerFails(3, ErrorCode.ENGINE_ERROR);
        assertThat(three.lastEvent(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.ENGINE_ERROR);
    }

    /** R19: one worker thread per session, newest frame kept, analyzeFps respected, no deadlock under flood. */
    @Test
    void r19_threadingUnderAFastCamera() throws Exception {
        // 30 ms inference, high score: the session passes as soon as 10 frames were analysed at analyzeFps (8).
        ConstantAnalyzer analyzer = new ConstantAnalyzer(0.95f, 30);
        DefaultLivenessService svc = DefaultLivenessService.builder().config(new MapConfigProvider())
                .analyzerFactory(factory(analyzer)).build();
        FakeFrameSource src = new FakeFrameSource();
        RecordingLivenessListener l = new RecordingLivenessListener();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        long t0 = System.nanoTime();
        s.start(src, l);
        stream(src, 5); // 200 fps camera, far faster than analysis
        LivenessResult r = l.await(10);
        long elapsedMs = (System.nanoTime() - t0) / 1000000L;
        assertThat(r.passed()).as("no deadlock under a frame flood").isTrue();

        assertThat(analyzer.threads).as("analysis on exactly one worker thread").hasSize(1);
        assertThat(analyzer.threads.iterator().next()).startsWith("liveness-").doesNotStartWith("liveness-events");
        assertThat(l.lastThread).startsWith("liveness-events-");
        assertThat(analyzer.calls.get()).as("window of 10, analysed once each").isBetween(10, 11);
        // 10 analyses at 8 fps need >= ~9 intervals of 125 ms: analyzeFps is respected.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(9 * 125 - 30);
        assertThat(src.pushed.get()).as("most camera frames were dropped, not queued").isGreaterThan(analyzer.calls.get() * 5);
    }

    /** R20: PASSED result fields on the real service (passive path). */
    @Test
    void r20_passedResultFields() throws Exception {
        DefaultLivenessService svc = DefaultLivenessService.builder().config(new MapConfigProvider())
                .analyzerFactory(factory(new ConstantAnalyzer(0.93f, 0))).build();
        FakeFrameSource src = new FakeFrameSource();
        RecordingLivenessListener l = new RecordingLivenessListener();
        LivenessSession s = svc.newSession(WorkflowType.SUPERVISOR);
        s.start(src, l);
        stream(src, 33);
        LivenessResult r = l.await(10);
        assertThat(r.decision).isEqualTo(LivenessResult.Decision.PASSED);
        assertThat(r.passPath).isEqualTo(LivenessResult.PassPath.PASSIVE_ONLY);
        assertThat(r.bestFrame).isNotNull();
        assertThat(r.passiveScore).isEqualTo(0.93f);
        assertThat(r.challengesIssued).isEmpty();
        assertThat(r.attempts).isEqualTo(1);
        assertThat(r.sessionId).isEqualTo(s.sessionId());
        assertThat(r.modelVersion).isEqualTo("audit-model-1");
        assertThat(r.failureCode).isNull();
        assertThat(r.durationMs).isPositive();
    }

    // ------------------------------------------------------------------ E. service + config

    /** R23: reloadConfig does not change a session that was already created/running (real service). */
    @Test
    void r23_reloadDoesNotAffectRunningSession() throws Exception {
        MapConfigProvider cfg = new MapConfigProvider();
        DefaultLivenessService svc = DefaultLivenessService.builder().config(cfg)
                .analyzerFactory(factory(new ConstantAnalyzer(0.6f, 0))).build();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT); // resident default: 2 challenges
        svc.reloadConfig(new MapConfigProvider().put(LivenessPolicy.PREFIX + "resident.minChallenges", "3"));
        assertThat(svc.policy(WorkflowType.RESIDENT).minChallenges).isEqualTo(3);
        FakeFrameSource src = new FakeFrameSource();
        RecordingLivenessListener l = new RecordingLivenessListener();
        s.start(src, l);
        stream(src, 20);
        assertThat(l.firstPrompt.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(l.last(Type.CHALLENGE_PROMPT).challengeCount).isEqualTo(2);
        s.cancel();
        l.await(5);
    }

    // ------------------------------------------------------------------ F. audit and privacy

    /** R24/R25/R26: audit through the real service: events, sessionId, modelVersion, scores; nothing image-like. */
    @Test
    void r24_r25_r26_auditOnTheRealService() throws Exception {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        MapConfigProvider cfg = new MapConfigProvider().put(LivenessPolicy.PREFIX + "resident.maxRetries", "0");
        DefaultLivenessService svc = DefaultLivenessService.builder().config(cfg).auditSink(audit)
                .analyzerFactory(factory(new ConstantAnalyzer(0.1f, 0))).build();
        FakeFrameSource src = new FakeFrameSource();
        RecordingLivenessListener l = new RecordingLivenessListener();
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        s.start(src, l);
        stream(src, 20);
        LivenessResult r = l.await(10);
        assertThat(r.failureCode).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        for (int i = 0; i < 500 && audit.of(AuditSink.Event.LIVENESS_FAILED_FINAL).isEmpty(); i++) Thread.yield();

        List<AuditSink.Event> events = new ArrayList<AuditSink.Event>();
        for (InMemoryAuditSink.Record rec : audit.records) events.add(rec.event);
        assertThat(events).contains(AuditSink.Event.CONFIG_LOADED, AuditSink.Event.MODEL_LOADED,
                AuditSink.Event.LIVENESS_STARTED, AuditSink.Event.PAD_ATTACK_SUSPECTED,
                AuditSink.Event.LIVENESS_FAILED_FINAL);
        InMemoryAuditSink.Record pad = audit.of(AuditSink.Event.PAD_ATTACK_SUSPECTED).get(0);
        assertThat(pad.sessionId).isEqualTo(s.sessionId());
        assertThat(pad.details).containsEntry(AuditRecorder.Keys.MODEL_VERSION, "audit-model-1")
                .containsEntry(AuditRecorder.Keys.ERROR_CODE, "PAD_FAILURE")
                .containsKey(AuditRecorder.Keys.PASSIVE_SCORE).containsKey("pad.minifas");
        assertThat(audit.of(AuditSink.Event.LIVENESS_FAILED_FINAL).get(0).details)
                .containsEntry(AuditRecorder.Keys.LAST_CAUSE, "PAD_FAILURE");

        // R25: only known keys, short values, no numeric arrays that could be coordinates or pixels.
        Set<String> allowed = new HashSet<String>();
        for (Field f : AuditRecorder.Keys.class.getFields()) allowed.add((String) f.get(null));
        synchronized (audit.records) {
            for (InMemoryAuditSink.Record rec : audit.records) {
                for (Map.Entry<String, String> e : rec.details.entrySet()) {
                    assertThat(allowed.contains(e.getKey()) || e.getKey().startsWith("pad.")).as(e.getKey()).isTrue();
                    assertThat(e.getValue().length()).as(e.getKey()).isLessThan(400);
                    assertThat(e.getValue()).doesNotContain("[[").doesNotMatch(".*\\[\\s*-?\\d+(\\.\\d+)?\\s*,.*");
                }
            }
        }
        // R26: the UI-facing event carries only the code; its user message is the generic one.
        LivenessEvent fin = l.last(Type.FAILED_FINAL);
        assertThat(fin.error.userMessage()).isEqualTo(ErrorCode.UserMessage.MAX_ATTEMPTS_REACHED);
        assertThat(ErrorCode.PAD_FAILURE.userMessage()).isEqualTo(ErrorCode.UserMessage.VERIFICATION_NOT_COMPLETED);
    }

    // ------------------------------------------------------------------ probes for minor issues (as built)

    /** Device problems are not liveness attempts: an automatic resume keeps the attempt number. */
    @Test
    void autoResumeAfterDeviceBlipKeepsTheAttemptNumber() {
        ScenarioDriver d = ScenarioDriver.resident();
        d.frames(20, d.score(0.9f)).deviceError(ErrorCode.DEVICE_DISCONNECTED, true).noFramesFor(1000);
        d.untilDone(d.score(0.9f));
        assertThat(d.result.passed()).isTrue();
        assertThat(d.result.attempts).as("user pressed Retry zero times").isEqualTo(1);
        assertThat(d.lastEvent(Type.STARTED).attemptNumber).isEqualTo(1);
    }

    /** A session refused by the cooldown reports a non-null placeholder model version. */
    @Test
    void cooldownRefusalReportsModelUnavailable() throws Exception {
        MapConfigProvider cfg = new MapConfigProvider().put(LivenessPolicy.PREFIX + "operator.maxRetries", "0");
        DefaultLivenessService svc = DefaultLivenessService.builder().config(cfg)
                .analyzerFactory(factory(new ConstantAnalyzer(0.1f, 0))).build();
        FakeFrameSource src = new FakeFrameSource();
        RecordingLivenessListener l = new RecordingLivenessListener();
        svc.newSession(WorkflowType.OPERATOR).start(src, l);
        stream(src, 20);
        l.await(10);
        RecordingLivenessListener refused = new RecordingLivenessListener();
        svc.newSession(WorkflowType.OPERATOR).start(new FakeFrameSource(), refused);
        assertThat(refused.await(5).modelVersion).isEqualTo(CooldownRefusedSession.MODEL_UNAVAILABLE);
    }
}
