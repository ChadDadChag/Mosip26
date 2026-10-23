package io.mosip.registration.liveness.analyzer.mesh;

/**
 * FaceMesh V2 output for one face: 478 points (468 mesh + 10 iris) in frame pixels, the presence value and the ROI
 * the mesh was run on. Owned and reused by its {@link FaceMeshLandmarker}: valid until the next estimate() call.
 */
public final class MeshResult {

    public static final int POINTS = 478;

    /** [478][3]: X, Y in frame pixels (continuous, y down), Z = depth in the same pixel scale (smaller = closer). */
    public final float[][] points = new float[POINTS][3];
    /** sigmoid of the presence logit. */
    public float presence;
    /** ROI: centre, side and rotation (radians) of the square crop. */
    public double cx, cy, side, theta;

    MeshResult() { }
}
