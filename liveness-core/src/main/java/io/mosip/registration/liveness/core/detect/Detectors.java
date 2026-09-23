package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.core.EngineTuning;

import java.util.EnumSet;
import java.util.Set;

/** Factory: one detector per challenge type. */
public final class Detectors {
    private Detectors() { }

    public static Set<ChallengeType> supported() {
        return EnumSet.allOf(ChallengeType.class);
    }

    public static ActionDetector create(ChallengeType type, EngineTuning tuning) {
        switch (type) {
            case BLINK: return new BlinkDetector(tuning);
            case SMILE: return new SmileDetector(tuning);
            case TURN_LEFT: case TURN_RIGHT: return new HeadTurnDetector(tuning, type);
            case LOOK_UP: case LOOK_DOWN: return new GazePitchDetector(tuning, type);
            default: throw new IllegalArgumentException("Unsupported challenge " + type);
        }
    }
}
