package io.mosip.registration.liveness.analyzer.detect;

/**
 * One detected face in frame pixels (continuous coordinates, y down). Coordinates and score are kept in double
 * so that NMS order and the integer crop boxes derived from them match the Python reference exactly.
 * Keypoints: 0 = subject's right eye, 1 = subject's left eye, 2 = nose tip, 3 = subject's right mouth corner,
 * 4 = subject's left mouth corner.
 */
public final class FaceDetection {

    public final double x, y, w, h;
    public final double score;
    private final double[] keypoints; // x0, y0, x1, y1, ... (10 values)

    public FaceDetection(double x, double y, double w, double h, double score, double[] keypoints) {
        if (keypoints == null || keypoints.length != 10) throw new IllegalArgumentException("need 5 keypoints");
        this.x = x; this.y = y; this.w = w; this.h = h;
        this.score = score;
        this.keypoints = keypoints.clone();
    }

    public double area() {
        return w * h;
    }

    public double keypointX(int n) {
        return keypoints[2 * n];
    }

    public double keypointY(int n) {
        return keypoints[2 * n + 1];
    }

    /** The contract's face box, [x, y, w, h]. */
    public float[] toBox() {
        return new float[] {(float) x, (float) y, (float) w, (float) h};
    }

    /** IoU of two [x, y, w, h] boxes; 0 when the union is empty (same formula as FaceGeometry.iou). */
    public static double iou(FaceDetection p, FaceDetection q) {
        double x1 = Math.max(p.x, q.x), y1 = Math.max(p.y, q.y);
        double x2 = Math.min(p.x + p.w, q.x + q.w), y2 = Math.min(p.y + p.h, q.y + q.h);
        double inter = Math.max(0.0, x2 - x1) * Math.max(0.0, y2 - y1);
        double union = p.w * p.h + q.w * q.h - inter;
        return union > 0 ? inter / union : 0.0;
    }
}
