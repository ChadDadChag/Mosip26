package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * Detects a face swap or photo switch between consecutive frames: the primary face box must overlap the previous
 * one, its centre must not jump, and its size must not change abruptly. Across a gap (face briefly lost, frames
 * dropped) only a looser overlap and the size check apply. Frames without exactly one face are skipped; the
 * reference is the last single-face box.
 */
public final class FaceContinuityGuard {
    private final EngineTuning t;
    private float[] lastBox;
    private long lastMs;

    public FaceContinuityGuard(EngineTuning tuning) {
        this.t = tuning;
    }

    public void reset() {
        lastBox = null;
    }

    /** @return false if the face changed discontinuously (the challenge must be invalidated). */
    public boolean check(FrameAnalysis a, long nowMs) {
        if (a.faceCount != 1 || a.faceBox == null || a.faceBox.length < 4) return true;
        float[] box = a.faceBox;
        boolean ok = true;
        if (lastBox != null) {
            float iou = FaceGeometry.iou(lastBox, box);
            float areaPrev = lastBox[2] * lastBox[3], area = box[2] * box[3];
            float ratio = areaPrev > 0 && area > 0 ? Math.max(area / areaPrev, areaPrev / area) : Float.MAX_VALUE;
            if (ratio > t.continuityMaxSizeRatio) ok = false;
            if (nowMs - lastMs > t.continuityGapMs) {
                if (!(iou >= t.continuityMinIouAfterGap)) ok = false;
            } else {
                float dx = (box[0] + box[2] / 2) - (lastBox[0] + lastBox[2] / 2);
                float dy = (box[1] + box[3] / 2) - (lastBox[1] + lastBox[3] / 2);
                float jump = (float) Math.sqrt(dx * dx + dy * dy) / Math.max(1f, lastBox[2]);
                if (!(iou >= t.continuityMinIou) || jump > t.continuityMaxCentreJump) ok = false;
            }
        }
        lastBox = box;
        lastMs = nowMs;
        return ok;
    }
}
