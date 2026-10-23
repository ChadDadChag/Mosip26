package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * Blink = EAR drops below {@code blinkClosedRatio × baseline} and comes back above
 * {@code blinkReopenRatio × baseline} within {@code blinkMaxClosedMs}. Longer closures (eyes kept shut, a
 * closed-eye photo) are not blinks: the detector waits for the eyes to reopen and starts over.
 */
public final class BlinkDetector implements ActionDetector {

    private enum Phase { ARMING, OPEN, CLOSED }

    private final EngineTuning t;
    private Baseline baseline;
    private long promptAtMs;
    private Phase phase;
    private long closedAtMs;
    private boolean tooLong;
    private float progress;

    public BlinkDetector(EngineTuning tuning) {
        this.t = tuning;
    }

    @Override public ChallengeType type() { return ChallengeType.BLINK; }

    @Override
    public void reset(Baseline baseline, long promptAtMs) {
        this.baseline = baseline;
        this.promptAtMs = promptAtMs;
        phase = Phase.ARMING;
        tooLong = false;
        progress = 0f;
    }

    @Override
    public DetectorStatus update(FrameAnalysis a, long nowMs) {
        float ear = FaceGeometry.meanEar(a.landmarks);
        if (a.faceCount != 1 || Float.isNaN(ear) || !(baseline.ear > 0)) return status();
        float closed = t.blinkClosedRatio * baseline.ear;
        float open = t.blinkReopenRatio * baseline.ear;
        switch (phase) {
            case ARMING:
                if (nowMs - promptAtMs >= t.minReactionMs && ear >= open) {
                    phase = Phase.OPEN;
                    progress = 0.1f;
                }
                break;
            case OPEN:
                if (ear < closed) {
                    phase = Phase.CLOSED;
                    closedAtMs = nowMs;
                    tooLong = false;
                    progress = 0.6f;
                }
                break;
            case CLOSED:
                if (nowMs - closedAtMs > t.blinkMaxClosedMs) {
                    tooLong = true;
                    progress = 0.1f;
                }
                if (ear >= open) {
                    if (!tooLong && nowMs - closedAtMs <= t.blinkMaxClosedMs) {
                        progress = 1f;
                        return DetectorStatus.DETECTED;
                    }
                    phase = Phase.OPEN; // too slow to be a blink: start over
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
