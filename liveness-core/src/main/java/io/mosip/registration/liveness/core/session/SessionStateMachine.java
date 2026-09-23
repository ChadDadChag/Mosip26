package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.challenge.ChallengeSelector;
import io.mosip.registration.liveness.core.guidance.PositioningHintFilter;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;
import io.mosip.registration.liveness.core.quality.BestFrameSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The session's decision logic: every input goes in with the current time, and the method returns the side
 * effects to perform (events, the final result, source control). No threads, no clock reads, no I/O except the
 * passive {@link AuditRecorder}. Deterministic given its inputs and the injected {@link ChallengeSelector}
 * randomness, so every transition is unit-testable (see docs/person3-design.md §3).
 *
 * <p>Invariants: PASSED is only reachable from a full passive window at or above {@code passiveThreshold}, or from
 * the active phase reporting that every challenge was detected. Any attack verdict (passive median or the active
 * phase's continuous PAD check) fails the attempt. Terminal states ignore every input, so exactly one COMPLETE
 * effect is ever produced.
 */
public final class SessionStateMachine {

    public enum State { IDLE, STARTING, PASSIVE_CHECK, ACTIVE_CHALLENGE, RETRY_AVAILABLE, DEVICE_WAIT,
        PASSED, FAILED_FINAL, CANCELLED }

    private final LivenessPolicy policy;
    private final EngineTuning tuning;
    private final String sessionId;
    private final String modelVersion;
    private final AuditRecorder audit;
    private final ChallengeSelector selector;
    private final ActivePhase active;

    private final AttemptTracker tracker;
    private final PassiveEvaluator passive;
    private final PositioningHintFilter hints;
    private final BestFrameSelector best;
    private final FrameSanity sanity;

    private State state = State.IDLE;
    private long sessionStartMs;
    private long attemptStartMs;
    private long deviceErrorAtMs;
    private boolean sourceNeedsRestart;
    private long multiFaceSinceMs = -1;
    private int consecutiveEngineErrors;
    private float passiveMedian = Float.NaN;
    private List<ChallengeType> sequence = Collections.emptyList();
    private List<ChallengeType> previousSequence;
    private FrameAnalysis lastWindowFrame;
    /** Time of the input being processed; used for durations in results. */
    private long lastNowMs;

    public SessionStateMachine(LivenessPolicy policy, EngineTuning tuning, String sessionId, String modelVersion,
                               AuditRecorder audit, ChallengeSelector selector, ActivePhase active) {
        this.policy = policy; this.tuning = tuning; this.sessionId = sessionId; this.modelVersion = modelVersion;
        this.audit = audit; this.selector = selector; this.active = active;
        this.tracker = new AttemptTracker(policy.maxRetries);
        this.passive = new PassiveEvaluator(policy, tuning);
        this.hints = new PositioningHintFilter(tuning);
        this.best = new BestFrameSelector(tuning);
        this.sanity = new FrameSanity(tuning);
    }

    public State state() {
        return state;
    }

    public boolean isTerminal() {
        return state == State.PASSED || state == State.FAILED_FINAL || state == State.CANCELLED;
    }

    /** True while frames are being judged (the worker uses this to pick the analysis rate). */
    public boolean inActiveChallenge() {
        return state == State.ACTIVE_CHALLENGE;
    }

    public int attemptNumber() {
        return tracker.attemptNumber();
    }

    // ------------------------------------------------------------------ inputs

    public List<Effect> start(long nowMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        if (state != State.IDLE) return out;
        sessionStartMs = nowMs;
        beginAttempt(nowMs, out);
        return out;
    }

    public List<Effect> onFrame(FrameAnalysis a, long nowMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        if (state == State.DEVICE_WAIT) {
            if (nowMs - deviceErrorAtMs > tuning.deviceGraceMs) return out; // too late: wait for the user's Retry
            // Frames resumed within the grace period: restart the attempt automatically, no retry consumed.
            tracker.beginNextAttempt();
            sanity.resetStream();
            beginAttempt(nowMs, out);
        }
        if (state != State.STARTING && state != State.PASSIVE_CHECK && state != State.ACTIVE_CHALLENGE) return out;

        if (!sanity.accept(a.frame)) {
            if (sanity.limitReached()) failAttempt(ErrorCode.INVALID_FRAME, "non-monotonic or implausible frames", out);
            return out;
        }
        consecutiveEngineErrors = 0;
        audit.frameAnalyzed(a.processingMs);

        if (state == State.STARTING) {
            state = State.PASSIVE_CHECK;
            out.add(Effect.emit(LivenessEvent.of(Type.CHECKING_PASSIVE, attempt())));
        }
        if (state == State.PASSIVE_CHECK) {
            passiveFrame(a, nowMs, out);
        } else {
            List<ActiveStep> steps = new ArrayList<ActiveStep>();
            active.onFrame(a, nowMs, steps);
            applySteps(steps, nowMs, out);
        }
        return out;
    }

    public List<Effect> onAnalyzerError(ErrorCode code, long nowMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        if (isTerminal() || state == State.IDLE) return out;
        if (code == ErrorCode.MODEL_INTEGRITY_FAILED) {
            audit.modelIntegrityFailed("analyzer reported integrity failure");
            finishFailed(ErrorCode.MODEL_INTEGRITY_FAILED, ErrorCode.MODEL_INTEGRITY_FAILED, nowMs, out);
            return out;
        }
        if (state != State.STARTING && state != State.PASSIVE_CHECK && state != State.ACTIVE_CHALLENGE) return out;
        if (code == ErrorCode.INVALID_FRAME) {
            sanity.countViolation();
            if (sanity.limitReached()) failAttempt(ErrorCode.INVALID_FRAME, "analyzer rejected frames", out);
            return out;
        }
        if (++consecutiveEngineErrors >= tuning.engineErrorFrames) {
            consecutiveEngineErrors = 0;
            failAttempt(ErrorCode.ENGINE_ERROR, "analyzer failed on consecutive frames", out);
        }
        return out;
    }

    public List<Effect> onSourceError(ErrorCode code, boolean recoverable, String detail, long nowMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        if (isTerminal() || state == State.IDLE) return out;
        ErrorCode c = code != null ? code : ErrorCode.DEVICE_UNAVAILABLE;
        if (!recoverable) {
            audit.deviceError(c, false, detail);
            finishFailed(c, c, nowMs, out);
        } else if (state == State.RETRY_AVAILABLE) {
            audit.deviceError(c, true, detail);
            sourceNeedsRestart = true; // the user's Retry will restart the source
        } else if (state == State.DEVICE_WAIT) {
            audit.deviceError(c, true, detail);
        } else {
            deviceProblem(c, detail, nowMs, out);
        }
        return out;
    }

    /**
     * Periodic input (the worker ticks every ~50 ms) so timeouts fire even when no frames arrive.
     * @param lastFrameArrivalMs when the source last delivered any frame (analysed or dropped), -1 if never
     */
    public List<Effect> onTick(long nowMs, long lastFrameArrivalMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        switch (state) {
            case STARTING:
                if (lastFrameArrivalMs < attemptStartMs && nowMs - attemptStartMs >= tuning.startTimeoutMs) {
                    deviceProblem(ErrorCode.DEVICE_UNAVAILABLE, "no frames after start", nowMs, out);
                }
                break;
            case PASSIVE_CHECK:
                if (stalled(nowMs, lastFrameArrivalMs)) {
                    deviceProblem(ErrorCode.DEVICE_DISCONNECTED, "frame stream stalled", nowMs, out);
                } else if (multiFaceSinceMs >= 0 && nowMs - multiFaceSinceMs >= tuning.multiFaceFailMs) {
                    failAttempt(ErrorCode.MULTIPLE_FACES, "multiple faces persisted", out);
                } else {
                    handleVerdict(passive.onTick(nowMs), nowMs, out);
                }
                break;
            case ACTIVE_CHALLENGE:
                if (stalled(nowMs, lastFrameArrivalMs)) {
                    deviceProblem(ErrorCode.DEVICE_DISCONNECTED, "frame stream stalled", nowMs, out);
                } else {
                    List<ActiveStep> steps = new ArrayList<ActiveStep>();
                    active.onTick(nowMs, steps);
                    applySteps(steps, nowMs, out);
                }
                break;
            default:
                break;
        }
        return out;
    }

    public List<Effect> onRetry(long nowMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        if (state == State.RETRY_AVAILABLE || state == State.DEVICE_WAIT) {
            boolean restart = sourceNeedsRestart || state == State.DEVICE_WAIT;
            tracker.beginNextAttempt();
            if (restart) {
                sourceNeedsRestart = false;
                sanity.resetStream();
                out.add(Effect.of(Effect.Kind.RESTART_SOURCE));
            }
            beginAttempt(nowMs, out);
        }
        return out;
    }

    public List<Effect> onCancel(long nowMs) {
        lastNowMs = nowMs;
        List<Effect> out = new ArrayList<Effect>();
        if (isTerminal()) return out;
        boolean started = state != State.IDLE;
        state = State.CANCELLED;
        out.add(Effect.complete(result(LivenessResult.Decision.CANCELLED, LivenessResult.PassPath.NONE,
                ErrorCode.CANCELLED, null, nowMs, started)));
        if (started) {
            out.add(Effect.of(Effect.Kind.STOP_SOURCE));
            audit.cancelled();
        }
        return out;
    }

    // ------------------------------------------------------------------ passive

    private void passiveFrame(FrameAnalysis a, long nowMs, List<Effect> out) {
        PositioningHintFilter.Output hint = hints.update(a, nowMs, false);
        if (hint.show != null) out.add(Effect.emit(LivenessEvent.guidance(hint.show, attempt())));
        else if (hint.clear) out.add(Effect.emit(LivenessEvent.of(Type.CHECKING_PASSIVE, attempt()))); // D4

        if (a.faceCount > 1) {
            if (multiFaceSinceMs < 0) multiFaceSinceMs = nowMs;
            if (nowMs - multiFaceSinceMs >= tuning.multiFaceFailMs) {
                failAttempt(ErrorCode.MULTIPLE_FACES, "multiple faces persisted", out);
                return;
            }
        } else {
            multiFaceSinceMs = -1;
        }

        best.offer(a);
        handleVerdict(passive.offer(a, nowMs), nowMs, out);
    }

    private void handleVerdict(PassiveEvaluator.Verdict v, long nowMs, List<Effect> out) {
        if (v == PassiveEvaluator.Verdict.PENDING) return;
        passiveMedian = passive.median();
        List<FrameAnalysis> window = passive.windowFrames();
        lastWindowFrame = window.isEmpty() ? null : window.get(window.size() - 1);
        audit.passiveDecided(v.name(), passiveMedian, passive.windowSize(), passive.elapsedMs(nowMs));
        switch (v) {
            case PASS: {
                Frame bestFrame = best.hasBest() ? best.bestFrame() : lastWindowFrame.frame;
                finishPassed(LivenessResult.PassPath.PASSIVE_ONLY, bestFrame, nowMs, out);
                break;
            }
            case ATTACK:
                audit.padSuspected(passiveMedian, passive.padDetailMedians(), "passive median below padRejectThreshold");
                failAttempt(ErrorCode.PAD_FAILURE, null, out);
                break;
            case BELOW_THRESHOLD:
                failAttempt(ErrorCode.LIVENESS_BELOW_THRESHOLD, "active challenges disabled", out);
                break;
            case TIMEOUT:
                failAttempt(passive.timeoutCode(), "no usable passive window", out);
                break;
            case NEED_ACTIVE:
                try {
                    sequence = Collections.unmodifiableList(selector.select(policy.challengePool, active.supported(),
                            policy.minChallenges, previousSequence));
                } catch (IllegalArgumentException e) {
                    failAttempt(ErrorCode.ENGINE_ERROR, "no supported challenges: " + e.getMessage(), out);
                    return;
                }
                previousSequence = sequence;
                state = State.ACTIVE_CHALLENGE;
                hints.reset();
                active.start(sequence, window, nowMs);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ active

    private void applySteps(List<ActiveStep> steps, long nowMs, List<Effect> out) {
        for (ActiveStep s : steps) {
            if (state != State.ACTIVE_CHALLENGE) return; // a previous step ended the attempt
            switch (s.kind) {
                case PROMPT:
                    audit.challengeIssued(s.challenge);
                    out.add(Effect.emit(LivenessEvent.prompt(s.challenge, s.index, s.count,
                            policy.challengeTimeoutMs, attempt())));
                    break;
                case PROGRESS:
                    out.add(Effect.emit(LivenessEvent.progress(s.challenge, s.index, s.count, s.progress, attempt())));
                    break;
                case ACTION_DETECTED:
                    audit.challengeResolved(s.challenge, AuditRecorder.ChallengeOutcome.DETECTED, s.durationMs);
                    out.add(Effect.emit(LivenessEvent.of(Type.ACTION_DETECTED, attempt())));
                    break;
                case GUIDANCE:
                    out.add(Effect.emit(LivenessEvent.guidance(s.guidance, attempt())));
                    break;
                case CLEAR_GUIDANCE: // D4: a progress event means "hint no longer applies"
                    out.add(Effect.emit(LivenessEvent.progress(s.challenge, s.index, s.count, s.progress, attempt())));
                    break;
                case HOLD_STILL:
                    out.add(Effect.emit(LivenessEvent.of(Type.HOLD_STILL, attempt())));
                    break;
                case PASSED:
                    finishPassed(LivenessResult.PassPath.ACTIVE_CHALLENGE,
                            s.bestFrame != null ? s.bestFrame : fallbackFrame(), nowMs, out);
                    return;
                case FAILED:
                    if (s.challenge != null && s.outcome != null) {
                        audit.challengeResolved(s.challenge, s.outcome, s.durationMs);
                    }
                    if (s.error == ErrorCode.PAD_FAILURE) {
                        audit.padSuspected(s.padMedian, null, "rolling median below padRejectThreshold during challenge");
                    }
                    failAttempt(s.error, s.outcome != null ? s.outcome.name() : null, out);
                    return;
                default:
                    break;
            }
        }
    }

    private Frame fallbackFrame() {
        if (best.hasBest()) return best.bestFrame();
        return lastWindowFrame != null ? lastWindowFrame.frame : null;
    }

    // ------------------------------------------------------------------ attempt lifecycle

    private void beginAttempt(long nowMs, List<Effect> out) {
        attemptStartMs = nowMs;
        passive.reset(nowMs);
        hints.reset();
        best.reset();
        sanity.resetAttempt();
        multiFaceSinceMs = -1;
        consecutiveEngineErrors = 0;
        passiveMedian = Float.NaN;
        sequence = Collections.emptyList();
        lastWindowFrame = null;
        state = State.STARTING;
        audit.attemptStarted(attempt());
        out.add(Effect.emit(LivenessEvent.of(Type.STARTED, attempt())));
    }

    private void deviceProblem(ErrorCode code, String detail, long nowMs, List<Effect> out) {
        audit.deviceError(code, true, detail);
        tracker.onAttemptFailed(code); // device errors never consume a retry
        deviceErrorAtMs = nowMs;
        state = State.DEVICE_WAIT;
        out.add(Effect.emit(LivenessEvent.failure(Type.DEVICE_ERROR, code, attempt(), tracker.retriesLeft(), true)));
    }

    private void failAttempt(ErrorCode cause, String reason, List<Effect> out) {
        AttemptTracker.Decision d = tracker.onAttemptFailed(cause);
        if (d.isFinal) {
            finishFailed(d.code, cause, lastNowMs, out);
            return;
        }
        audit.attemptFailed(cause, d.retriesLeft, reason);
        state = State.RETRY_AVAILABLE;
        out.add(Effect.emit(LivenessEvent.failure(Type.ATTEMPT_FAILED, d.code, attempt(), d.retriesLeft, true)));
    }

    private void finishPassed(LivenessResult.PassPath path, Frame bestFrame, long nowMs, List<Effect> out) {
        state = State.PASSED;
        out.add(Effect.emit(LivenessEvent.of(Type.PASSED, attempt())));
        out.add(Effect.complete(result(LivenessResult.Decision.PASSED, path, null, bestFrame, nowMs, true)));
        out.add(Effect.of(Effect.Kind.STOP_SOURCE));
        audit.passed(path, passiveMedian);
    }

    private void finishFailed(ErrorCode finalCode, ErrorCode lastCause, long nowMs, List<Effect> out) {
        state = State.FAILED_FINAL;
        out.add(Effect.emit(LivenessEvent.failure(Type.FAILED_FINAL, finalCode, attempt(), 0, false)));
        out.add(Effect.complete(result(LivenessResult.Decision.FAILED, LivenessResult.PassPath.NONE, finalCode,
                null, nowMs, true)));
        out.add(Effect.of(Effect.Kind.STOP_SOURCE));
        boolean cooldown = finalCode == ErrorCode.MAX_RETRIES_EXCEEDED
                && policy.onRepeatedFailure == LivenessPolicy.OnRepeatedFailure.COOLDOWN;
        if (cooldown) out.add(Effect.of(Effect.Kind.TRIP_COOLDOWN));
        audit.failedFinal(finalCode, lastCause, cooldown ? policy.cooldownSec : 0);
    }

    private LivenessResult result(LivenessResult.Decision decision, LivenessResult.PassPath path, ErrorCode code,
                                  Frame bestFrame, long nowMs, boolean started) {
        return new LivenessResult(decision, path, code, bestFrame, passiveMedian, sequence,
                started ? tracker.attemptNumber() : 0, started ? Math.max(0, nowMs - sessionStartMs) : 0,
                sessionId, modelVersion);
    }

    private boolean stalled(long nowMs, long lastFrameArrivalMs) {
        long since = Math.max(lastFrameArrivalMs, attemptStartMs);
        return nowMs - since >= tuning.frameStallMs;
    }

    private int attempt() {
        return tracker.attemptNumber();
    }
}
