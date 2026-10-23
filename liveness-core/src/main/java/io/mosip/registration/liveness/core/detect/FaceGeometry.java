package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.FrameAnalysis;

/**
 * Landmark and pose helpers. Every value here is scale-free (ratios) so it does not depend on distance to the
 * camera. Coordinates are image pixels with y pointing DOWN. Methods return NaN when the input is missing.
 *
 * <p><b>Direction conventions live here and only here.</b>
 * <ul>
 *   <li>{@link #userYaw}: positive = the subject turned to THEIR left. This is the contract's definition of
 *       {@code FrameAnalysis.yawDeg}, so no mirroring correction is applied (the analyzer must report pose in the
 *       subject's frame even for mirrored front-camera images).</li>
 *   <li>{@link #userPitch}: positive = the subject looks UP. The contract does not define the pitch sign
 *       (proposal P2); if Part 2 reports the opposite, flip {@link #PITCH_SIGN}.</li>
 * </ul>
 */
public final class FaceGeometry {

    /** +1 if FrameAnalysis.pitchDeg > 0 means "looking up" (assumed, proposal P2), -1 otherwise. */
    public static final float PITCH_SIGN = 1f;

    private FaceGeometry() { }

    /** True when the analyzer reported head pose (it may report NaN when it cannot estimate it). */
    public static boolean hasPose(FrameAnalysis a) {
        return !Float.isNaN(a.yawDeg) && !Float.isNaN(a.pitchDeg);
    }

    /**
     * True unless a REPORTED yaw or pitch exceeds {@code maxDeg}. Unknown pose (NaN) is not held against the frame:
     * an analyzer without pose estimation must still allow passive decisions (pose challenges are then not offered).
     */
    public static boolean withinPose(FrameAnalysis a, float maxDeg) {
        return (Float.isNaN(a.yawDeg) || Math.abs(a.yawDeg) <= maxDeg)
                && (Float.isNaN(a.pitchDeg) || Math.abs(a.pitchDeg) <= maxDeg);
    }

    /** True unless a REPORTED roll (head tilted sideways) exceeds {@code maxDeg}; unknown roll (NaN) is tolerated. */
    public static boolean withinRoll(FrameAnalysis a, float maxDeg) {
        return Float.isNaN(a.rollDeg) || Math.abs(a.rollDeg) <= maxDeg;
    }

    /** True when the landmarks give usable eye and mouth ratios (needed for BLINK and SMILE). */
    public static boolean hasExpressionLandmarks(FrameAnalysis a) {
        return !Float.isNaN(meanEar(a.landmarks)) && !Float.isNaN(mouthWidthRatio(a.landmarks));
    }

    public static float userYaw(FrameAnalysis a) {
        return a.yawDeg;
    }

    public static float userPitch(FrameAnalysis a) {
        return PITCH_SIGN * a.pitchDeg;
    }

    /** Largest |yaw| or |pitch| change from the baseline (user convention); NaN when neither is known. */
    public static float poseDrift(FrameAnalysis a, Baseline b) {
        float dy = Math.abs(userYaw(a) - b.yaw), dp = Math.abs(userPitch(a) - b.pitch);
        if (Float.isNaN(dy)) return dp;
        if (Float.isNaN(dp)) return dy;
        return Math.max(dy, dp);
    }

    public static float dist(float[] a, float[] b) {
        if (a == null || b == null || a.length < 2 || b.length < 2) return Float.NaN;
        float dx = a[0] - b[0], dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Eye aspect ratio (Soukupová & Čech 2016) for the contract's p1..p6 order
     * [outer corner, upper-outer, upper-inner, inner corner, lower-inner, lower-outer]:
     * (|p2-p6| + |p3-p5|) / (2 |p1-p4|). About 0.25-0.35 open, below ~0.15 closed.
     */
    public static float eyeAspectRatio(float[][] eye) {
        if (eye == null || eye.length < 6) return Float.NaN;
        float width = dist(eye[0], eye[3]);
        if (!(width > 0)) return Float.NaN;
        return (dist(eye[1], eye[5]) + dist(eye[2], eye[4])) / (2f * width);
    }

    /** Mean EAR of both eyes; if one eye is missing, the other one. */
    public static float meanEar(FaceLandmarks l) {
        if (l == null) return Float.NaN;
        float left = eyeAspectRatio(l.leftEye), right = eyeAspectRatio(l.rightEye);
        if (Float.isNaN(left)) return right;
        if (Float.isNaN(right)) return left;
        return (left + right) / 2f;
    }

    public static float[] eyeCentre(float[][] eye) {
        if (eye == null || eye.length < 6) return null;
        float x = 0, y = 0;
        for (int i = 0; i < 6; i++) {
            if (eye[i] == null || eye[i].length < 2) return null;
            x += eye[i][0];
            y += eye[i][1];
        }
        return new float[] {x / 6f, y / 6f};
    }

    /** Distance between eye centres in pixels: the normaliser for all mouth ratios, and the face resolution. */
    public static float interOcular(FaceLandmarks l) {
        if (l == null) return Float.NaN;
        return dist(eyeCentre(l.leftEye), eyeCentre(l.rightEye));
    }

    /** Mouth width / inter-ocular distance. Rises noticeably when smiling. */
    public static float mouthWidthRatio(FaceLandmarks l) {
        float io = interOcular(l);
        if (!(io > 0)) return Float.NaN;
        return dist(l.mouthLeft, l.mouthRight) / io;
    }

    /** Inner-lip gap / inter-ocular distance (mouth opening). */
    public static float mouthOpenRatio(FaceLandmarks l) {
        float io = interOcular(l);
        if (!(io > 0)) return Float.NaN;
        return dist(l.mouthUpperInner, l.mouthLowerInner) / io;
    }

    /**
     * How far the mouth corners sit ABOVE the inner-lip centre, / inter-ocular distance. Positive when the
     * corners lift (smile). Image y points down, so "above" means a smaller y.
     */
    public static float cornerLift(FaceLandmarks l) {
        float io = interOcular(l);
        if (!(io > 0) || l.mouthLeft == null || l.mouthRight == null || l.mouthUpperInner == null
                || l.mouthLowerInner == null) return Float.NaN;
        float midY = (l.mouthUpperInner[1] + l.mouthLowerInner[1]) / 2f;
        float lift = ((midY - l.mouthLeft[1]) + (midY - l.mouthRight[1])) / 2f;
        return lift / io;
    }

    /** Intersection over union of two [x, y, w, h] boxes; NaN if either is missing. */
    public static float iou(float[] a, float[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return Float.NaN;
        float x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
        float x2 = Math.min(a[0] + a[2], b[0] + b[2]), y2 = Math.min(a[1] + a[3], b[1] + b[3]);
        float inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float union = a[2] * a[3] + b[2] * b[3] - inter;
        return union > 0 ? inter / union : Float.NaN;
    }
}
