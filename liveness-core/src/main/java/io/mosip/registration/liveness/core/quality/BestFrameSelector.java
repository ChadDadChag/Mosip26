package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.FaceGeometry;

/**
 * Picks the frame handed back in {@code LivenessResult.bestFrame}: the capture/auth flow uses it, so it must be a
 * good neutral portrait. Only offered frames from the passive window or the post-challenge neutral hold are
 * considered; mid-action frames are never offered. A frame qualifies when it has exactly one face, no quality
 * issue at all, near-frontal pose and open eyes. Among qualifying frames the most frontal, largest face wins
 * (face size is the best sharpness/resolution proxy available without touching pixels).
 */
public final class BestFrameSelector {
    private final EngineTuning tuning;
    private FrameAnalysis best;
    private float bestScore = Float.NEGATIVE_INFINITY;

    public BestFrameSelector(EngineTuning tuning) {
        this.tuning = tuning;
    }

    public void reset() {
        best = null;
        bestScore = Float.NEGATIVE_INFINITY;
    }

    public boolean qualifies(FrameAnalysis a) {
        if (!a.usable() || a.faceBox == null) return false;
        if (Math.abs(a.yawDeg) > tuning.bestFrameMaxPoseDeg || Math.abs(a.pitchDeg) > tuning.bestFrameMaxPoseDeg)
            return false;
        float ear = FaceGeometry.meanEar(a.landmarks);
        return Float.isNaN(ear) || ear >= tuning.bestFrameMinEar; // no landmarks → cannot judge eyes; pose/quality decide
    }

    /** Offers a candidate; returns true if it became the new best. */
    public boolean offer(FrameAnalysis a) {
        if (!qualifies(a)) return false;
        float frontal = 1f - (Math.abs(a.yawDeg) + Math.abs(a.pitchDeg)) / (2f * tuning.bestFrameMaxPoseDeg);
        float faceArea = a.faceBox[2] * a.faceBox[3];
        float frameArea = (float) a.frame.width() * a.frame.height();
        float size = frameArea > 0 ? Math.min(1f, faceArea / frameArea * 4f) : 0f;
        float score = 0.6f * frontal + 0.4f * size;
        if (score > bestScore) {
            bestScore = score;
            best = a;
            return true;
        }
        return false;
    }

    public boolean hasBest() {
        return best != null;
    }

    public Frame bestFrame() {
        return best != null ? best.frame : null;
    }
}
