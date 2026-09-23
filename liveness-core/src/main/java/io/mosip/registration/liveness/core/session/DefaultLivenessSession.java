package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.challenge.ChallengeRunner;
import io.mosip.registration.liveness.core.challenge.ChallengeSelector;
import io.mosip.registration.liveness.core.policy.CooldownTracker;
import io.mosip.registration.liveness.core.util.RandomSource;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real session: wires {@link FrameSource} → {@link FramePump} → {@link FaceAnalyzer} →
 * {@link SessionStateMachine} → {@link EventDispatcher}.
 *
 * <p>Threads:
 * <ul>
 *   <li><b>Source thread</b> (Part 1's): {@code onFrame} only swaps the pump's latest-frame slot.</li>
 *   <li><b>Worker</b> {@code liveness-<id>} (one per session): creates, uses and closes the analyzer, feeds the
 *       state machine, ticks every ≤50 ms so timeouts fire without frames, and executes the effects. All
 *       inputs from other threads (retry, cancel, device errors, frame wake-ups) arrive through one queue.</li>
 *   <li><b>Dispatcher</b> {@code liveness-events-<id>}: every listener callback, in order, exactly one
 *       onComplete, nothing after it.</li>
 * </ul>
 * {@link #cancel()} delivers the CANCELLED result immediately from the calling thread (even while the worker is
 * inside {@code analyze}) and stops the source; the worker then cleans up.
 */
public final class DefaultLivenessSession implements LivenessSession {

    /** Longest the worker waits for input before ticking. */
    static final long MAX_WAIT_MS = 50;

    private enum Phase { NEW, RUNNING, DONE }

    private enum InputKind { WAKE, RETRY, CANCEL, SOURCE_ERROR }

    private static final class Input {
        static final Input WAKE = new Input(InputKind.WAKE, null, null, false);
        static final Input RETRY = new Input(InputKind.RETRY, null, null, false);
        static final Input CANCEL = new Input(InputKind.CANCEL, null, null, false);
        final InputKind kind;
        final ErrorCode code;
        final String detail;
        final boolean recoverable;

        Input(InputKind kind, ErrorCode code, String detail, boolean recoverable) {
            this.kind = kind; this.code = code; this.detail = detail; this.recoverable = recoverable;
        }
    }

    private final String sessionId = UUID.randomUUID().toString();
    private final WorkflowType workflow;
    private final LivenessPolicy policy;
    private final EngineTuning tuning;
    private final FaceAnalyzerFactory analyzerFactory;
    private final TimeSource clock;
    private final RandomSource random;
    private final CooldownTracker cooldown;
    private final AuditRecorder recorder;
    private final boolean ownThread;

    private final BlockingQueue<Input> inputs = new LinkedBlockingQueue<Input>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final Object lock = new Object();
    // Guarded by lock.
    private Phase phase = Phase.NEW;
    private boolean cancelRequested;
    private EventDispatcher dispatcher;
    private FrameSource source;

    // Worker-owned.
    private FramePump pump;
    private FaceAnalyzer analyzer;
    private SessionStateMachine sm;
    private long lastAnalysisMs = Long.MIN_VALUE / 2;
    private boolean cleanedUp;

    private volatile long startMs;
    private volatile int attemptSnapshot;
    private volatile String modelVersion = CooldownRefusedSession.MODEL_UNAVAILABLE;

    /**
     * @param ownThread false only in tests, which then drive the worker with {@link #stepForTest}.
     */
    DefaultLivenessSession(WorkflowType workflow, LivenessPolicy policy, EngineTuning tuning,
                           FaceAnalyzerFactory analyzerFactory, AuditSink audit, TimeSource clock,
                           RandomSource random, CooldownTracker cooldown, boolean ownThread) {
        this.workflow = workflow; this.policy = policy; this.tuning = tuning; this.analyzerFactory = analyzerFactory;
        this.clock = clock; this.random = random; this.cooldown = cooldown; this.ownThread = ownThread;
        this.recorder = new AuditRecorder(audit, workflow, sessionId, null, clock, "core");
    }

    // ------------------------------------------------------------------ LivenessSession

    @Override
    public void start(FrameSource source, LivenessListener listener) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        synchronized (lock) {
            if (phase != Phase.NEW) throw new IllegalStateException("Session already started");
            dispatcher = new EventDispatcher(listener, "liveness-events-" + shortId());
            startMs = clock.nowMs();
            if (cancelRequested) {
                phase = Phase.DONE;
                dispatcher.complete(cancelledResult());
                return;
            }
            this.source = source;
            phase = Phase.RUNNING;
        }
        pump = new FramePump(clock, new FramePump.Sink() {
            @Override
            public void frameAvailable() {
                if (wakePending.compareAndSet(false, true)) inputs.offer(Input.WAKE);
            }

            @Override
            public void sourceError(ErrorCode code, String detail, boolean recoverable) {
                inputs.offer(new Input(InputKind.SOURCE_ERROR, code, detail, recoverable));
            }
        });
        try {
            FrameSource.DeviceInfo info = source.info();
            if (info != null) recorder.setDevice(info.deviceId, info.certificationLevel);
        } catch (RuntimeException ignored) {
            // Device identity is optional audit context.
        }
        startSource();
        if (ownThread) {
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWorker();
                }
            }, "liveness-" + shortId());
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void retry() {
        inputs.offer(Input.RETRY); // the state machine ignores it unless a retry is available
    }

    @Override
    public void cancel() {
        synchronized (lock) {
            if (cancelRequested) return;
            cancelRequested = true;
            if (phase == Phase.NEW) return; // start() will deliver the result
            phase = Phase.DONE;
            dispatcher.complete(cancelledResult()); // loses the race harmlessly if a final result was already sent
        }
        inputs.offer(Input.CANCEL);
        stopSource();
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    // ------------------------------------------------------------------ worker

    private void runWorker() {
        try {
            init();
            while (step(MAX_WAIT_MS)) {
                // loop until the state machine reaches a terminal state
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fatal(ErrorCode.ENGINE_ERROR);
        } catch (Throwable t) {
            fatal(ErrorCode.ENGINE_ERROR); // the listener must always get exactly one final result
            if (t instanceof Error) throw (Error) t;
        } finally {
            cleanup();
        }
    }

    /** Creates the analyzer and starts the state machine. Worker thread only. */
    void init() {
        ErrorCode initError = null;
        try {
            analyzer = analyzerFactory.create();
            String v = analyzer.modelVersion();
            if (v != null) modelVersion = v;
            recorder.setModelVersion(modelVersion);
            recorder.modelLoaded();
        } catch (LivenessEngineException e) {
            initError = e.code() == ErrorCode.MODEL_INTEGRITY_FAILED ? ErrorCode.MODEL_INTEGRITY_FAILED : ErrorCode.ENGINE_ERROR;
        } catch (RuntimeException e) {
            initError = ErrorCode.ENGINE_ERROR;
        }
        sm = new SessionStateMachine(policy, tuning, sessionId, modelVersion, recorder,
                new ChallengeSelector(random), new ChallengeRunner(policy, tuning, random));
        long now = clock.nowMs();
        if (initError != null) {
            apply(sm.onFatal(initError, now));
        } else {
            apply(sm.start(now));
        }
    }

    /**
     * One worker iteration: drain inputs (waiting up to {@code maxWaitMs} for the first), analyse the latest frame
     * if one is due, tick. Returns false once the session is over.
     */
    boolean step(long maxWaitMs) throws InterruptedException {
        // With a frame waiting, wake up exactly when it is due; otherwise sleep until an input (a new frame wakes us).
        long wait = pump.hasFrame() ? Math.min(maxWaitMs, Math.max(0, nextAnalysisDueMs() - clock.nowMs())) : maxWaitMs;
        Input in = wait > 0 ? inputs.poll(wait, TimeUnit.MILLISECONDS) : inputs.poll();
        while (in != null && !sm.isTerminal()) {
            handle(in);
            in = inputs.poll();
        }
        if (!sm.isTerminal()) analyseIfDue();
        if (!sm.isTerminal()) {
            recorder.setFramesDropped(pump.dropped());
            apply(sm.onTick(clock.nowMs(), pump.lastArrivalMs()));
        }
        attemptSnapshot = sm.attemptNumber();
        return !sm.isTerminal();
    }

    private void handle(Input in) {
        long now = clock.nowMs();
        switch (in.kind) {
            case WAKE:
                wakePending.set(false);
                break;
            case RETRY:
                apply(sm.onRetry(now));
                break;
            case CANCEL:
                apply(sm.onCancel(now));
                break;
            case SOURCE_ERROR:
                apply(sm.onSourceError(in.code, in.recoverable, in.detail, now));
                break;
            default:
                break;
        }
    }

    private void analyseIfDue() {
        if (sm.state() == SessionStateMachine.State.RETRY_AVAILABLE) {
            pump.discard(); // preview keeps running; nothing to judge until the user presses Retry
            return;
        }
        long now = clock.nowMs();
        if (now < nextAnalysisDueMs()) return; // throttled: the waiting frame may be replaced by a newer one
        Frame frame = pump.take();
        if (frame == null) return;
        lastAnalysisMs = now;
        FrameAnalysis a;
        try {
            a = analyzer.analyze(frame);
        } catch (LivenessEngineException e) {
            apply(sm.onAnalyzerError(e.code() != null ? e.code() : ErrorCode.ENGINE_ERROR, clock.nowMs()));
            return;
        } catch (RuntimeException e) {
            apply(sm.onAnalyzerError(ErrorCode.ENGINE_ERROR, clock.nowMs()));
            return;
        }
        if (a == null || a.frame == null) {
            apply(sm.onAnalyzerError(ErrorCode.ENGINE_ERROR, clock.nowMs()));
            return;
        }
        apply(sm.onFrame(a, clock.nowMs()));
    }

    private long nextAnalysisDueMs() {
        if (sm == null) return 0;
        int fps = sm.inActiveChallenge() ? Math.max(policy.analyzeFps, tuning.activeMaxFps) : policy.analyzeFps;
        return lastAnalysisMs + 1000L / Math.max(1, fps);
    }

    private void apply(List<Effect> effects) {
        for (Effect e : effects) {
            switch (e.kind) {
                case EMIT:
                    dispatcher.emit(e.event);
                    break;
                case COMPLETE:
                    dispatcher.complete(e.result);
                    synchronized (lock) { phase = Phase.DONE; }
                    break;
                case STOP_SOURCE:
                    stopSource();
                    break;
                case RESTART_SOURCE:
                    stopSource();
                    startSource();
                    break;
                case TRIP_COOLDOWN:
                    cooldown.trip(workflow, policy.cooldownSec);
                    break;
                default:
                    break;
            }
        }
    }

    private void fatal(ErrorCode code) {
        if (sm != null) {
            apply(sm.onFatal(code, clock.nowMs()));
        } else {
            dispatcher.emit(LivenessEvent.failure(LivenessEvent.Type.FAILED_FINAL, code, attemptSnapshot, 0, false));
            dispatcher.complete(new LivenessResult(LivenessResult.Decision.FAILED, LivenessResult.PassPath.NONE, code,
                    null, Float.NaN, Collections.<ChallengeType>emptyList(), attemptSnapshot,
                    clock.nowMs() - startMs, sessionId, modelVersion));
        }
    }

    /** Releases the source and the analyzer. Worker thread only; idempotent. */
    void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;
        if (pump != null) pump.close();
        stopSource();
        if (analyzer != null) {
            try {
                analyzer.close();
            } catch (RuntimeException ignored) {
                // Closing is best effort.
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void startSource() {
        try {
            source.start(pump);
        } catch (RuntimeException e) {
            // Contract: start() never throws. Treat a violation as a recoverable unavailable device.
            inputs.offer(new Input(InputKind.SOURCE_ERROR, ErrorCode.DEVICE_UNAVAILABLE, "start threw", true));
        }
    }

    private void stopSource() {
        FrameSource s;
        synchronized (lock) { s = source; }
        if (s == null) return;
        try {
            s.stop();
        } catch (RuntimeException ignored) {
            // stop() is best effort and idempotent.
        }
    }

    private LivenessResult cancelledResult() {
        return new LivenessResult(LivenessResult.Decision.CANCELLED, LivenessResult.PassPath.NONE, ErrorCode.CANCELLED,
                null, Float.NaN, Collections.<ChallengeType>emptyList(), attemptSnapshot,
                Math.max(0, clock.nowMs() - startMs), sessionId, modelVersion);
    }

    private String shortId() {
        return sessionId.substring(0, 8);
    }

    // ------------------------------------------------------------------ test hooks (package-private)

    /** Tests: run the worker in the calling thread. Returns false once the session is over. */
    boolean stepForTest() throws InterruptedException {
        if (sm == null) init();
        if (sm.isTerminal()) {
            drainForTest();
            return false;
        }
        boolean more = step(0);
        if (!more) drainForTest();
        return more;
    }

    private void drainForTest() throws InterruptedException {
        Input in;
        while ((in = inputs.poll()) != null) {
            if (in.kind == InputKind.CANCEL) apply(sm.onCancel(clock.nowMs()));
        }
        cleanup();
    }

    SessionStateMachine.State stateForTest() {
        return sm == null ? SessionStateMachine.State.IDLE : sm.state();
    }

    EventDispatcher dispatcherForTest() {
        synchronized (lock) { return dispatcher; }
    }
}
