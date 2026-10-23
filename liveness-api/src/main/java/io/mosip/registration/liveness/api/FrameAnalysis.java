package io.mosip.registration.liveness.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Output of FaceAnalyzer for one frame. Owner: Person 2. Consumed by Person 3's orchestrator. */
public final class FrameAnalysis {

    public enum QualityIssue { TOO_DARK, TOO_BRIGHT, BLURRY, FACE_TOO_SMALL, FACE_TOO_LARGE, FACE_OFF_CENTER, FACE_CUT_OFF }

    public final Frame frame;
    public final int faceCount;              // 0 = no face, >1 = multiple faces
    public final float[] faceBox;            // primary face [x, y, w, h] in pixels; null if faceCount == 0
    public final FaceLandmarks landmarks;    // primary face; null if faceCount == 0
    public final float yawDeg, pitchDeg, rollDeg; // + yaw = subject turned to THEIR left
    public final Set<QualityIssue> qualityIssues;
    /** Passive liveness: probability [0..1] that the primary face is a live person. NaN if not computed. */
    public final float livenessScore;
    /** Raw PAD/model outputs for AUDIT ONLY (never shown in UI), e.g. {"minifas_v2": 0.91, "moire": 0.1}. */
    public final Map<String, Float> padDetails;
    public final long processingMs;

    public FrameAnalysis(Frame frame, int faceCount, float[] faceBox, FaceLandmarks landmarks,
                         float yawDeg, float pitchDeg, float rollDeg, Set<QualityIssue> qualityIssues,
                         float livenessScore, Map<String, Float> padDetails, long processingMs) {
        this.frame = frame; this.faceCount = faceCount; this.faceBox = faceBox; this.landmarks = landmarks;
        this.yawDeg = yawDeg; this.pitchDeg = pitchDeg; this.rollDeg = rollDeg;
        this.qualityIssues = qualityIssues == null ? EnumSet.noneOf(QualityIssue.class) : qualityIssues;
        this.livenessScore = livenessScore;
        this.padDetails = padDetails == null ? Collections.<String, Float>emptyMap() : padDetails;
        this.processingMs = processingMs;
    }

    public boolean usable() { return faceCount == 1 && qualityIssues.isEmpty(); }
}
