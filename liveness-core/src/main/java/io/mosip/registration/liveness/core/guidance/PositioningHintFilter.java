package io.mosip.registration.liveness.core.guidance;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * Turns per-frame problems into debounced UI hints so the screen does not flicker:
 * a hint is shown only after it persisted {@link EngineTuning#hintPersistMs}, the shown hint changes at most every
 * {@link EngineTuning#hintMinIntervalMs}, and a shown hint is cleared once the problem has been gone for
 * {@code hintPersistMs}. Pure logic; time is passed in.
 */
public final class PositioningHintFilter {

    /** What to tell the UI after this frame. */
    public static final class Output {
        public static final Output NONE = new Output(null, false);
        public static final Output CLEAR = new Output(null, true);
        /** Non-null when a (new) hint must be shown. */
        public final Guidance show;
        /** True when the previously shown hint no longer applies. */
        public final boolean clear;

        private Output(Guidance show, boolean clear) {
            this.show = show;
            this.clear = clear;
        }

        static Output show(Guidance g) {
            return new Output(g, false);
        }
    }

    private final EngineTuning tuning;
    private Guidance candidate;
    private long candidateSinceMs;
    private Guidance shown;
    private long shownAtMs = Long.MIN_VALUE / 2;

    public PositioningHintFilter(EngineTuning tuning) {
        this.tuning = tuning;
    }

    public void reset() {
        candidate = null;
        shown = null;
        shownAtMs = Long.MIN_VALUE / 2;
    }

    public Guidance shown() {
        return shown;
    }

    /**
     * @param challengeMode during a challenge the user is asked to turn/look away, so pose, centring and size
     *                      hints are suppressed; only face count and lighting remain.
     */
    public Output update(FrameAnalysis a, long nowMs, boolean challengeMode) {
        Guidance g = classify(a, challengeMode);
        if (g != candidate) {
            candidate = g;
            candidateSinceMs = nowMs;
        }
        if (nowMs - candidateSinceMs < tuning.hintPersistMs) return Output.NONE;
        if (candidate == shown) return Output.NONE;
        if (candidate == null) {
            shown = null;
            return Output.CLEAR;
        }
        // NO_FACE and MULTIPLE_FACES are important enough to bypass the change-rate limit.
        boolean urgent = candidate == Guidance.NO_FACE || candidate == Guidance.MULTIPLE_FACES;
        if (!urgent && shown != null && nowMs - shownAtMs < tuning.hintMinIntervalMs) return Output.NONE;
        shown = candidate;
        shownAtMs = nowMs;
        return Output.show(shown);
    }

    /** Highest-priority problem in this frame, or null when the frame is fine. */
    public Guidance classify(FrameAnalysis a, boolean challengeMode) {
        if (a.faceCount == 0) return Guidance.NO_FACE;
        if (a.faceCount > 1) return Guidance.MULTIPLE_FACES;
        if (a.qualityIssues.contains(FrameAnalysis.QualityIssue.TOO_DARK)
                || a.qualityIssues.contains(FrameAnalysis.QualityIssue.TOO_BRIGHT)) return Guidance.IMPROVE_LIGHTING;
        if (challengeMode) return null;
        if (a.qualityIssues.contains(FrameAnalysis.QualityIssue.FACE_TOO_SMALL)) return Guidance.MOVE_CLOSER;
        if (a.qualityIssues.contains(FrameAnalysis.QualityIssue.FACE_TOO_LARGE)) return Guidance.MOVE_BACK;
        if (a.qualityIssues.contains(FrameAnalysis.QualityIssue.FACE_OFF_CENTER)
                || a.qualityIssues.contains(FrameAnalysis.QualityIssue.FACE_CUT_OFF)) return Guidance.CENTER_FACE;
        if (a.qualityIssues.contains(FrameAnalysis.QualityIssue.BLURRY)) return Guidance.HOLD_STEADY;
        if (Math.abs(a.yawDeg) > tuning.maxPassivePoseDeg || Math.abs(a.pitchDeg) > tuning.maxPassivePoseDeg)
            return Guidance.LOOK_AT_CAMERA;
        return null;
    }
}
