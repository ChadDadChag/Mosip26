package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * Smile = mouth width / inter-ocular distance rises {@code smileWidthIncrease} over baseline (or a smaller
 * {@code smileWidthIncreaseWithLift} together with the corners lifting by {@code smileLiftIncrease}), held for
 * {@code actionHoldMs} over at least {@code actionHoldFrames} frames. Normalising by inter-ocular distance makes it
 * independent of distance to the camera.
 */
public final class SmileDetector implements ActionDetector {

    private final EngineTuning t;
    private Baseline baseline;
    private long promptAtMs;
    private boolean armed;
    private long smilingSinceMs = -1;
    private int smilingFrames;
    private float progress;

    public SmileDetector(EngineTuning tuning) {
        this.t = tuning;
    }

    @Override public ChallengeType type() { return ChallengeType.SMILE; }

    @Override
    public void reset(Baseline baseline, long promptAtMs) {
        this.baseline = baseline;
        this.promptAtMs = promptAtMs;
        armed = false;
        smilingSinceMs = -1;
        smilingFrames = 0;
        progress = 0f;
    }

    @Override
    public DetectorStatus update(FrameAnalysis a, long nowMs) {
        float width = FaceGeometry.mouthWidthRatio(a.landmarks);
        if (a.faceCount != 1 || Float.isNaN(width) || !(baseline.mouthWidth > 0)) {
            return armed ? DetectorStatus.IN_PROGRESS : DetectorStatus.ARMING;
        }
        float increase = width / baseline.mouthWidth - 1f;
        float lift = FaceGeometry.cornerLift(a.landmarks);
        float liftIncrease = Float.isNaN(lift) || Float.isNaN(baseline.cornerLift) ? 0f : lift - baseline.cornerLift;

        if (!armed) {
            if (nowMs - promptAtMs >= t.minReactionMs && increase <= t.armNeutralFraction * t.smileWidthIncrease) {
                armed = true;
            } else {
                return DetectorStatus.ARMING;
            }
        }
        boolean smiling = increase >= t.smileWidthIncrease
                || (increase >= t.smileWidthIncreaseWithLift && liftIncrease >= t.smileLiftIncrease);
        if (smiling) {
            if (smilingSinceMs < 0) smilingSinceMs = nowMs;
            smilingFrames++;
            if (smilingFrames >= t.actionHoldFrames && nowMs - smilingSinceMs >= t.actionHoldMs) {
                progress = 1f;
                return DetectorStatus.DETECTED;
            }
            progress = 0.75f;
        } else {
            smilingSinceMs = -1;
            smilingFrames = 0;
            progress = Math.max(0f, Math.min(0.6f, 0.6f * increase / t.smileWidthIncrease));
        }
        return DetectorStatus.IN_PROGRESS;
    }

    @Override public float progress() { return progress; }
}
