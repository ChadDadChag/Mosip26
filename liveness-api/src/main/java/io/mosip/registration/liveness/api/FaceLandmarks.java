package io.mosip.registration.liveness.api;

/**
 * Model-independent named landmarks (pixel coordinates, {x, y}). Owner: Person 2 fills it from whatever
 * landmark model is used; Person 3 computes blink/smile from it. Swapping the model never changes this class.
 * Eye points follow the classic EAR order p1..p6: [outer corner, upper-outer, upper-inner, inner corner,
 * lower-inner, lower-outer].
 */
public final class FaceLandmarks {
    public final float[][] leftEye;      // 6 points (subject's left)
    public final float[][] rightEye;     // 6 points
    public final float[] mouthLeft, mouthRight;          // outer mouth corners
    public final float[] mouthUpperInner, mouthLowerInner; // inner lip mid-points
    public final float[] noseTip, chin;
    public final float[] leftIris, rightIris;            // iris centres, for "look in direction"; may be null

    public FaceLandmarks(float[][] leftEye, float[][] rightEye, float[] mouthLeft, float[] mouthRight,
                         float[] mouthUpperInner, float[] mouthLowerInner, float[] noseTip, float[] chin,
                         float[] leftIris, float[] rightIris) {
        this.leftEye = leftEye; this.rightEye = rightEye; this.mouthLeft = mouthLeft; this.mouthRight = mouthRight;
        this.mouthUpperInner = mouthUpperInner; this.mouthLowerInner = mouthLowerInner;
        this.noseTip = noseTip; this.chin = chin; this.leftIris = leftIris; this.rightIris = rightIris;
    }
}
