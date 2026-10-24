package io.mosip.registration.liveness.analyzer;

import ai.onnxruntime.OrtException;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.mesh.FaceMeshLandmarker;
import io.mosip.registration.liveness.analyzer.mesh.HeadPoseEstimator;
import io.mosip.registration.liveness.analyzer.mesh.MeshIndices;
import io.mosip.registration.liveness.analyzer.mesh.MeshResult;
import io.mosip.registration.liveness.analyzer.pad.MoireCap;
import io.mosip.registration.liveness.analyzer.pad.PadResult;
import io.mosip.registration.liveness.analyzer.pad.ReplayCues;
import io.mosip.registration.liveness.analyzer.pad.SilentFacePad;
import io.mosip.registration.liveness.analyzer.quality.QualityAssessor;
import io.mosip.registration.liveness.analyzer.quality.QualityReport;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Part 2's {@link FaceAnalyzer}: YuNet face detection, FaceMesh V2 landmarks and head pose, quality checks and
 * MiniFASNet passive PAD, all on ONNX Runtime, mirroring {@code models/tools/reference_pipeline.py}.
 *
 * <p>Conventions (contract): pose is always in the SUBJECT's frame and frames are never mirrored.
 * <ul>
 *   <li>+yaw = the subject turned to THEIR left;</li>
 *   <li>+pitch = the subject looks UP (this confirms Part 3's proposal P2, {@code FaceGeometry.PITCH_SIGN = +1});
 *       the mesh's neutral-face bias ({@link AnalyzerConfig#pitchBiasDeg}) is removed;</li>
 *   <li>+roll = the subject's left eye is higher.</li>
 * </ul>
 *
 * <p>Per frame: detect; no face: empty analysis; several faces: primary box only (no landmarks, NaN pose and
 * score); one face: quality issues, replay cues and mesh. Without a mesh the pose is unknown, so the score is NaN.
 * Outside the PAD pose range (|yaw| &gt; padMaxYawDeg or |pitch| &gt; padMaxPitchDeg) the score is NaN; otherwise
 * score = (p_v2[1] + p_v1se[1]) / 2, then the session moire cap ({@link MoireCap}): every frame with exactly one face
 * adds its moire to the session's rolling median, scored or not; any other face count clears it. When the median of
 * at least moireMinFrames values reaches moireCapThreshold (1.40) the score becomes min(score, moireCapScore = 0.60),
 * so the session goes to a challenge instead of passing passively (a phone held close fools the models, not the
 * moire). livenessScore is the score after the cap.
 *
 * <p>padDetails (audit only, at most 8 keys, never shown in the UI):
 * <ul>
 *   <li>scored frames, in order: minifas_v2_live, minifas_v1se_live, minifas_v2_c0, minifas_v2_c2,
 *       minifas_v1se_spoof (= v1se c0 + c2), moire, moire_med (the session's rolling median), moire_cap (1 when the
 *       cap or veto applied, else 0);</li>
 *   <li>pose-gated frames: pose_gated (1), moire, moire_med;</li>
 *   <li>frames without a mesh: moire, moire_med;</li>
 *   <li>no face or several faces: empty.</li>
 * </ul>
 * Glare is still computed; {@link #lastReplayCues()} exposes it with the moire for evaluation tools and demos.
 *
 * <p>One analyzer per session: it owns its buffers and the session moire state and is not thread-safe. The ONNX
 * sessions are shared and are never closed by an analyzer. Created only by the factory in this package.
 */
public final class OnnxFaceAnalyzer implements FaceAnalyzer {

    static final String POSE_GATED = "pose_gated";
    static final String MOIRE_CAP = "moire_cap";

    private final AnalyzerConfig config;
    private final String modelVersion;
    private final OnnxModels models;
    private final YuNetDetector detector;
    private final FaceMeshLandmarker landmarker;
    private final SilentFacePad pad;
    private final QualityAssessor quality;
    private final ReplayCues cues;
    private final MoireCap moireCap;
    private final AnalyzerStats stats = new AnalyzerStats();
    private final float[] pose = new float[3];
    private ReplayCues.Cues lastCues;
    private QualityReport lastQuality;
    private volatile boolean closed;
    /** Test seam: when not NaN, replaces every computed moire value (tests drive the cap without screen images). */
    float moireOverride = Float.NaN;

    OnnxFaceAnalyzer(OnnxModels sharedModels, AnalyzerConfig config, String modelVersion) {
        if (sharedModels == null || config == null) throw new IllegalArgumentException("null argument");
        this.config = config;
        this.modelVersion = modelVersion;
        this.models = sharedModels;
        this.detector = new YuNetDetector(sharedModels.env(), sharedModels.session(ModelRole.DETECTOR), config.detector);
        this.landmarker = new FaceMeshLandmarker(sharedModels.env(), sharedModels.session(ModelRole.LANDMARKS), config);
        this.pad = new SilentFacePad(sharedModels.env(), sharedModels.session(ModelRole.PAD_V2),
                sharedModels.session(ModelRole.PAD_V1SE));
        this.quality = new QualityAssessor(config);
        this.cues = new ReplayCues();
        this.moireCap = new MoireCap(config.moireCapThreshold, config.moireCapScore, config.moireWindow,
                config.moireMinFrames, config.moireVetoThreshold);
    }

    @Override
    public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "analyzer is closed");
        if (frame == null || frame.width() <= 0 || frame.height() <= 0) {
            throw new LivenessEngineException(ErrorCode.INVALID_FRAME, "frame is null or has no pixels");
        }
        long start = System.nanoTime();
        try {
            return run(frame, start);
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime failed while analyzing a frame", e);
        } catch (RuntimeException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "Unexpected failure analyzing a frame", e);
        }
    }

    private FrameAnalysis run(Frame frame, long start) throws OrtException {
        byte[] rgb = frame.rgb();
        int w = frame.width(), h = frame.height();
        stats.beginFrame();

        long t = System.nanoTime();
        List<FaceDetection> faces = detector.detect(rgb, w, h);
        stats.detectNs = System.nanoTime() - t;
        int faceCount = YuNetDetector.countFaces(faces, config.detector.secondFaceMinAreaRatio);
        if (faceCount != 1) {
            moireCap.reset(); // no face, or a different face may have been presented
            lastCues = null;
            lastQuality = null;
        }
        if (faceCount == 0) {
            stats.framesNoFace++;
            return finish(frame, 0, null, null, nan3(), EnumSet.noneOf(QualityIssue.class), Float.NaN,
                    Collections.<String, Float>emptyMap(), start);
        }
        FaceDetection primary = YuNetDetector.primary(faces);
        if (faceCount > 1) {
            stats.framesMultiFace++;
            return finish(frame, faceCount, primary.toBox(), null, nan3(), EnumSet.noneOf(QualityIssue.class),
                    Float.NaN, Collections.<String, Float>emptyMap(), start);
        }
        stats.framesOneFace++;

        t = System.nanoTime();
        lastCues = cues.compute(rgb, w, h, primary); // glare feeds the TOO_BRIGHT check, moire the session cap
        stats.cuesNs = System.nanoTime() - t;

        t = System.nanoTime();
        QualityReport q = quality.assess(rgb, w, h, primary, lastCues.glare);
        stats.qualityNs = System.nanoTime() - t;
        lastQuality = q;

        t = System.nanoTime();
        MeshResult mesh = landmarker.estimate(rgb, w, h, primary);
        stats.meshNs = System.nanoTime() - t;
        if (mesh == null) {
            Map<String, Float> details = new LinkedHashMap<String, Float>();
            capped(Float.NaN, details);
            return finish(frame, 1, primary.toBox(), null, nan3(), q.issuesCopy(), Float.NaN, details, start);
        }

        t = System.nanoTime();
        HeadPoseEstimator.yawPitchRoll(mesh.points, (float) config.pitchBiasDeg, pose);
        FaceLandmarks landmarks = MeshIndices.toFaceLandmarks(mesh.points);
        stats.poseNs = System.nanoTime() - t;
        float[] ypr = {pose[0], pose[1], pose[2]};

        if (poseGated(ypr[0], ypr[1], config)) {
            stats.framesPoseGated++;
            Map<String, Float> details = new LinkedHashMap<String, Float>();
            details.put(POSE_GATED, 1f);
            capped(Float.NaN, details);
            return finish(frame, 1, primary.toBox(), landmarks, ypr, q.issuesCopy(), Float.NaN, details, start);
        }

        t = System.nanoTime();
        PadResult p = pad.assess(rgb, w, h, primary);
        stats.padNs = System.nanoTime() - t;
        Map<String, Float> details = new LinkedHashMap<String, Float>();
        details.put("minifas_v2_live", p.v2[1]);
        details.put("minifas_v1se_live", p.v1se[1]);
        details.put("minifas_v2_c0", p.v2[0]);
        details.put("minifas_v2_c2", p.v2[2]);
        details.put("minifas_v1se_spoof", p.v1se[0] + p.v1se[2]);
        float score = capped(p.score, details);
        details.put(MOIRE_CAP, moireCap.active() ? 1f : 0f);
        return finish(frame, 1, primary.toBox(), landmarks, ypr, q.issuesCopy(), score, details, start);
    }

    /**
     * Adds this frame's moire (from the replay cues computed before quality) to the session's rolling median
     * ({@link MoireCap}), records moire and moire_med, and returns the score after the cap or veto.
     */
    private float capped(float score, Map<String, Float> details) {
        float moire = Float.isNaN(moireOverride) ? lastCues.moire : moireOverride;
        float out = moireCap.apply(score, moire);
        if (!Float.isNaN(moire)) details.put("moire", moire);
        if (!Float.isNaN(moireCap.rollingMedian())) details.put("moire_med", moireCap.rollingMedian());
        return out;
    }

    private FrameAnalysis finish(Frame frame, int faceCount, float[] box, FaceLandmarks landmarks, float[] ypr,
                                 EnumSet<QualityIssue> issues, float score, Map<String, Float> details, long start) {
        long elapsed = System.nanoTime() - start;
        stats.totalNs = elapsed;
        return new FrameAnalysis(frame, faceCount, box, landmarks, ypr[0], ypr[1], ypr[2], issues, score, details,
                elapsed / 1_000_000L);
    }

    /** True when the PAD model's pose range is exceeded (or the pose is unknown): the score is then NaN. */
    static boolean poseGated(float yawDeg, float pitchDeg, AnalyzerConfig config) {
        if (Float.isNaN(yawDeg) || Float.isNaN(pitchDeg)) return true;
        return Math.abs(yawDeg) > config.padMaxYawDeg || Math.abs(pitchDeg) > config.padMaxPitchDeg;
    }

    private static float[] nan3() {
        return new float[] {Float.NaN, Float.NaN, Float.NaN};
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    /** The shared sessions this analyzer runs on (tests check sharing). */
    OnnxModels models() {
        return models;
    }

    /** The effective configuration of this analyzer. */
    public AnalyzerConfig config() {
        return config;
    }

    /**
     * Moire and glare of the last frame's face (not part of the FaceAnalyzer contract; for evaluation tools and the
     * demo). Null when the last frame had no face or several faces.
     */
    public ReplayCues.Cues lastReplayCues() {
        return lastCues;
    }

    /**
     * The quality metrics of the last frame's face, lapVar included (not part of the FaceAnalyzer contract; for
     * evaluation tools). Null when the last frame had no face or several faces.
     */
    public QualityReport lastQuality() {
        return lastQuality;
    }

    /** Statistics of this analyzer (not part of the FaceAnalyzer contract; for the benchmark and the demo). */
    public AnalyzerStats stats() {
        return stats;
    }

    /** Idempotent. Releases nothing shared: the ONNX sessions belong to the factory. */
    @Override
    public void close() {
        closed = true;
    }
}
