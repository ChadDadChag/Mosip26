package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.FaceGeometry;

/**
 * Picks the frame handed back in {@code LivenessResult.bestFrame}: the capture/auth flow uses it, so it must be a
 * good neutral portrait. Only offered frames from the passive window or the post-challenge neutral hold are
 * considered; mid-action frames are never offered. A frame qualifies when it has exactly one face, no quality
 * issue at all, near-frontal pose and open eyes. Qualifying frames are ranked first by how they meet ISO/IEC 19794-5
 * ({@link Iso19794Check#tier()}: compliant, then not known to break a limit, then the rest), and within a tier by a
 * score combining sharpness (Laplacian variance of the face region, {@link Sharpness}), frontality and face size, so
 * the sharpest, most front-facing compliant frame wins.
 */
public final class BestFrameSelector {
    private final EngineTuning tuning;
    private FrameAnalysis best;
    private Iso19794Check bestCheck;
    private int bestTier = -1;
    private float bestScore = Float.NEGATIVE_INFINITY;

    public BestFrameSelector(EngineTuning tuning) {
        this.tuning = tuning;
    }

    public void reset() {
        best = null;
        bestCheck = null;
        bestTier = -1;
        bestScore = Float.NEGATIVE_INFINITY;
    }

    public boolean qualifies(FrameAnalysis a) {
        if (!a.usable() || a.faceBox == null) return false;
        if (!FaceGeometry.withinPose(a, tuning.bestFrameMaxPoseDeg)) return false;
        float ear = FaceGeometry.meanEar(a.landmarks);
        return Float.isNaN(ear) || ear >= tuning.bestFrameMinEar; // no landmarks → cannot judge eyes; pose/quality decide
    }

    /** Offers a candidate; returns true if it became the new best. */
    public boolean offer(FrameAnalysis a) {
        if (!qualifies(a)) return false;
        float frontal = FaceGeometry.hasPose(a)
                ? 1f - (Math.abs(a.yawDeg) + Math.abs(a.pitchDeg)) / (2f * tuning.bestFrameMaxPoseDeg)
                : 0.5f; // pose unknown: neutral, sharpness and size decide
        float faceArea = a.faceBox[2] * a.faceBox[3];
        float frameArea = (float) a.frame.width() * a.frame.height();
        float size = frameArea > 0 ? Math.min(1f, faceArea / frameArea * 4f) : 0f;
        float lap = Sharpness.laplacianVariance(a.frame, a.faceBox);
        float sharp = lap / (lap + tuning.bestFrameSharpnessHalfPoint); // 0..1, 0.5 at the half point
        float score = 0.45f * sharp + 0.35f * frontal + 0.2f * size;
        Iso19794Check check = Iso19794Check.of(a, tuning, true); // offered frames are neutral by construction
        int tier = check.tier();
        if (tier > bestTier || tier == bestTier && score > bestScore) {
            bestTier = tier;
            bestScore = score;
            best = a;
            bestCheck = check;
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

    /** How the current best frame measures against ISO/IEC 19794-5; null when there is none. */
    public Iso19794Check bestCheck() {
        return bestCheck;
    }
}
