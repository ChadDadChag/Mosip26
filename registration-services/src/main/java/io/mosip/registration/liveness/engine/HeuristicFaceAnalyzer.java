package io.mosip.registration.liveness.engine;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reference {@link FaceAnalyzer} implementation built entirely from classical,
 * explainable image-processing signals (see {@link ImageQualityMetrics} and
 * {@link FaceRegionDetector}) - <b>not</b> a trained machine-learning model.
 *
 * <p>The {@code FaceAnalyzer} contract's own Javadoc calls for "Pure Java + ONNX
 * Runtime" performing face detection, landmarks, head pose, quality and passive
 * liveness/PAD in one pass. Producing and evaluating an actual trained PAD model is
 * a model-sourcing/data-science task outside what can be done inside a coding
 * session with no network access to a model registry and no labelled bona-fide/
 * attack dataset. This class exists instead to make the full {@code FaceAnalyzer}
 * -&gt; {@code FrameAnalysis} contract genuinely runnable and testable end-to-end
 * today, with a single, clearly-bounded seam (this one class) to swap in a real
 * ONNX-backed analyzer later without touching anything else that depends on the
 * contract.</p>
 *
 * <p><b>What is and isn't real here:</b></p>
 * <ul>
 *   <li>Face location: a genuine (if coarse) classical skin-colour + projection-
 *       profile detector - see {@link FaceRegionDetector}. Real, but not
 *       ML-accurate; see its Javadoc for known failure modes.</li>
 *   <li>Landmarks: <b>not real</b> - fixed proportions of the detected box, filled
 *       in only so the {@link FaceLandmarks} shape is always populated. See
 *       {@link ProportionalLandmarkEstimator}.</li>
 *   <li>Head pose (yaw/pitch/roll): not computed at all; always {@link Float#NaN}.</li>
 *   <li>Liveness/PAD score: a genuine combination of two real, cited signals
 *       (Laplacian-variance sharpness and RGB channel-diversity - see
 *       {@link ImageQualityMetrics}), squashed into [0,1]. This gives some real
 *       separation between, for example, a sharp/textured live face and a flat,
 *       uniformly-lit printed photo, but it is <b>not validated against any
 *       curated bona-fide/attack presentation dataset</b> and must not be presented
 *       as ISO/IEC 30107-3 compliant. See {@code PadSelfEvaluationTest} for a
 *       synthetic, non-certifying self-check of this scoring behaviour.</li>
 * </ul>
 */
public final class HeuristicFaceAnalyzer implements FaceAnalyzer {

    static final String DEFAULT_PROPERTIES_RESOURCE = "liveness/heuristic-analyzer.properties";
    static final String DEFAULT_MANIFEST_RESOURCE = "liveness/heuristic-analyzer.manifest";

    // Empirically-chosen thresholds, NOT derived from a validated dataset. A real
    // deployment should calibrate these against representative capture hardware
    // and lighting, or replace this class entirely with a trained model.
    private static final double DARK_LUMA_THRESHOLD = 40.0;
    private static final double BRIGHT_LUMA_THRESHOLD = 220.0;
    private static final double BLUR_VARIANCE_THRESHOLD = 15.0;
    private static final double MIN_FACE_AREA_FRACTION = 0.05;
    private static final double MAX_FACE_AREA_FRACTION = 0.65;
    private static final double OFF_CENTER_FRACTION = 0.20;
    private static final double EDGE_MARGIN_FRACTION = 0.02;
    private static final double SHARPNESS_REFERENCE = 80.0;
    private static final double DIVERSITY_REFERENCE = 45.0;

    private final ModelManifest manifest;

    public HeuristicFaceAnalyzer() throws LivenessEngineException {
        this(DEFAULT_PROPERTIES_RESOURCE, DEFAULT_MANIFEST_RESOURCE);
    }

    /** Package-visible constructor so tests can point at alternate/tampered resources. */
    HeuristicFaceAnalyzer(String propertiesResource, String manifestResource) throws LivenessEngineException {
        this.manifest = ModelManifest.loadAndVerify(propertiesResource, manifestResource);
    }

    @Override
    public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
        if (frame == null) {
            throw new LivenessEngineException(ErrorCode.INVALID_FRAME, "frame must not be null");
        }

        long start = System.nanoTime();
        try {
            byte[] rgb = frame.rgb();
            int width = frame.width();
            int height = frame.height();

            FaceRegionDetector.Detection detection = FaceRegionDetector.detect(rgb, width, height);

            if (!detection.found) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                return new FrameAnalysis(frame, 0, null, null,
                        Float.NaN, Float.NaN, Float.NaN,
                        EnumSet.noneOf(QualityIssue.class),
                        Float.NaN, emptyPadDetails(), elapsedMs);
            }

            int fx = detection.x, fy = detection.y, fw = detection.w, fh = detection.h;
            float[] faceBox = {fx, fy, fw, fh};
            FaceLandmarks landmarks = ProportionalLandmarkEstimator.estimate(fx, fy, fw, fh);

            double meanLuma = ImageQualityMetrics.meanLuma(rgb, width, height, fx, fy, fw, fh);
            double sharpness = ImageQualityMetrics.laplacianVariance(rgb, width, height, fx, fy, fw, fh);
            double diversity = ImageQualityMetrics.colorDiversity(rgb, width, height, fx, fy, fw, fh);
            double areaFraction = (double) (fw * fh) / (double) (width * height);

            Set<QualityIssue> issues = EnumSet.noneOf(QualityIssue.class);
            if (meanLuma < DARK_LUMA_THRESHOLD) {
                issues.add(QualityIssue.TOO_DARK);
            }
            if (meanLuma > BRIGHT_LUMA_THRESHOLD) {
                issues.add(QualityIssue.TOO_BRIGHT);
            }
            if (sharpness < BLUR_VARIANCE_THRESHOLD) {
                issues.add(QualityIssue.BLURRY);
            }
            if (areaFraction < MIN_FACE_AREA_FRACTION) {
                issues.add(QualityIssue.FACE_TOO_SMALL);
            }
            if (areaFraction > MAX_FACE_AREA_FRACTION) {
                issues.add(QualityIssue.FACE_TOO_LARGE);
            }
            if (isOffCenter(fx, fy, fw, fh, width, height)) {
                issues.add(QualityIssue.FACE_OFF_CENTER);
            }
            if (isCutOff(fx, fy, fw, fh, width, height)) {
                issues.add(QualityIssue.FACE_CUT_OFF);
            }

            double sharpnessScore = clamp01(sharpness / SHARPNESS_REFERENCE);
            double diversityScore = clamp01(diversity / DIVERSITY_REFERENCE);
            double livenessScore = clamp01(0.5 * sharpnessScore + 0.5 * diversityScore);

            Map<String, Float> padDetails = new LinkedHashMap<>();
            padDetails.put("laplacian_variance", (float) sharpness);
            padDetails.put("color_diversity", (float) diversity);
            padDetails.put("mean_luma", (float) meanLuma);
            padDetails.put("face_area_fraction", (float) areaFraction);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return new FrameAnalysis(frame, 1, faceBox, landmarks,
                    Float.NaN, Float.NaN, Float.NaN,
                    issues, (float) livenessScore, padDetails, elapsedMs);
        } catch (RuntimeException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR,
                    "Unexpected failure analyzing frame", e);
        }
    }

    @Override
    public String modelVersion() {
        return manifest.version();
    }

    @Override
    public void close() {
        // No native resources held by this heuristic implementation.
    }

    private static boolean isOffCenter(int fx, int fy, int fw, int fh, int width, int height) {
        double faceCenterX = fx + fw / 2.0;
        double faceCenterY = fy + fh / 2.0;
        double frameCenterX = width / 2.0;
        double frameCenterY = height / 2.0;
        double dx = Math.abs(faceCenterX - frameCenterX) / width;
        double dy = Math.abs(faceCenterY - frameCenterY) / height;
        return dx > OFF_CENTER_FRACTION || dy > OFF_CENTER_FRACTION;
    }

    private static boolean isCutOff(int fx, int fy, int fw, int fh, int width, int height) {
        double marginX = width * EDGE_MARGIN_FRACTION;
        double marginY = height * EDGE_MARGIN_FRACTION;
        return fx <= marginX || fy <= marginY
                || (fx + fw) >= (width - marginX)
                || (fy + fh) >= (height - marginY);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Map<String, Float> emptyPadDetails() {
        return new LinkedHashMap<>();
    }
}
