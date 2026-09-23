package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.policy.CooldownTracker;
import io.mosip.registration.liveness.core.policy.PolicyResolver;
import io.mosip.registration.liveness.core.scenario.ScriptedFaceAnalyzer;
import io.mosip.registration.liveness.core.scenario.ScriptedFaceAnalyzer.Shape;
import io.mosip.registration.liveness.core.scenario.ScriptedFrameSource;
import io.mosip.registration.liveness.core.testing.Fa;
import io.mosip.registration.liveness.core.util.ManualTimeSource;
import io.mosip.registration.liveness.core.util.SeededRandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scenario DSL: drives a REAL {@link DefaultLivenessSession} (pump, analyzer calls, state machine, challenge runner,
 * dispatcher) with a manual clock, a scripted source and a scripted analyzer. The camera delivers a frame every
 * {@link #CAMERA_MS}; the session's own throttle decides which ones are analysed. No sleeping: time only moves when
 * the driver says so.
 *
 * <pre>
 * ScenarioDriver d = ScenarioDriver.resident();
 * d.frames(15, d.score(0.6f)).untilPrompt().perform().untilDone();
 * </pre>
 */
public final class ScenarioDriver {

    public static final long CAMERA_MS = 33; // 30 fps camera

    public final LivenessPolicy policy;
    public final EngineTuning tuning = EngineTuning.defaults();
    public final ManualTimeSource clock = new ManualTimeSource(100000);
    public final ScriptedFrameSource source = new ScriptedFrameSource();
    public final ScriptedFaceAnalyzer analyzer = new ScriptedFaceAnalyzer();
    public final CooldownTracker cooldown = new CooldownTracker(clock);
    public final List<LivenessEvent> events = Collections.synchronizedList(new ArrayList<LivenessEvent>());
    public final List<Object> timeline = Collections.synchronizedList(new ArrayList<Object>());
    public final List<AuditSink.Event> audit = Collections.synchronizedList(new ArrayList<AuditSink.Event>());
    public final List<Map<String, String>> auditDetails = Collections.synchronizedList(new ArrayList<Map<String, String>>());
    public final Set<Frame> midActionFrames = new HashSet<Frame>();
    public volatile LivenessResult result;
    public volatile int completions;
    public volatile boolean autoRetry = true;

    final DefaultLivenessSession session;
    private final WorkflowType workflow;
    private long seq;
    private boolean frozen;
    private int promptsHandled;

    public static ScenarioDriver resident() { return new ScenarioDriver(WorkflowType.RESIDENT, null); }
    public static ScenarioDriver operator() { return new ScenarioDriver(WorkflowType.OPERATOR, null); }
    public static ScenarioDriver supervisor() { return new ScenarioDriver(WorkflowType.SUPERVISOR, null); }

    public static ScenarioDriver with(WorkflowType wf, LivenessPolicyTweak tweak) { return new ScenarioDriver(wf, tweak); }

    public interface LivenessPolicyTweak { void apply(LivenessPolicy p); }

    ScenarioDriver(WorkflowType workflow, LivenessPolicyTweak tweak) {
        this(workflow, tweak, null, 7);
    }

    ScenarioDriver(WorkflowType workflow, LivenessPolicyTweak tweak, final ErrorCode factoryError, long seed) {
        this.workflow = workflow;
        this.policy = PolicyResolver.resolve(null, workflow).policy; // bundled defaults per workflow
        if (tweak != null) tweak.apply(policy);
        FaceAnalyzerFactory factory = new FaceAnalyzerFactory() {
            @Override public FaceAnalyzer create() throws LivenessEngineException {
                if (factoryError == ErrorCode.MODEL_INTEGRITY_FAILED) {
                    throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED, "checksum mismatch");
                }
                if (factoryError != null) throw new IllegalStateException("cannot load");
                return analyzer;
            }
        };
        AuditSink sink = new AuditSink() {
            @Override public void record(Event e, WorkflowType wf, String s, Map<String, String> d) {
                audit.add(e);
                auditDetails.add(d);
            }
        };
        session = new DefaultLivenessSession(workflow, policy, tuning, factory, sink, clock,
                new SeededRandomSource(seed), cooldown, false);
        session.start(source, new LivenessListener() {
            @Override public void onEvent(LivenessEvent e) {
                events.add(e);
                timeline.add(e);
                if (autoRetry && (e.type == Type.ATTEMPT_FAILED || (e.type == Type.DEVICE_ERROR && e.recoverable))) {
                    session.retry();
                }
            }

            @Override public void onComplete(LivenessResult r) {
                result = r;
                completions++;
                timeline.add(r);
            }
        });
    }

    // ------------------------------------------------------------------ shapes

    public Shape score(final float s) {
        return new Shape() { @Override public Fa apply(Fa f) { return f.score(s); } };
    }

    public static final Shape NO_FACE = new Shape() { @Override public Fa apply(Fa f) { return f.faces(0); } };
    public static final Shape TWO_FACES = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).faces(2); } };

    public static Shape and(final Shape base, final Shape extra) {
        return new Shape() { @Override public Fa apply(Fa f) { return extra.apply(base.apply(f)); } };
    }

    /** The action a cooperative user performs for a prompt, frame by frame (0-based). */
    public static Shape action(final ChallengeType c, final float score, final int k, final boolean opposite) {
        return new Shape() {
            @Override public Fa apply(Fa f) {
                Fa g = f.score(score);
                float s = opposite ? -1 : 1;
                switch (c) {
                    case BLINK: return k >= 2 && k < 5 ? g.eyesClosed() : g;
                    case SMILE: return k < 12 ? g.smile() : g;
                    case TURN_LEFT: return k < 12 ? g.yaw(26 * s) : g;
                    case TURN_RIGHT: return k < 12 ? g.yaw(-26 * s) : g;
                    case LOOK_UP: return k < 12 ? g.pitch(18 * s) : g;
                    default: return k < 12 ? g.pitch(-18 * s) : g;
                }
            }
        };
    }

    // ------------------------------------------------------------------ steps

    /** One camera frame (33 ms later) followed by one worker step. */
    public ScenarioDriver frame(Shape shape) {
        clock.advance(CAMERA_MS);
        long s = frozen ? seq : ++seq;
        analyzer.script(s, shape);
        source.push(frozen ? 100000 + seq * CAMERA_MS : clock.nowMs(), s);
        step();
        return this;
    }

    public ScenarioDriver frames(int n, Shape shape) {
        for (int i = 0; i < n && !done(); i++) frame(shape);
        return this;
    }

    /** Time passes with no frames at all (worker ticks every 50 ms). */
    public ScenarioDriver noFramesFor(long ms) {
        for (long t = 0; t < ms && !done(); t += 50) {
            clock.advance(50);
            step();
        }
        return this;
    }

    /** From now on the camera repeats the same timestamp and sequence (frozen / replayed stream). */
    public ScenarioDriver freeze() {
        frozen = true;
        return this;
    }

    public ScenarioDriver analyzerFails(int frames, ErrorCode code) {
        for (int i = 0; i < frames && !done(); i++) {
            clock.advance(130); // one analysis per frame
            long s = ++seq;
            analyzer.fail(s, code);
            source.push(clock.nowMs(), s);
            step();
        }
        return this;
    }

    public ScenarioDriver deviceError(ErrorCode code, boolean recoverable) {
        source.error(code, recoverable);
        step();
        return this;
    }

    public ScenarioDriver retry() {
        session.retry();
        step();
        return this;
    }

    public ScenarioDriver cancel() {
        session.cancel();
        step();
        return this;
    }

    /** Neutral mid-score frames until a new CHALLENGE_PROMPT has been delivered. */
    public ScenarioDriver untilPrompt() {
        return untilPrompt(score(0.6f));
    }

    public ScenarioDriver untilPrompt(Shape neutral) {
        int target = promptsHandled + 1;
        for (int i = 0; i < 300 && !done() && count(Type.CHALLENGE_PROMPT) < target; i++) frame(neutral);
        return this;
    }

    /** Performs the latest prompt like a cooperative live user (after a human reaction delay). */
    public ScenarioDriver perform() {
        return perform(0.6f, false);
    }

    public ScenarioDriver performOpposite() {
        return perform(0.6f, true);
    }

    public ScenarioDriver perform(float score, boolean opposite) {
        LivenessEvent p = lastEvent(Type.CHALLENGE_PROMPT);
        if (p == null) throw new IllegalStateException("no prompt yet: " + types());
        promptsHandled = count(Type.CHALLENGE_PROMPT);
        frames(9, score(score)); // ~300 ms reaction
        for (int k = 0; k < 24 && !done(); k++) {
            Shape a = action(p.challenge, score, k, opposite);
            frame(a);
        }
        return this;
    }

    /** Answers every prompt until the session ends or waits for Retry. */
    public ScenarioDriver performAll() {
        for (int i = 0; i < 10 && !done(); i++) {
            untilPrompt();
            if (done() || count(Type.CHALLENGE_PROMPT) <= promptsHandled) break;
            perform();
        }
        return untilDone(score(0.6f));
    }

    /** Neutral frames until a final result. */
    public ScenarioDriver untilDone(Shape shape) {
        for (int i = 0; i < 2000 && !done(); i++) frame(shape);
        return this;
    }

    public void step() {
        try {
            session.stepForTest();
            DefaultLivenessSession s = session;
            EventDispatcher d = s.dispatcherForTest();
            if (d != null && !d.awaitIdle(5000)) throw new AssertionError("dispatcher did not drain");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ queries

    public boolean done() {
        return result != null;
    }

    public int count(Type t) {
        int n = 0;
        synchronized (events) {
            for (LivenessEvent e : events) if (e.type == t) n++;
        }
        return n;
    }

    public LivenessEvent lastEvent(Type t) {
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).type == t) return events.get(i);
        }
        return null;
    }

    public List<Type> types() {
        List<Type> out = new ArrayList<Type>();
        synchronized (events) {
            for (LivenessEvent e : events) out.add(e.type);
        }
        return out;
    }

    /** Errors of all ATTEMPT_FAILED events, in order. */
    public List<ErrorCode> attemptErrors() {
        List<ErrorCode> out = new ArrayList<ErrorCode>();
        synchronized (events) {
            for (LivenessEvent e : events) if (e.type == Type.ATTEMPT_FAILED) out.add(e.error);
        }
        return out;
    }

    public WorkflowType workflow() {
        return workflow;
    }
}
