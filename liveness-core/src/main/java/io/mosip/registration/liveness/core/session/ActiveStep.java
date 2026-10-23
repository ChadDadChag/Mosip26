package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.core.audit.AuditRecorder.ChallengeOutcome;

/** Something the active phase wants the session to do or report. Immutable. */
public final class ActiveStep {

    public enum Kind { PROMPT, PROGRESS, ACTION_DETECTED, GUIDANCE, CLEAR_GUIDANCE, HOLD_STILL, PASSED, FAILED }

    public final Kind kind;
    public final ChallengeType challenge;
    public final int index, count;       // 1-based index
    public final float progress;
    public final long durationMs;        // prompt → outcome, for audit
    public final Guidance guidance;
    public final Frame bestFrame;        // PASSED
    public final ErrorCode error;        // FAILED
    public final ChallengeOutcome outcome; // FAILED (audit detail)
    public final float padMedian;        // FAILED with PAD_FAILURE

    private ActiveStep(Kind kind, ChallengeType challenge, int index, int count, float progress, long durationMs,
                       Guidance guidance, Frame bestFrame, ErrorCode error, ChallengeOutcome outcome, float padMedian) {
        this.kind = kind; this.challenge = challenge; this.index = index; this.count = count;
        this.progress = progress; this.durationMs = durationMs; this.guidance = guidance; this.bestFrame = bestFrame;
        this.error = error; this.outcome = outcome; this.padMedian = padMedian;
    }

    public static ActiveStep prompt(ChallengeType c, int index, int count) {
        return new ActiveStep(Kind.PROMPT, c, index, count, 0f, 0, null, null, null, null, Float.NaN);
    }

    public static ActiveStep progress(ChallengeType c, int index, int count, float p) {
        return new ActiveStep(Kind.PROGRESS, c, index, count, p, 0, null, null, null, null, Float.NaN);
    }

    public static ActiveStep actionDetected(ChallengeType c, int index, int count, long durationMs) {
        return new ActiveStep(Kind.ACTION_DETECTED, c, index, count, 1f, durationMs, null, null, null, null, Float.NaN);
    }

    public static ActiveStep guidance(Guidance g) {
        return new ActiveStep(Kind.GUIDANCE, null, 0, 0, 0f, 0, g, null, null, null, Float.NaN);
    }

    public static ActiveStep clearGuidance(ChallengeType c, int index, int count, float p) {
        return new ActiveStep(Kind.CLEAR_GUIDANCE, c, index, count, p, 0, null, null, null, null, Float.NaN);
    }

    public static ActiveStep holdStill() {
        return new ActiveStep(Kind.HOLD_STILL, null, 0, 0, 0f, 0, null, null, null, null, Float.NaN);
    }

    public static ActiveStep passed(Frame bestFrame) {
        return new ActiveStep(Kind.PASSED, null, 0, 0, 0f, 0, null, bestFrame, null, null, Float.NaN);
    }

    public static ActiveStep failed(ErrorCode code, ChallengeType c, ChallengeOutcome outcome, long durationMs,
                                    float padMedian) {
        return new ActiveStep(Kind.FAILED, c, 0, 0, 0f, durationMs, null, null, code, outcome, padMedian);
    }

    @Override
    public String toString() {
        return kind + (challenge != null ? "(" + challenge + ")" : "") + (error != null ? "[" + error + "]" : "");
    }
}
