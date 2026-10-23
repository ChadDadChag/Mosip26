package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * TURN_LEFT / TURN_RIGHT from yaw. "Left" is the USER's left: {@link FaceGeometry#userYaw} is positive when the
 * subject turns to their left, so TURN_LEFT looks for +yaw and TURN_RIGHT for -yaw.
 */
public final class HeadTurnDetector extends AxisMotionDetector {

    public HeadTurnDetector(EngineTuning tuning, ChallengeType type) {
        super(tuning, type, direction(type));
    }

    private static float direction(ChallengeType type) {
        if (type == ChallengeType.TURN_LEFT) return 1f;
        if (type == ChallengeType.TURN_RIGHT) return -1f;
        throw new IllegalArgumentException("Not a turn: " + type);
    }

    @Override float axis(FrameAnalysis a) { return FaceGeometry.userYaw(a); }
    @Override float baselineAxis(Baseline b) { return b.yaw; }
    @Override float minDeg() { return t.turnMinDeg; }
    @Override float returnDeg() { return t.turnReturnDeg; }
}
