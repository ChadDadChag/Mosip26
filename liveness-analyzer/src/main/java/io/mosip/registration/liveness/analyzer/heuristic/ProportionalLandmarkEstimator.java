package io.mosip.registration.liveness.analyzer.heuristic;

import io.mosip.registration.liveness.api.FaceLandmarks;

/**
 * <b>Placeholder only - not a real landmark detector.</b>
 *
 * <p>Produces {@link FaceLandmarks} by placing points at fixed, textbook-typical
 * proportions of a given face bounding box (e.g. eyes at roughly one third down,
 * mouth at roughly three quarters down). It does not look at pixel content at all
 * beyond the box coordinates.</p>
 *
 * <p>This exists purely so the {@code FaceAnalyzer} contract's {@code landmarks}
 * field is always populated with correctly-shaped, plausibly-positioned data,
 * letting Part 3's blink/smile/gaze code compile and run end-to-end today. The
 * points are <b>not derived from the actual face in the frame</b> - two different
 * faces of the same box size and position produce identical landmarks. Anything
 * that depends on landmark accuracy (eye-aspect-ratio blink detection, precise
 * mouth-corner smile detection, iris-based gaze) will not behave correctly against
 * a real subject until this is replaced by a genuine landmark model sharing the
 * same {@link FaceLandmarks} shape.</p>
 */
final class ProportionalLandmarkEstimator {

    private ProportionalLandmarkEstimator() {
    }

    static FaceLandmarks estimate(int faceX, int faceY, int faceW, int faceH) {
        float x = faceX;
        float y = faceY;
        float w = faceW;
        float h = faceH;

        float[][] leftEye = eyePoints(x + 0.62f * w, y + 0.38f * h, w);
        float[][] rightEye = eyePoints(x + 0.38f * w, y + 0.38f * h, w);

        float[] mouthLeft = {x + 0.68f * w, y + 0.78f * h};
        float[] mouthRight = {x + 0.32f * w, y + 0.78f * h};
        float[] mouthUpperInner = {x + 0.5f * w, y + 0.75f * h};
        float[] mouthLowerInner = {x + 0.5f * w, y + 0.81f * h};

        float[] noseTip = {x + 0.5f * w, y + 0.58f * h};
        float[] chin = {x + 0.5f * w, y + 0.95f * h};

        float[] leftIris = {x + 0.62f * w, y + 0.38f * h};
        float[] rightIris = {x + 0.38f * w, y + 0.38f * h};

        return new FaceLandmarks(leftEye, rightEye, mouthLeft, mouthRight,
                mouthUpperInner, mouthLowerInner, noseTip, chin, leftIris, rightIris);
    }

    /**
     * Six points around a nominal eye centre, in the EAR (eye-aspect-ratio) point
     * order documented on {@link FaceLandmarks}: outer corner, upper-outer,
     * upper-inner, inner corner, lower-inner, lower-outer. Since this estimator has
     * no real eye-opening information, the eye is placed as a fixed, slightly-open
     * ellipse - EAR computed from these points will be roughly constant across
     * frames regardless of whether the subject actually blinked.
     */
    private static float[][] eyePoints(float centerX, float centerY, float faceWidth) {
        float eyeWidth = 0.18f * faceWidth;
        float eyeHeight = 0.06f * faceWidth;
        float halfW = eyeWidth / 2f;

        return new float[][]{
                {centerX - halfW, centerY},                         // outer corner
                {centerX - halfW / 2f, centerY - eyeHeight / 2f},    // upper-outer
                {centerX + halfW / 2f, centerY - eyeHeight / 2f},    // upper-inner
                {centerX + halfW, centerY},                          // inner corner
                {centerX + halfW / 2f, centerY + eyeHeight / 2f},    // lower-inner
                {centerX - halfW / 2f, centerY + eyeHeight / 2f}     // lower-outer
        };
    }
}
