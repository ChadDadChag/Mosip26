package io.mosip.registration.liveness.analyzer;

import ai.onnxruntime.OrtException;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.mesh.FaceMeshLandmarker;
import io.mosip.registration.liveness.analyzer.mesh.HeadPoseEstimator;
import io.mosip.registration.liveness.analyzer.mesh.MeshIndices;
import io.mosip.registration.liveness.analyzer.mesh.MeshResult;
import io.mosip.registration.liveness.analyzer.pad.MoireCap;
import io.mosip.registration.liveness.analyzer.pad.PadLatch;
import io.mosip.registration.liveness.analyzer.pad.PadResult;
import io.mosip.registration.liveness.analyzer.pad.ReplayCues;
import io.mosip.registration.liveness.analyzer.pad.SilentFacePad;
import io.mosip.registration.liveness.analyzer.pad.VolatilityCap;
import io.mosip.registration.liveness.analyzer.quality.QualityAssessor;
import io.mosip.registration.liveness.analyzer.quality.QualityReport;
import io.mosip.registration.liveness.analyzer.quality.RelativeBlur;
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
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

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
 * score); one face: quality issues, replay cues and mesh. BLURRY is also set relative to the session
 * ({@link RelativeBlur}: lapVar below qualityRelativeBlurRatio x the median of the last 15 unflagged single-face
 * frames, once 5 are known). The mesh ROI follows the previous frame's landmarks while the face box overlaps (F7,
 * {@link AnalyzerConfig#meshTracking}), which keeps landmarks through larger head turns than YuNet's eye keypoints
 * allow. Without a mesh the pose is unknown, so the score is NaN.
 * Outside the PAD pose range (|yaw| &gt; padMaxYawDeg or |pitch| &gt; padMaxPitchDeg) the score is NaN; otherwise
 * score = (p_v2[1] + p_v1se[1]) / 2, then the session moire cap ({@link MoireCap}): every frame with exactly one face
 * adds its moire to the session's rolling median, scored or not; any other face count clears it. When the median of
 * at least moireMinFrames values reaches moireCapThreshold (1.40) the score becomes min(score, moireCapScore = 0.60),
 * so the session goes to a challenge instead of passing passively (a phone held close fools the models, not the
 * moire). Then the session print guards (F3 retest, both off with NaN):
 * <ul>
 *   <li>the PAD latch ({@link PadLatch}): once the 3-frame median of the scores so far is below padLatchThreshold
 *       (0.30), every later score of the session, retries included, is at most padLatchCapScore (0.60), so a
 *       retry cannot pass passively. It never resets; a new session has a new analyzer;</li>
 *   <li>the volatility cap ({@link VolatilityCap}): when the second-lowest model score of the last 10 scored
 *       single-face frames (at least 5) is below volatilityMinScore (0.85), the score is at most volatilityCapScore
 *       (0.60). Any other face count clears that window, and capped scores never enter it, so it lifts once the
 *       low frames have left.</li>
 * </ul>
 * livenessScore is the score after the cap and the guards.
 *
 * <p>padDetails (audit only, at most 8 keys, never shown in the UI):
 * <ul>
 *   <li>scored frames, in order: minifas_v2_live, minifas_v1se_live, minifas_v2_c0, moire, moire_med (the
 *       session's rolling median), moire_cap (1 when the cap or veto applied, else 0), volatility_cap (1 when the
 *       volatility cap applied, else 0), pad_latch (1 while the session is latched, else 0). The guards' two keys
 *       replaced minifas_v2_c2 and minifas_v1se_spoof, which the softmax makes redundant:
 *       v2 c2 = 1 - minifas_v2_live - minifas_v2_c0, v1se spoof = 1 - minifas_v1se_live;</li>
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
    static final String VOLATILITY_CAP = "volatility_cap";
    static final String PAD_LATCH = "pad_latch";
    private static final Logger LOG = Logger.getLogger(OnnxFaceAnalyzer.class.getName());
    /** Minimum IoU between this frame's face box and the previous one for landmark tracking (F7). */
    static final double TRACK_IOU = 0.3;

    private final AnalyzerConfig config;
    private final String modelVersion;
    private final OnnxModels models;
    private final YuNetDetector detector;
    private final FaceMeshLandmarker landmarker;
    private final SilentFacePad pad;
    private final QualityAssessor quality;
    private final ReplayCues cues;
    private final MoireCap moireCap;
    private final PadLatch padLatch;
    private final VolatilityCap volatilityCap;
    private final RelativeBlur relativeBlur;
    // Landmark tracking (F7): the last mesh and its face box; trackBox == null means no tracking source.
    private final float[][] trackPoints = new float[MeshResult.POINTS][2];
    private FaceDetection trackBox;
    private final AnalyzerStats stats = new AnalyzerStats();
    private final float[] pose = new float[3];
    private ReplayCues.Cues lastCues;
    private QualityReport lastQuality;
    private volatile boolean closed;
    /** Test seam: when not NaN, replaces every computed moire value (tests drive the cap without screen images). */
    float moireOverride = Float.NaN;
    /** Test seam: when not NaN, replaces every scored frame's model score (tests drive the print guards). */
    float modelScoreOverride = Float.NaN;

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
        this.padLatch = new PadLatch(config.padLatchThreshold, config.padLatchCapScore);
        this.volatilityCap = new VolatilityCap(config.volatilityMinScore, config.volatilityCapScore);
        this.relativeBlur = new RelativeBlur(config.qualityRelativeBlurRatio);
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
            volatilityCap.reset(); // the PAD latch is never reset within a session
            lastCues = null;
            lastQuality = null;
            trackBox = null;
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
        EnumSet<QualityIssue> issues = q.issuesCopy();
        if (relativeBlur.check(q.lapVar, issues.contains(QualityIssue.BLURRY))) issues.add(QualityIssue.BLURRY);
        stats.qualityNs = System.nanoTime() - t;
        lastQuality = q;

        t = System.nanoTime();
        MeshResult mesh = mesh(rgb, w, h, primary);
        stats.meshNs = System.nanoTime() - t;
        if (mesh == null) {
            Map<String, Float> details = new LinkedHashMap<String, Float>();
            capped(Float.NaN, details);
            return finish(frame, 1, primary.toBox(), null, nan3(), issues, Float.NaN, details, start);
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
            return finish(frame, 1, primary.toBox(), landmarks, ypr, issues, Float.NaN, details, start);
        }

        t = System.nanoTime();
        PadResult p = pad.assess(rgb, w, h, primary);
        stats.padNs = System.nanoTime() - t;
        Map<String, Float> details = new LinkedHashMap<String, Float>();
        details.put("minifas_v2_live", p.v2[1]);
        details.put("minifas_v1se_live", p.v1se[1]);
        details.put("minifas_v2_c0", p.v2[0]);
        float model = Float.isNaN(modelScoreOverride) ? p.score : modelScoreOverride;
        float score = capped(model, details);
        details.put(MOIRE_CAP, moireCap.active() ? 1f : 0f);
        score = guarded(score, model, details);
        return finish(frame, 1, primary.toBox(), landmarks, ypr, issues, score, details, start);
    }

    /**
     * Applies the session print guards to a scored frame's score (after the moire cap) and records volatility_cap and
     * pad_latch. The latch is logged once, with numbers only.
     */
    private float guarded(float score, float modelScore, Map<String, Float> details) {
        boolean wasLatched = padLatch.latched();
        float out = volatilityCap.apply(padLatch.apply(score), modelScore);
        details.put(VOLATILITY_CAP, volatilityCap.active() ? 1f : 0f);
        details.put(PAD_LATCH, padLatch.latched() ? 1f : 0f);
        if (!wasLatched && padLatch.latched()) {
            LOG.info(String.format(Locale.ROOT, "PAD latch: 3-frame score median %.3f below %.2f at scored frame %d;"
                    + " later scores of this session are capped at %.2f", padLatch.latchMedian(),
                    config.padLatchThreshold, padLatch.latchedAt(), config.padLatchCapScore));
        }
        return out;
    }

    /**
     * The mesh of the primary face (F7, {@code reference_pipeline.session_mesh}). When tracking is on, the previous
     * frame had a mesh and the face box overlaps the previous one (IoU >= {@value #TRACK_IOU}), the ROI comes from
     * the previous landmarks ({@link FaceMeshLandmarker#trackingRoi}); if the presence then falls below the threshold,
     * the frame is re-run once on the YuNet ROI. Otherwise the YuNet ROI is used. The mesh found (or none) becomes the
     * next frame's tracking source.
     */
    private MeshResult mesh(byte[] rgb, int w, int h, FaceDetection primary) throws OrtException {
        MeshResult mesh;
        if (config.meshTracking && trackBox != null && FaceDetection.iou(primary, trackBox) >= TRACK_IOU) {
            stats.framesTracked++;
            double[] roi = FaceMeshLandmarker.trackingRoi(trackPoints, config.meshRoiScale);
            mesh = landmarker.estimate(rgb, w, h, roi[0], roi[1], roi[2], roi[3]);
            if (mesh == null) {
                stats.trackingFallbacks++;
                mesh = landmarker.estimate(rgb, w, h, primary);
            }
        } else {
            mesh = landmarker.estimate(rgb, w, h, primary);
        }
        if (mesh == null) {
            trackBox = null;
        } else {
            trackFrom(mesh.points, primary);
        }
        return mesh;
    }

    /** Sets the tracking source (also a test seam: tests inject a bad source to force the fallback). */
    void trackFrom(float[][] points, FaceDetection box) {
        for (int n = 0; n < MeshResult.POINTS; n++) {
            trackPoints[n][0] = points[n][0];
            trackPoints[n][1] = points[n][1];
        }
        trackBox = box;
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
