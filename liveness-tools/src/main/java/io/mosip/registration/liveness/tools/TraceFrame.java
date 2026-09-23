package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.detect.FaceGeometry;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One analysed frame in a trace. PRIVACY: only scale-free features and pose are stored: eye aspect ratio, mouth
 * width and corner lift (both relative to inter-ocular distance), yaw/pitch/roll, liveness score, face count,
 * quality issues and the face box normalised to the frame. No pixels, no landmark coordinates: a face cannot be
 * reconstructed from a trace.
 */
public final class TraceFrame {
    public final long t;           // ms since the trace started
    public final long seq;
    public final int faces;
    public final float[] box;      // x, y, w, h as fractions of the frame; null if no face
    public final float yaw, pitch, roll;
    public final float score;
    public final float ear, mouthWidth, cornerLift;
    public final Set<FrameAnalysis.QualityIssue> issues;
    public final long processingMs;

    public TraceFrame(long t, long seq, int faces, float[] box, float yaw, float pitch, float roll, float score,
                      float ear, float mouthWidth, float cornerLift, Set<FrameAnalysis.QualityIssue> issues,
                      long processingMs) {
        this.t = t; this.seq = seq; this.faces = faces; this.box = box; this.yaw = yaw; this.pitch = pitch;
        this.roll = roll; this.score = score; this.ear = ear; this.mouthWidth = mouthWidth;
        this.cornerLift = cornerLift; this.issues = issues; this.processingMs = processingMs;
    }

    /** Extracts features from an analyzer result; {@code t0} is the trace start time. */
    public static TraceFrame of(FrameAnalysis a, long t0) {
        float[] box = null;
        if (a.faceBox != null && a.frame != null) {
            float w = a.frame.width(), h = a.frame.height();
            box = new float[] {a.faceBox[0] / w, a.faceBox[1] / h, a.faceBox[2] / w, a.faceBox[3] / h};
        }
        return new TraceFrame(a.frame.timestampMs() - t0, a.frame.sequence(), a.faceCount, box, a.yawDeg, a.pitchDeg,
                a.rollDeg, a.livenessScore, FaceGeometry.meanEar(a.landmarks), FaceGeometry.mouthWidthRatio(a.landmarks),
                FaceGeometry.cornerLift(a.landmarks), a.qualityIssues, a.processingMs);
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", t);
        m.put("seq", seq);
        m.put("faces", faces);
        if (box != null) {
            List<Object> b = new ArrayList<Object>();
            for (float f : box) b.add(f);
            m.put("box", b);
        }
        m.put("yaw", yaw);
        m.put("pitch", pitch);
        m.put("roll", roll);
        m.put("score", score);
        m.put("ear", ear);
        m.put("mouth", mouthWidth);
        m.put("lift", cornerLift);
        List<Object> q = new ArrayList<Object>();
        for (FrameAnalysis.QualityIssue i : issues) q.add(i.name());
        m.put("issues", q);
        m.put("ms", processingMs);
        return m;
    }

    static TraceFrame fromJson(Map<String, Object> m) {
        float[] box = null;
        Object b = m.get("box");
        if (b instanceof List) {
            List<?> l = (List<?>) b;
            box = new float[l.size()];
            for (int i = 0; i < box.length; i++) box[i] = num(l.get(i));
        }
        Set<FrameAnalysis.QualityIssue> issues = EnumSet.noneOf(FrameAnalysis.QualityIssue.class);
        Object q = m.get("issues");
        if (q instanceof List) for (Object o : (List<?>) q) issues.add(FrameAnalysis.QualityIssue.valueOf((String) o));
        return new TraceFrame((long) num(m.get("t")), (long) num(m.get("seq")), (int) num(m.get("faces")), box,
                num(m.get("yaw")), num(m.get("pitch")), num(m.get("roll")), num(m.get("score")), num(m.get("ear")),
                num(m.get("mouth")), num(m.get("lift")), issues, (long) num(m.get("ms")));
    }

    private static float num(Object o) {
        return o instanceof Number ? ((Number) o).floatValue() : Float.NaN;
    }
}
