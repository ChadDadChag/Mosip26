package io.mosip.registration.liveness.analyzer.mesh;

import io.mosip.registration.liveness.api.FaceLandmarks;

/**
 * FaceMesh V2 point indices for the contract's {@link FaceLandmarks}, from the SUBJECT's point of view. Eye order is
 * the contract's p1..p6: [outer corner, upper-outer, upper-inner, inner corner, lower-inner, lower-outer].
 */
public final class MeshIndices {

    public static final int[] LEFT_EYE = {263, 387, 385, 362, 380, 373};
    public static final int[] RIGHT_EYE = {33, 160, 158, 133, 153, 144};
    public static final int MOUTH_LEFT = 291, MOUTH_RIGHT = 61;
    public static final int MOUTH_UPPER_INNER = 13, MOUTH_LOWER_INNER = 14;
    public static final int NOSE_TIP = 1, CHIN = 152, FOREHEAD = 10;
    public static final int LEFT_IRIS = 473, RIGHT_IRIS = 468;
    /** Outer eye corners used for the head-pose x axis. */
    public static final int LEFT_EYE_OUTER = 263, RIGHT_EYE_OUTER = 33;

    private MeshIndices() { }

    /** Contract landmarks ({x, y} copies) from the [478][3] frame-space mesh points. */
    public static FaceLandmarks toFaceLandmarks(float[][] pts) {
        return new FaceLandmarks(eye(pts, LEFT_EYE), eye(pts, RIGHT_EYE),
                xy(pts, MOUTH_LEFT), xy(pts, MOUTH_RIGHT), xy(pts, MOUTH_UPPER_INNER), xy(pts, MOUTH_LOWER_INNER),
                xy(pts, NOSE_TIP), xy(pts, CHIN), xy(pts, LEFT_IRIS), xy(pts, RIGHT_IRIS));
    }

    private static float[][] eye(float[][] pts, int[] idx) {
        float[][] out = new float[idx.length][];
        for (int i = 0; i < idx.length; i++) out[i] = xy(pts, idx[i]);
        return out;
    }

    private static float[] xy(float[][] pts, int i) {
        return new float[] {pts[i][0], pts[i][1]};
    }
}
