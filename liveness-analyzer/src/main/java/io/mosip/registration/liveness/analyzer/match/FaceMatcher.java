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
 * MATCH at or above {@link AnalyzerConfig#bindingCosineThreshold} (0.45; OpenCV's SFace default is 0.363), else
 * NO_MATCH.
 * Spec: {@code reference_pipeline.face_match}.
 *
 * <p>Created by {@code OnnxFaceAnalyzerFactory.createMatcher()} and sharing the factory's verified sessions;
 * {@link #close()} does not close them. Creating a matcher is cheap: its first call builds the MATCHER session unless
 * the factory warmed it up ({@code warmUpMatcher}). Binding is not presentation attack detection: a photo of the same
 * person matches. Use it after liveness passed, between {@code LivenessResult.bestFrame} and the captured face.
 * Thread-safe (calls are serialised). Nothing is kept between calls.
 *
 * <p>Identity binding (F15): {@link #identify} compares the same best frame with the user's onboarded face templates,
 * with the same detection and alignment, and its own threshold ({@link AnalyzerConfig#identityCosineThreshold}).
 */
public final class FaceMatcher implements AutoCloseable {

    private final OnnxModels models;
    private final YuNetDetector detector;
    private final double secondFaceMinAreaRatio;
    private final double threshold;
    private final double identityThreshold;
    private SFaceEmbedder embedder; // built on first use: the MATCHER session is lazy (OnnxModels.ensureSession)
    private boolean closed;

    public FaceMatcher(OnnxModels models, AnalyzerConfig config) {
        if (models == null || config == null) throw new IllegalArgumentException("null argument");
        this.models = models;
        this.detector = new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), config.detector);
        this.secondFaceMinAreaRatio = config.detector.secondFaceMinAreaRatio;
        this.threshold = config.bindingCosineThreshold;
        this.identityThreshold = config.identityCosineThreshold;
    }

    /** The embedder; the first call builds the MATCHER session unless the factory warmed it up. */
    private SFaceEmbedder embedder() throws LivenessEngineException {
        if (embedder == null) embedder = new SFaceEmbedder(models.env(), models.ensureSession(ModelRole.MATCHER));
        return embedder;
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
        SFaceEmbedder embedder = embedder();
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
     * Identity binding (F15): is the live face (the PASSED session's best frame) the user whose onboarded face images
     * are {@code templates}? Every template is detected, aligned and embedded as {@link #compare} does. A null entry (an
     * undecodable template) and an image without exactly one face are unreadable. The best similarity over the readable
     * templates decides: MATCH at or above {@link AnalyzerConfig#identityCosineThreshold}, else NO_MATCH. No template
     * gives NOT_BOUND_NO_TEMPLATE, only unreadable ones NOT_BOUND_UNREADABLE; the host decides what those mean. Nothing
     * is kept: the features exist only during this call.
     */
    public synchronized IdentityResult identify(Frame live, List<Frame> templates) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        if (live == null) throw new LivenessEngineException(ErrorCode.INVALID_FRAME, "frame is null");
        int given = templates == null ? 0 : templates.size();
        if (given == 0) {
            return new IdentityResult(IdentityResult.Outcome.NOT_BOUND_NO_TEMPLATE, Double.NaN, identityThreshold, 0, 0);
        }
        check(live.rgb(), live.width(), live.height(), "A");
        SFaceEmbedder embedder = embedder();
        try {
            List<FaceDetection> faces = detector.detect(live.rgb(), live.width(), live.height());
            if (YuNetDetector.countFaces(faces, secondFaceMinAreaRatio) != 1) {
                return new IdentityResult(IdentityResult.Outcome.NO_LIVE_FACE, Double.NaN, identityThreshold, given, 0);
            }
            float[] liveFeature = embedder.embed(live.rgb(), live.width(), live.height(), YuNetDetector.primary(faces));
            double best = Double.NaN;
            int unreadable = 0;
            for (Frame t : templates) {
                if (t == null || t.rgb() == null || t.width() <= 0 || t.height() <= 0
                        || t.rgb().length < (long) t.width() * t.height() * 3) {
                    unreadable++;
                    continue;
                }
                List<FaceDetection> tf = detector.detect(t.rgb(), t.width(), t.height());
                if (YuNetDetector.countFaces(tf, secondFaceMinAreaRatio) != 1) {
                    unreadable++;
                    continue;
                }
                double sim = SFaceEmbedder.cosine(liveFeature,
                        embedder.embed(t.rgb(), t.width(), t.height(), YuNetDetector.primary(tf)));
                if (!Double.isNaN(sim) && (Double.isNaN(best) || sim > best)) best = sim;
            }
            return new IdentityResult(identityOutcome(given, unreadable, best, identityThreshold), best,
                    identityThreshold, given, unreadable);
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while identifying a face", e);
        } catch (RuntimeException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "Unexpected failure identifying a face", e);
        }
    }

    /**
     * The F15 outcome over {@code given} templates of which {@code unreadable} were unreadable, with {@code best} the
     * highest finite similarity (NaN if none). Unreadable only when no template was readable: a readable template
     * without a finite similarity (a degenerate feature) is NO_MATCH, never "not bound", which the host may accept.
     */
    static IdentityResult.Outcome identityOutcome(int given, int unreadable, double best, double threshold) {
        if (given == 0) return IdentityResult.Outcome.NOT_BOUND_NO_TEMPLATE;
        if (unreadable >= given) return IdentityResult.Outcome.NOT_BOUND_UNREADABLE;
        return !Double.isNaN(best) && best >= threshold ? IdentityResult.Outcome.MATCH : IdentityResult.Outcome.NO_MATCH;
    }

    /**
     * For evaluation tools: the SFace feature of the image's face when it has exactly one (as {@link #compare} counts
     * faces), else null. A feature is a biometric template: keep it in memory only.
     */
    public synchronized float[] featureOfSingleFace(byte[] rgb, int w, int h) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        check(rgb, w, h, "A");
        SFaceEmbedder embedder = embedder();
        try {
            List<FaceDetection> faces = detector.detect(rgb, w, h);
            if (YuNetDetector.countFaces(faces, secondFaceMinAreaRatio) != 1) return null;
            return embedder.embed(rgb, w, h, YuNetDetector.primary(faces));
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while embedding a face", e);
        }
    }

    /** The largest face's feature and the image's face count, for {@link #featureOfPrimaryFace}. */
    public static final class PrimaryFeature {
        /** Faces as {@link #compare} counts them (a face under the second-face area ratio is ignored). */
        public final int faceCount;
        /** The feature of the largest face (YuNet's primary). */
        public final float[] feature;

        PrimaryFeature(int faceCount, float[] feature) {
            this.faceCount = faceCount;
            this.feature = feature;
        }
    }

    /**
     * For evaluation tools: the feature of the largest face whatever the face count, and the count, so a group photo
     * can be used and flagged. Null when the image has no face. A feature is a biometric template: memory only.
     */
    public synchronized PrimaryFeature featureOfPrimaryFace(byte[] rgb, int w, int h) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        check(rgb, w, h, "A");
        SFaceEmbedder embedder = embedder();
        try {
            List<FaceDetection> faces = detector.detect(rgb, w, h);
            if (faces.isEmpty()) return null;
            return new PrimaryFeature(YuNetDetector.countFaces(faces, secondFaceMinAreaRatio),
                    embedder.embed(rgb, w, h, YuNetDetector.primary(faces)));
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while embedding a face", e);
        }
    }

    /** For evaluation tools: the feature of every face YuNet finds, in YuNet's order (group photos). Memory only. */
    public synchronized List<float[]> featuresOfAllFaces(byte[] rgb, int w, int h) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "face matcher is closed");
        check(rgb, w, h, "A");
        SFaceEmbedder embedder = embedder();
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

    /** The identity binding (F15) threshold in use. */
    public double identityThreshold() {
        return identityThreshold;
    }

    /** Marks the matcher closed; the factory's shared sessions stay open. */
    @Override
    public synchronized void close() {
        closed = true;
    }
}
