package io.mosip.registration.liveness.analyzer.detect;

/**
 * YuNet detector settings. Defaults match {@code reference_pipeline.py}. Thresholds are doubles so that
 * comparisons behave exactly like the reference (0.7f is not 0.7).
 */
public final class DetectorConfig {

    /** Frames wider than this are downscaled to this width before detection. */
    public final int maxWidth;
    /** Minimum sqrt(cls * obj) for a candidate to count. */
    public final double scoreThreshold;
    /** A candidate is suppressed when its IoU with a kept, higher-scoring face is above this. */
    public final double nmsIou;
    /** A face counts towards faceCount only if its area is at least this fraction of the largest face's. */
    public final double secondFaceMinAreaRatio;

    public DetectorConfig(int maxWidth, double scoreThreshold, double nmsIou, double secondFaceMinAreaRatio) {
        if (maxWidth < 32) throw new IllegalArgumentException("maxWidth must be at least 32");
        this.maxWidth = maxWidth;
        this.scoreThreshold = scoreThreshold;
        this.nmsIou = nmsIou;
        this.secondFaceMinAreaRatio = secondFaceMinAreaRatio;
    }

    public static DetectorConfig defaults() {
        return new DetectorConfig(320, 0.7, 0.3, 0.2);
    }
}
