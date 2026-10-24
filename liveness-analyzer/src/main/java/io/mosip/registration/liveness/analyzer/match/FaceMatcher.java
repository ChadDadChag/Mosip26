package io.mosip.registration.liveness.analyzer.match;

import ai.onnxruntime.OrtException;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.util.ArrayList;
import java.util.List;

/**
 * Face binding (F1): are the face that passed liveness and the face that was captured the same person?
 *
 * <p>{@link #compare} runs YuNet on each image and requires exactly one face in each (counted as the analyzer counts
 * faces: a face smaller than {@code detectorSecondFaceMinAreaRatio} of the largest is ignored). Otherwise it returns
 * NO_FACE_A/B or MULTIPLE_FACES_A/B. It then compares the two SFace features ({@link SFaceEmbedder}) by cosine:
 * MATCH at or above {@link AnalyzerConfig#bindingCosineThreshold} (0.363, OpenCV's SFace threshold), else NO_MATCH.
 * Spec: {@code reference_pipeline.face_match}.
 *
 * <p>Created by {@code OnnxFaceAnalyzerFactory.createMatcher()} and sharing the factory's verified sessions;
 * {@link #close()} does not close them. Binding is not presentation attack detection: a photo of the same person
 * matches. Use it after liveness passed, between {@code LivenessResult.bestFrame} and the captured face. Thread-safe
 * (calls are serialised). Nothing is kept between calls.
 */
public final class FaceMatcher implements AutoCloseable {

    private final YuNetDetector detector;
    private final SFaceEmbedder embedder;
    private final double secondFaceMinAreaRatio;
    private final double threshold;
    private boolean closed;

    public FaceMatcher(OnnxModels models, AnalyzerConfig config) {
        if (models == null || config == null) throw new IllegalArgumentException("null argument");
        this.detector = new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), config.detector);
        this.embedder = new SFaceEmbedder(models.env(), models.session(ModelRole.MATCHER));
        this.secondFaceMinAreaRatio = config.detector.secondFaceMinAreaRatio;
        this.threshold = config.bindingCosineThreshold;
    }

    public MatchResult compare(Frame a, Frame b) throws LivenessEngineException {
        if (a == null || b == null) throw new LivenessEngineException(ErrorCode.INVALID_FRAME, "frame is null");
        return compare(a.rgb(), a.width(), a.height(), b.rgb(), b.width(), b.height());
    }

    /** Both images packed RGB888, row by row. */
    public synchronized MatchResult compare(byte[] rgbA, int wA, int hA, byte[] rgbB, int wB, int hB)
            throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        check(rgbA, wA, hA, "A");
        check(rgbB, wB, hB, "B");
        try {
            List<FaceDetection> facesA = detector.detect(rgbA, wA, hA);
            int nA = YuNetDetector.countFaces(facesA, secondFaceMinAreaRatio);
            if (nA != 1) return failed(nA == 0 ? MatchResult.Outcome.NO_FACE_A : MatchResult.Outcome.MULTIPLE_FACES_A);
            List<FaceDetection> facesB = detector.detect(rgbB, wB, hB);
            int nB = YuNetDetector.countFaces(facesB, secondFaceMinAreaRatio);
            if (nB != 1) return failed(nB == 0 ? MatchResult.Outcome.NO_FACE_B : MatchResult.Outcome.MULTIPLE_FACES_B);
            float[] featureA = embedder.embed(rgbA, wA, hA, YuNetDetector.primary(facesA));
            float[] featureB = embedder.embed(rgbB, wB, hB, YuNetDetector.primary(facesB));
            double sim = SFaceEmbedder.cosine(featureA, featureB);
            return new MatchResult(sim >= threshold ? MatchResult.Outcome.MATCH : MatchResult.Outcome.NO_MATCH, sim,
                    threshold);
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while matching faces", e);
        } catch (RuntimeException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "Unexpected failure matching faces", e);
        }
    }

    /**
     * For evaluation tools: the SFace feature of the image's face when it has exactly one (as {@link #compare} counts
     * faces), else null. A feature is a biometric template: keep it in memory only.
     */
    public synchronized float[] featureOfSingleFace(byte[] rgb, int w, int h) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        check(rgb, w, h, "A");
        try {
            List<FaceDetection> faces = detector.detect(rgb, w, h);
            if (YuNetDetector.countFaces(faces, secondFaceMinAreaRatio) != 1) return null;
            return embedder.embed(rgb, w, h, YuNetDetector.primary(faces));
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while embedding a face", e);
        }
    }

    /** For evaluation tools: the feature of every face YuNet finds, in YuNet's order (group photos). Memory only. */
    public synchronized List<float[]> featuresOfAllFaces(byte[] rgb, int w, int h) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        check(rgb, w, h, "A");
        try {
            List<float[]> out = new ArrayList<float[]>();
            for (FaceDetection f : detector.detect(rgb, w, h)) out.add(embedder.embed(rgb, w, h, f));
            return out;
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while embedding faces", e);
        }
    }

    private MatchResult failed(MatchResult.Outcome outcome) {
        return new MatchResult(outcome, Double.NaN, threshold);
    }

    private static void check(byte[] rgb, int w, int h, String which) throws LivenessEngineException {
        if (rgb == null || w <= 0 || h <= 0 || rgb.length < (long) w * h * 3) {
            throw new LivenessEngineException(ErrorCode.INVALID_FRAME, "image " + which + " is empty or too short");
        }
    }

    /** The threshold in use. */
    public double threshold() {
        return threshold;
    }

    /** Marks the matcher closed; the factory's shared sessions stay open. */
    @Override
    public synchronized void close() {
        closed = true;
    }
}
