package io.mosip.registration.liveness.fake;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.challenge.ChallengeSelector;
import io.mosip.registration.liveness.core.policy.CooldownTracker;
import io.mosip.registration.liveness.core.session.AttemptTracker;
import io.mosip.registration.liveness.core.session.EventDispatcher;
import io.mosip.registration.liveness.core.util.RandomSource;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scripted session. Same lifecycle, threading and retry/cancel semantics as the real engine; only the decisions
 * are scripted. If a real {@link FrameSource} is given it is started and stopped like the real engine would, its
 * device errors are reported, and its latest frame becomes {@link LivenessResult#bestFrame}.
 */
final class FakeLivenessSession implements LivenessSession {

    // UI pacing (not detection thresholds): roughly what the real engine will feel like.
    static final long CHECKING_MS = 1500;
    static final long PROMPT_TO_FIRST_PROGRESS_MS = 900;
    static final long PROGRESS_STEP_MS = 700;
    static final long ACTION_DETECTED_PAUSE_MS = 600;
    static final long HOLD_STILL_MS = 800;
    static final long GUIDANCE_REPEAT_MS = 1000;
    static final int MAX_PROMPT_JITTER_MS = 400;
    static final int SYNTHETIC_FRAME_W = 320, SYNTHETIC_FRAME_H = 240;

    private static final List<ErrorCode> ROTATING_FAILURES = Arrays.asList(
            ErrorCode.CHALLENGE_FAILED, ErrorCode.CHALLENGE_TIMEOUT, ErrorCode.POOR_QUALITY);

    private enum State { NEW, RUNNING, WAITING_RETRY, DONE }

    private final String sessionId = UUID.randomUUID().toString();
    private final WorkflowType workflow;
    private final LivenessPolicy policy;
    private final FakeScenario scenario;
    private final double timeScale;
    private final RandomSource random;
    private final TimeSource clock;
    private final AuditSink audit;
    private final CooldownTracker cooldown;
    private final ChallengeSelector selector;
    private final AtomicReference<Frame> latestFrame = new AtomicReference<Frame>();

    private final Object lock = new Object();
    // Guarded by lock.
    private State state = State.NEW;
    private boolean cancelRequested;
    private boolean retryRequested;
    private ErrorCode pendingDeviceError;
    private boolean pendingDeviceRecoverable;
    private EventDispatcher dispatcher;
    private FrameSource source;
    private Thread worker;

    private volatile long startMs;
    private volatile List<ChallengeType> lastIssued = Collections.emptyList();

    FakeLivenessSession(WorkflowType workflow, LivenessPolicy policy, FakeScenario scenario, double timeScale,
                        RandomSource random, TimeSource clock, AuditSink audit, CooldownTracker cooldown) {
        this.workflow = workflow; this.policy = policy; this.scenario = scenario; this.timeScale = timeScale;
        this.random = random; this.clock = clock; this.audit = audit; this.cooldown = cooldown;
        this.selector = new ChallengeSelector(random);
    }

    FakeScenario scenario() {
        return scenario;
    }

    // ---------------------------------------------------------------- LivenessSession

    @Override
    public void start(FrameSource source, LivenessListener listener) {
        synchronized (lock) {
            if (state != State.NEW) throw new IllegalStateException("Session already started");
            dispatcher = new EventDispatcher(listener, "liveness-events-" + shortId());
            this.source = source;
            startMs = clock.nowMs();
            if (cancelRequested) {
                // cancel() before start(): deliver the CANCELLED result now, never open the camera.
                state = State.DONE;
                dispatcher.complete(result(LivenessResult.Decision.CANCELLED, LivenessResult.PassPath.NONE,
                        ErrorCode.CANCELLED, null, Float.NaN, 0));
                return;
            }
            state = State.RUNNING;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runSession();
                }
            }, "liveness-fake-" + shortId());
            worker.setDaemon(true);
        }
        startSource();
        worker.start();
    }

    @Override
    public void retry() {
        synchronized (lock) {
            if (state == State.WAITING_RETRY) {
                retryRequested = true;
                lock.notifyAll();
            }
        }
    }

    @Override
    public void cancel() {
        boolean won;
        int attempts;
        synchronized (lock) {
            if (cancelRequested || state == State.DONE) return;
            cancelRequested = true;
            lock.notifyAll();
            if (state == State.NEW) return; // start() will deliver the result
            state = State.DONE;
            attempts = currentAttempt;
            won = dispatcher.complete(result(LivenessResult.Decision.CANCELLED, LivenessResult.PassPath.NONE,
                    ErrorCode.CANCELLED, null, Float.NaN, attempts));
        }
        stopSource();
        if (won) {
            Map<String, String> d = details(attempts);
            d.put("errorCode", ErrorCode.CANCELLED.name());
            d.put("cancelled", "true");
            audit(AuditSink.Event.LIVENESS_FAILED_FINAL, d);
        }
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    /** Test helper: waits for the worker to exit and all callbacks to be delivered. */
    boolean awaitTermination(long timeoutMs) throws InterruptedException {
        Thread w;
        EventDispatcher d;
        synchronized (lock) { w = worker; d = dispatcher; }
        if (w != null) w.join(timeoutMs);
        return (w == null || !w.isAlive()) && (d == null || d.awaitDelivered(timeoutMs));
    }

    // ---------------------------------------------------------------- worker

    private volatile int currentAttempt = 1;

    private void runSession() {
        AttemptTracker tracker = new AttemptTracker(policy.maxRetries);
        List<ChallengeType> previous = null;
        try {
            while (true) {
                int attempt = tracker.attemptNumber();
                currentAttempt = attempt;
                emit(LivenessEvent.of(LivenessEvent.Type.STARTED, attempt));
                audit(AuditSink.Event.LIVENESS_STARTED, details(attempt));

                Outcome o;
                try {
                    o = runAttempt(attempt, previous);
                } catch (DeviceInterrupt d) {
                    o = Outcome.device(d.code, d.recoverable);
                }
                if (o.issued != null) {
                    previous = o.issued;
                    lastIssued = o.issued;
                }

                if (o.passed) {
                    finishPassed(o, attempt);
                    return;
                }
                if (o.device) {
                    Map<String, String> d = details(attempt);
                    d.put("errorCode", o.code.name());
                    d.put("recoverable", Boolean.toString(o.recoverable));
                    audit(AuditSink.Event.DEVICE_ERROR, d);
                    if (!o.recoverable) {
                        finishFailed(o.code, o.code, attempt, o.score);
                        return;
                    }
                    tracker.onAttemptFailed(o.code); // never consumes a retry
                    enterWaitingForRetry();
                    emit(LivenessEvent.failure(LivenessEvent.Type.DEVICE_ERROR, o.code, attempt,
                            tracker.retriesLeft(), true));
                } else {
                    AttemptTracker.Decision decision = tracker.onAttemptFailed(o.code);
                    if (decision.isFinal) {
                        finishFailed(decision.code, o.code, attempt, o.score);
                        return;
                    }
                    Map<String, String> d = details(attempt);
                    d.put("errorCode", o.code.name());
                    d.put("retriesLeft", Integer.toString(decision.retriesLeft));
                    if (o.code == ErrorCode.PAD_FAILURE) audit(AuditSink.Event.PAD_ATTACK_SUSPECTED, d);
                    audit(AuditSink.Event.LIVENESS_ATTEMPT_FAILED, d);
                    enterWaitingForRetry();
                    emit(LivenessEvent.failure(LivenessEvent.Type.ATTEMPT_FAILED, decision.code, attempt,
                            decision.retriesLeft, true));
                }
                awaitRetry();
                if (o.device) restartSource();
                tracker.beginNextAttempt();
            }
        } catch (Cancelled c) {
            // cancel() already delivered the CANCELLED result and stopped the source.
        }
    }

    private Outcome runAttempt(int attempt, List<ChallengeType> previous) throws Cancelled, DeviceInterrupt {
        switch (scenario) {
            case PASS_PASSIVE:
                checking(attempt);
                return Outcome.pass(LivenessResult.PassPath.PASSIVE_ONLY, 0.93f, null);
            case PASS_AFTER_1_CHALLENGE:
                checking(attempt);
                return passActive(attempt, 1, previous);
            case PASS_AFTER_2_CHALLENGES:
                checking(attempt);
                return passActive(attempt, Math.min(2, distinctPoolSize()), previous);
            case FAIL_CHALLENGE_THEN_PASS_ON_RETRY:
                checking(attempt);
                if (attempt == 1) return failDuringChallenge(attempt, previous, ErrorCode.CHALLENGE_FAILED, false);
                return passActive(attempt, policy.minChallenges, previous);
            case PAD_ATTACK:
                checking(attempt);
                return Outcome.fail(ErrorCode.PAD_FAILURE, 0.12f, null);
            case CHALLENGE_TIMEOUT:
                checking(attempt);
                if (attempt == 1) return failDuringChallenge(attempt, previous, ErrorCode.CHALLENGE_TIMEOUT, true);
                return passActive(attempt, policy.minChallenges, previous);
            case NO_FACE_THEN_PASS:
                emit(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, attempt));
                pause(300);
                emit(LivenessEvent.guidance(LivenessEvent.Guidance.NO_FACE, attempt));
                pause(GUIDANCE_REPEAT_MS);
                emit(LivenessEvent.guidance(LivenessEvent.Guidance.NO_FACE, attempt));
                pause(GUIDANCE_REPEAT_MS);
                checking(attempt); // re-emitting CHECKING_PASSIVE clears the hint (proposal P4)
                return Outcome.pass(LivenessResult.PassPath.PASSIVE_ONLY, 0.91f, null);
            case MULTIPLE_FACES:
                if (attempt > 1) {
                    checking(attempt);
                    return passActive(attempt, policy.minChallenges, previous);
                }
                emit(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, attempt));
                pause(500);
                emit(LivenessEvent.guidance(LivenessEvent.Guidance.MULTIPLE_FACES, attempt));
                pause(700);
                emit(LivenessEvent.guidance(LivenessEvent.Guidance.MULTIPLE_FACES, attempt));
                pause(800);
                return Outcome.fail(ErrorCode.MULTIPLE_FACES, Float.NaN, null);
            case POOR_LIGHTING_HINTS:
                emit(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, attempt));
                pause(400);
                emit(LivenessEvent.guidance(LivenessEvent.Guidance.IMPROVE_LIGHTING, attempt));
                pause(1200);
                emit(LivenessEvent.guidance(LivenessEvent.Guidance.MOVE_CLOSER, attempt));
                pause(1200);
                checking(attempt);
                return passActive(attempt, policy.minChallenges, previous);
            case DEVICE_DISCONNECT:
                checking(attempt);
                if (attempt == 1) {
                    List<ChallengeType> seq = select(policy.minChallenges, previous);
                    lastIssued = seq;
                    prompt(seq.get(0), 1, seq.size(), attempt);
                    progress(seq.get(0), 1, seq.size(), 0.35f, attempt);
                    pause(PROGRESS_STEP_MS);
                    throw new DeviceInterrupt(ErrorCode.DEVICE_DISCONNECTED, true);
                }
                return passActive(attempt, policy.minChallenges, previous);
            case MAX_RETRIES_EXCEEDED: {
                checking(attempt);
                ErrorCode cause = ROTATING_FAILURES.get((attempt - 1) % ROTATING_FAILURES.size());
                if (cause == ErrorCode.POOR_QUALITY) return Outcome.fail(cause, Float.NaN, null);
                return failDuringChallenge(attempt, previous, cause, cause == ErrorCode.CHALLENGE_TIMEOUT);
            }
            case ENGINE_ERROR:
                emit(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, attempt));
                pause(800);
                return Outcome.fail(ErrorCode.ENGINE_ERROR, Float.NaN, null);
            case PASS_ACTIVE:
            default:
                checking(attempt);
                return passActive(attempt, policy.minChallenges, previous);
        }
    }

    // ---------------------------------------------------------------- script building blocks

    private void checking(int attempt) throws Cancelled, DeviceInterrupt {
        emit(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, attempt));
        pause(CHECKING_MS);
    }

    private Outcome passActive(int attempt, int count, List<ChallengeType> previous)
            throws Cancelled, DeviceInterrupt {
        if (!policy.activeEnabled) {
            // Same as the real engine: inconclusive passive + active disabled = below threshold.
            return Outcome.fail(ErrorCode.LIVENESS_BELOW_THRESHOLD, 0.62f, null);
        }
        List<ChallengeType> seq = select(count, previous);
        lastIssued = seq;
        for (int i = 0; i < seq.size(); i++) {
            ChallengeType c = seq.get(i);
            prompt(c, i + 1, seq.size(), attempt);
            progress(c, i + 1, seq.size(), 0.35f, attempt);
            pause(PROGRESS_STEP_MS);
            progress(c, i + 1, seq.size(), 0.7f, attempt);
            pause(PROGRESS_STEP_MS);
            emit(LivenessEvent.of(LivenessEvent.Type.ACTION_DETECTED, attempt));
            pause(ACTION_DETECTED_PAUSE_MS);
        }
        emit(LivenessEvent.of(LivenessEvent.Type.HOLD_STILL, attempt));
        pause(HOLD_STILL_MS);
        return Outcome.pass(LivenessResult.PassPath.ACTIVE_CHALLENGE, 0.62f, seq);
    }

    private Outcome failDuringChallenge(int attempt, List<ChallengeType> previous, ErrorCode code, boolean timeout)
            throws Cancelled, DeviceInterrupt {
        if (!policy.activeEnabled) return Outcome.fail(ErrorCode.LIVENESS_BELOW_THRESHOLD, 0.62f, null);
        List<ChallengeType> seq = select(policy.minChallenges, previous);
        lastIssued = seq;
        ChallengeType c = seq.get(0);
        prompt(c, 1, seq.size(), attempt);
        if (timeout) {
            progress(c, 1, seq.size(), 0.2f, attempt);
            pause(Math.max(0, policy.challengeTimeoutMs - PROMPT_TO_FIRST_PROGRESS_MS));
        } else {
            progress(c, 1, seq.size(), 0.35f, attempt);
            pause(PROGRESS_STEP_MS);
        }
        return Outcome.fail(code, 0.62f, seq);
    }

    /** Emits the prompt after the random 0-400 ms jitter, then waits until the first progress update. */
    private void prompt(ChallengeType c, int index, int count, int attempt) throws Cancelled, DeviceInterrupt {
        pause(random.nextInt(MAX_PROMPT_JITTER_MS + 1));
        emit(LivenessEvent.prompt(c, index, count, policy.challengeTimeoutMs, attempt));
        pause(PROMPT_TO_FIRST_PROGRESS_MS);
    }

    private void progress(ChallengeType c, int index, int count, float p, int attempt) {
        emit(LivenessEvent.progress(c, index, count, p, attempt));
    }

    private List<ChallengeType> select(int count, List<ChallengeType> previous) {
        return selector.select(policy.challengePool, EnumSet.allOf(ChallengeType.class), count, previous);
    }

    private int distinctPoolSize() {
        return EnumSet.copyOf(policy.challengePool).size();
    }

    // ---------------------------------------------------------------- finishing

    private void finishPassed(Outcome o, int attempt) throws Cancelled {
        boolean won;
        synchronized (lock) {
            if (cancelRequested) throw new Cancelled();
            state = State.DONE;
            emit(LivenessEvent.of(LivenessEvent.Type.PASSED, attempt));
            won = dispatcher.complete(result(LivenessResult.Decision.PASSED, o.path, null, bestFrame(), o.score, attempt));
        }
        stopSource();
        if (won) {
            Map<String, String> d = details(attempt);
            d.put("passPath", o.path.name());
            d.put("passiveScore", Float.toString(o.score));
            audit(AuditSink.Event.LIVENESS_PASSED, d);
        }
    }

    private void finishFailed(ErrorCode finalCode, ErrorCode lastCause, int attempt, float score) throws Cancelled {
        boolean won;
        synchronized (lock) {
            if (cancelRequested) throw new Cancelled();
            state = State.DONE;
            emit(LivenessEvent.failure(LivenessEvent.Type.FAILED_FINAL, finalCode, attempt, 0, false));
            won = dispatcher.complete(result(LivenessResult.Decision.FAILED, LivenessResult.PassPath.NONE,
                    finalCode, null, score, attempt));
        }
        stopSource();
        if (!won) return;
        boolean cooldownTripped = finalCode == ErrorCode.MAX_RETRIES_EXCEEDED
                && policy.onRepeatedFailure == LivenessPolicy.OnRepeatedFailure.COOLDOWN;
        if (cooldownTripped) cooldown.trip(workflow, policy.cooldownSec);
        Map<String, String> d = details(attempt);
        d.put("errorCode", finalCode.name());
        d.put("lastCause", lastCause.name());
        if (cooldownTripped) d.put("cooldownSec", Integer.toString(policy.cooldownSec));
        if (lastCause == ErrorCode.PAD_FAILURE) audit(AuditSink.Event.PAD_ATTACK_SUSPECTED, d);
        audit(AuditSink.Event.LIVENESS_FAILED_FINAL, d);
    }

    private LivenessResult result(LivenessResult.Decision decision, LivenessResult.PassPath path, ErrorCode code,
                                  Frame best, float score, int attempts) {
        return new LivenessResult(decision, path, code, best, score, lastIssued, attempts,
                clock.nowMs() - startMs, sessionId, FakeLivenessService.MODEL_VERSION);
    }

    /** The real source's latest frame if there is one, otherwise a synthetic mid-grey frame. */
    private Frame bestFrame() {
        Frame f = latestFrame.get();
        if (f != null) return f;
        byte[] rgb = new byte[SYNTHETIC_FRAME_W * SYNTHETIC_FRAME_H * 3];
        Arrays.fill(rgb, (byte) 0x80);
        return new Frame(rgb, SYNTHETIC_FRAME_W, SYNTHETIC_FRAME_H, clock.nowMs(), 0, "fake:synthetic");
    }

    // ---------------------------------------------------------------- waiting, cancel, device errors

    /** Waits {@code ms} (scaled by timeScale); wakes early on cancel or a device error from the real source. */
    private void pause(long ms) throws Cancelled, DeviceInterrupt {
        long scaledNs = (long) (ms * timeScale * 1000000L);
        long end = System.nanoTime() + scaledNs;
        synchronized (lock) {
            while (true) {
                checkInterrupts();
                long leftMs = (end - System.nanoTime()) / 1000000L;
                if (leftMs <= 0) return;
                try {
                    lock.wait(leftMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Cancelled();
                }
            }
        }
    }

    /**
     * Must run BEFORE ATTEMPT_FAILED / DEVICE_ERROR is emitted: a listener may call retry() from inside that
     * callback, and a Retry press that arrives before the worker is waiting must not be lost.
     */
    private void enterWaitingForRetry() {
        synchronized (lock) {
            state = State.WAITING_RETRY;
            retryRequested = false;
        }
    }

    private void awaitRetry() throws Cancelled {
        synchronized (lock) {
            while (!retryRequested && !cancelRequested) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Cancelled();
                }
            }
            if (cancelRequested) throw new Cancelled();
            state = State.RUNNING;
        }
    }

    // Caller holds lock.
    private void checkInterrupts() throws Cancelled, DeviceInterrupt {
        if (cancelRequested) throw new Cancelled();
        if (pendingDeviceError != null) {
            ErrorCode code = pendingDeviceError;
            pendingDeviceError = null;
            throw new DeviceInterrupt(code, pendingDeviceRecoverable);
        }
    }

    private void startSource() {
        FrameSource s;
        synchronized (lock) { s = source; }
        if (s == null) return;
        try {
            s.start(new FrameSource.Listener() {
                @Override
                public void onFrame(Frame frame) {
                    latestFrame.set(frame);
                }

                @Override
                public void onError(ErrorCode code, String detailForAuditOnly, boolean recoverable) {
                    synchronized (lock) {
                        if (state == State.DONE || cancelRequested) return;
                        pendingDeviceError = code != null ? code : ErrorCode.DEVICE_UNAVAILABLE;
                        pendingDeviceRecoverable = recoverable;
                        lock.notifyAll();
                    }
                }
            });
        } catch (RuntimeException e) {
            // Contract says start() never throws; treat a violation as an unavailable device.
            synchronized (lock) {
                pendingDeviceError = ErrorCode.DEVICE_UNAVAILABLE;
                pendingDeviceRecoverable = true;
                lock.notifyAll();
            }
        }
    }

    private void stopSource() {
        FrameSource s;
        synchronized (lock) { s = source; }
        if (s == null) return;
        try {
            s.stop();
        } catch (RuntimeException ignored) {
            // stop() is best effort.
        }
    }

    private void restartSource() {
        stopSource();
        synchronized (lock) { pendingDeviceError = null; }
        startSource();
    }

    // ---------------------------------------------------------------- helpers

    private void emit(LivenessEvent e) {
        dispatcher.emit(e);
    }

    private Map<String, String> details(int attempt) {
        Map<String, String> d = new HashMap<String, String>();
        d.put("attempt", Integer.toString(attempt));
        d.put("modelVersion", FakeLivenessService.MODEL_VERSION);
        d.put("fakeScenario", scenario.name());
        if (!lastIssued.isEmpty()) d.put("challenges", lastIssued.toString());
        d.put("durationMs", Long.toString(clock.nowMs() - startMs));
        return d;
    }

    private void audit(AuditSink.Event event, Map<String, String> details) {
        if (audit == null) return;
        try {
            audit.record(event, workflow, sessionId, details);
        } catch (RuntimeException ignored) {
            // Audit failures must not change the liveness outcome.
        }
    }

    private String shortId() {
        return sessionId.substring(0, 8);
    }

    private static final class Outcome {
        final boolean passed;
        final boolean device;
        final boolean recoverable;
        final LivenessResult.PassPath path;
        final ErrorCode code;
        final float score;
        final List<ChallengeType> issued;

        private Outcome(boolean passed, boolean device, boolean recoverable, LivenessResult.PassPath path,
                        ErrorCode code, float score, List<ChallengeType> issued) {
            this.passed = passed; this.device = device; this.recoverable = recoverable; this.path = path;
            this.code = code; this.score = score; this.issued = issued;
        }

        static Outcome pass(LivenessResult.PassPath path, float score, List<ChallengeType> issued) {
            return new Outcome(true, false, false, path, null, score, issued);
        }

        static Outcome fail(ErrorCode code, float score, List<ChallengeType> issued) {
            return new Outcome(false, false, false, LivenessResult.PassPath.NONE, code, score, issued);
        }

        static Outcome device(ErrorCode code, boolean recoverable) {
            return new Outcome(false, true, recoverable, LivenessResult.PassPath.NONE, code, Float.NaN, null);
        }
    }

    private static final class Cancelled extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class DeviceInterrupt extends Exception {
        private static final long serialVersionUID = 1L;
        final ErrorCode code;
        final boolean recoverable;

        DeviceInterrupt(ErrorCode code, boolean recoverable) {
            this.code = code; this.recoverable = recoverable;
        }
    }
}
