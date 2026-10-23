package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * Shared logic for "move the head along one axis and hold": turn left/right (yaw) and look up/down (pitch).
 * Signed change from baseline in the requested direction must reach {@code minDeg} and be held for
 * {@code actionHoldMs} over {@code actionHoldFrames} frames; that is DETECTED (the runner then shows
 * LOOK_AT_CAMERA). With {@code axisRequireReturn} the head must also come back within {@code returnDeg} of
 * baseline, the original rule. Moving {@code minDeg} the opposite way, before or after the correct move (head
 * wiggling from a replayed video), is WRONG_ACTION.
 */
abstract class AxisMotionDetector implements ActionDetector {

    private enum Phase { ARMING, MOVING, HOLDING, RETURNING }

    protected final EngineTuning t;
    private final ChallengeType type;
    private final float direction; // +1 or -1 in the axis' user convention
    private Baseline baseline;
    private long promptAtMs;
    private Phase phase;
    private long holdSinceMs;
    private int holdFrames;
    private float progress;

    AxisMotionDetector(EngineTuning tuning, ChallengeType type, float direction) {
        this.t = tuning; this.type = type; this.direction = direction;
    }

    /** The axis value in the user convention (see {@link FaceGeometry}). */
    abstract float axis(FrameAnalysis a);

    abstract float baselineAxis(Baseline b);

    abstract float minDeg();

    abstract float returnDeg();

    @Override public ChallengeType type() { return type; }

    @Override
    public void reset(Baseline baseline, long promptAtMs) {
        this.baseline = baseline;
        this.promptAtMs = promptAtMs;
        phase = Phase.ARMING;
        holdFrames = 0;
        progress = 0f;
    }

    @Override
    public DetectorStatus update(FrameAnalysis a, long nowMs) {
        float value = axis(a);
        float base = baselineAxis(baseline);
        if (a.faceCount != 1 || Float.isNaN(value) || Float.isNaN(base)) return status();
        float delta = (value - base) * direction; // > 0 = requested direction
        float min = minDeg();

        if (phase == Phase.ARMING) {
            if (nowMs - promptAtMs < t.minReactionMs || Math.abs(delta) > t.armNeutralFraction * min) {
                return DetectorStatus.ARMING;
            }
            phase = Phase.MOVING;
        }
        if (delta <= -min) {
            progress = 0f;
            return DetectorStatus.WRONG_ACTION;
        }
        switch (phase) {
            case MOVING:
                progress = Math.max(0f, Math.min(0.6f, 0.6f * delta / min));
                if (delta >= min) {
                    phase = Phase.HOLDING;
                    holdSinceMs = nowMs;
                    holdFrames = 1;
                    progress = 0.7f;
                }
                break;
            case HOLDING:
                if (delta >= min) {
                    holdFrames++;
                    if (holdFrames >= t.actionHoldFrames && nowMs - holdSinceMs >= t.actionHoldMs) {
                        if (!t.axisRequireReturn) {
                            progress = 1f;
                            return DetectorStatus.DETECTED;
                        }
                        phase = Phase.RETURNING;
                        progress = 0.85f;
                    }
                } else {
                    phase = Phase.MOVING; // dropped out before the hold completed
                    holdFrames = 0;
                }
                break;
            case RETURNING:
                if (Math.abs(delta) <= returnDeg()) {
                    progress = 1f;
                    return DetectorStatus.DETECTED;
                }
                break;
            default:
                break;
        }
        return status();
    }

    private DetectorStatus status() {
        return phase == Phase.ARMING ? DetectorStatus.ARMING : DetectorStatus.IN_PROGRESS;
    }

    @Override public float progress() { return progress; }
}
