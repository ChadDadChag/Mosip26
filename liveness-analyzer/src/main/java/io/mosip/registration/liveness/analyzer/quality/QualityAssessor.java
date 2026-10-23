package io.mosip.registration.liveness.analyzer.quality;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.image.Affine;
import io.mosip.registration.liveness.analyzer.image.ImageOps;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;

import java.util.EnumSet;

/**
 * Face quality ({@code reference_pipeline.quality}). The face box is cropped axis-aligned to 112 x 112
 * (affine [[w/112, 0, x], [0, h/112, y]]) and converted to BT.601 luma after bilinear sampling.
 * <pre>
 *   lumaMean = mean(gray)                lapVar = population variance of the 4-neighbour Laplacian
 *                                                 up + down + left + right - 4 centre over the 110 x 110 interior
 *   areaFrac = w h / (W H)               faceWidthPx = w
 *   centreDx = |x + w/2 - W/2| / W       centreDy = |y + h/2 - H/2| / H
 *   cutOff   = x &lt; 0 or y &lt; 0 or x + w &gt; W or y + h &gt; H
 * </pre>
 * Issues: TOO_DARK, TOO_BRIGHT, BLURRY, FACE_TOO_SMALL (area or width), FACE_TOO_LARGE, FACE_OFF_CENTER,
 * FACE_CUT_OFF, with the thresholds of {@link AnalyzerConfig}.
 *
 * <p>One instance per analyzer: buffers are reused; not thread-safe.
 */
public final class QualityAssessor {

    static final int SIZE = 112;

    private final AnalyzerConfig config;
    private final float[] gray = new float[SIZE * SIZE];
    private final double[] lap = new double[(SIZE - 2) * (SIZE - 2)];

    public QualityAssessor(AnalyzerConfig config) {
        if (config == null) throw new IllegalArgumentException("null config");
        this.config = config;
    }

    /** The axis-aligned crop of a float face box to size x size. */
    public static Affine boxAffine(FaceDetection face, int size) {
        return new Affine(face.w / size, 0, face.x, 0, face.h / size, face.y);
    }

    public QualityReport assess(byte[] rgb, int width, int height, FaceDetection face) {
        ImageOps.sample(rgb, width, height, boxAffine(face, SIZE), SIZE, SIZE, ImageOps.Layout.GRAY_0_255, gray);
        double sum = 0;
        for (float g : gray) sum += g;
        double lumaMean = sum / gray.length;
        double lapVar = laplacianVariance(gray, SIZE, lap);

        double x = face.x, y = face.y, w = face.w, h = face.h;
        double areaFrac = (w * h) / ((double) width * height);
        double centreDx = Math.abs(x + w / 2 - width / 2.0) / width;
        double centreDy = Math.abs(y + h / 2 - height / 2.0) / height;
        boolean cutOff = x < 0 || y < 0 || x + w > width || y + h > height;

        EnumSet<QualityIssue> issues = EnumSet.noneOf(QualityIssue.class);
        if (lumaMean < config.tooDarkLuma) issues.add(QualityIssue.TOO_DARK);
        if (lumaMean > config.tooBrightLuma) issues.add(QualityIssue.TOO_BRIGHT);
        if (lapVar < config.blurLapVar) issues.add(QualityIssue.BLURRY);
        if (areaFrac < config.minAreaFrac || w < config.minFaceWidthPx) issues.add(QualityIssue.FACE_TOO_SMALL);
        if (areaFrac > config.maxAreaFrac) issues.add(QualityIssue.FACE_TOO_LARGE);
        if (centreDx > config.maxCentreOffset || centreDy > config.maxCentreOffset) issues.add(QualityIssue.FACE_OFF_CENTER);
        if (cutOff) issues.add(QualityIssue.FACE_CUT_OFF);
        return new QualityReport(lumaMean, lapVar, areaFrac, w, centreDx, centreDy, cutOff, issues);
    }

    /** Two-pass population variance of the interior 4-neighbour Laplacian of an n x n image. */
    static double laplacianVariance(float[] g, int n, double[] scratch) {
        int k = 0;
        double sum = 0;
        for (int r = 1; r < n - 1; r++) {
            for (int c = 1; c < n - 1; c++) {
                double v = (double) g[(r - 1) * n + c] + g[(r + 1) * n + c] + g[r * n + c - 1] + g[r * n + c + 1]
                        - 4.0 * g[r * n + c];
                scratch[k++] = v;
                sum += v;
            }
        }
        double mean = sum / k, ss = 0;
        for (int i = 0; i < k; i++) {
            double d = scratch[i] - mean;
            ss += d * d;
        }
        return ss / k;
    }
}
