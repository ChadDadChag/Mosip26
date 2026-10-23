package io.mosip.registration.liveness.core.testing;

import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builder for synthetic {@link FrameAnalysis} values: a 640x480 frame with one centred face whose landmarks are
 * geometrically consistent with the requested eye openness (EAR) and mouth width. Stands in for Part 2's analyzer
 * in unit tests only.
 */
public final class Fa {

    public static final int W = 640, H = 480;
    /** Inter-ocular distance of the synthetic face in pixels. */
    public static final float IOD = 100f;
    /** EAR of open eyes on the synthetic face. */
    public static final float OPEN_EAR = 0.30f;
    /** Mouth width / IOD of the neutral synthetic face. */
    public static final float NEUTRAL_MOUTH = 0.90f;

    private static final byte[] PIXELS = new byte[W * H * 3];

    private long ts;
    private long seq;
    private int faces = 1;
    private float score = 0.9f;
    private float yaw, pitch;
    private float ear = OPEN_EAR;
    private float mouth = NEUTRAL_MOUTH;
    private float cornerLift;
    private float dx, dy;
    private float boxScale = 1f;
    private final Set<FrameAnalysis.QualityIssue> issues = EnumSet.noneOf(FrameAnalysis.QualityIssue.class);
    private Map<String, Float> pad = Collections.emptyMap();
    private boolean landmarks = true;
    private long processingMs = 30;

    public static Fa at(long timestampMs, long sequence) {
        Fa f = new Fa();
        f.ts = timestampMs;
        f.seq = sequence;
        return f;
    }

    public Fa faces(int n) { faces = n; return this; }
    public Fa score(float s) { score = s; return this; }
    public Fa yaw(float d) { yaw = d; return this; }
    public Fa pitch(float d) { pitch = d; return this; }
    public Fa ear(float e) { ear = e; return this; }
    public Fa eyesClosed() { ear = 0.08f; return this; }
    public Fa mouth(float widthOverIod) { mouth = widthOverIod; return this; }
    public Fa smile() { mouth = NEUTRAL_MOUTH * 1.3f; cornerLift = 0.08f; return this; }
    public Fa shift(float x, float y) { dx = x; dy = y; return this; }
    public Fa boxScale(float s) { boxScale = s; return this; }
    public Fa issue(FrameAnalysis.QualityIssue q) { issues.add(q); return this; }
    public Fa noLandmarks() { landmarks = false; return this; }
    public Fa processing(long ms) { processingMs = ms; return this; }

    public Fa pad(String key, float value) {
        if (pad.isEmpty()) pad = new LinkedHashMap<String, Float>();
        pad.put(key, value);
        return this;
    }

    public FrameAnalysis build() {
        Frame frame = new Frame(PIXELS, W, H, ts, seq, "mock:test");
        if (faces == 0) {
            return new FrameAnalysis(frame, 0, null, null, 0, 0, 0, issues, Float.NaN, pad, processingMs);
        }
        float cx = W / 2f + dx, cy = H / 2f + dy;
        float bw = 220 * boxScale, bh = 280 * boxScale;
        float[] box = {cx - bw / 2, cy - bh / 2, bw, bh};
        return new FrameAnalysis(frame, faces, box, landmarks ? landmarks(cx, cy) : null, yaw, pitch, 0f, issues,
                score, pad, processingMs);
    }

    private FaceLandmarks landmarks(float cx, float cy) {
        float s = boxScale;
        float eyeY = cy - 40 * s;
        // Subject's left eye appears on the image right for an unmirrored camera; the side does not matter here.
        float[][] left = eye(cx + IOD * s / 2, eyeY, s);
        float[][] right = eye(cx - IOD * s / 2, eyeY, s);
        float mouthY = cy + 60 * s;
        float half = mouth * IOD * s / 2;
        float lift = cornerLift * IOD * s;
        return new FaceLandmarks(left, right,
                new float[] {cx - half, mouthY - lift}, new float[] {cx + half, mouthY - lift},
                new float[] {cx, mouthY - 3 * s}, new float[] {cx, mouthY + 3 * s},
                new float[] {cx, cy + 10 * s}, new float[] {cx, cy + 130 * s}, null, null);
    }

    /** Six EAR-ordered points with width 40 px and the requested aspect ratio. */
    private float[][] eye(float ex, float ey, float s) {
        float w = 40 * s;
        float h = ear * w; // EAR = (2h) / (2w) with both vertical pairs = h
        return new float[][] {
                {ex - w / 2, ey},             // p1 outer corner
                {ex - w / 6, ey - h / 2},     // p2 upper-outer
                {ex + w / 6, ey - h / 2},     // p3 upper-inner
                {ex + w / 2, ey},             // p4 inner corner
                {ex + w / 6, ey + h / 2},     // p5 lower-inner
                {ex - w / 6, ey + h / 2}};    // p6 lower-outer
    }
}
