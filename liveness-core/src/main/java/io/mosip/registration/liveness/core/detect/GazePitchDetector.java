package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * LOOK_UP / LOOK_DOWN from head pitch ({@link FaceGeometry#userPitch}: positive = looking up, see proposal P2).
 */
public final class GazePitchDetector extends AxisMotionDetector {

    public GazePitchDetector(EngineTuning tuning, ChallengeType type) {
        super(tuning, type, direction(type));
    }

    private static float direction(ChallengeType type) {
        if (type == ChallengeType.LOOK_UP) return 1f;
        if (type == ChallengeType.LOOK_DOWN) return -1f;
        throw new IllegalArgumentException("Not a look: " + type);
    }

    @Override float axis(FrameAnalysis a) { return FaceGeometry.userPitch(a); }
    @Override float baselineAxis(Baseline b) { return b.pitch; }
    @Override float minDeg() { return t.lookMinDeg; }
    @Override float returnDeg() { return t.lookReturnDeg; }
}
