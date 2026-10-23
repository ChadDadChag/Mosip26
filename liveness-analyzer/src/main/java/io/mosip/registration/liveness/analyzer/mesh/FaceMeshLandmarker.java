package io.mosip.registration.liveness.analyzer.mesh;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.image.Affine;
import io.mosip.registration.liveness.analyzer.image.ImageOps;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * MediaPipe FaceMesh V2 on the primary face ({@code reference_pipeline.py}, section "Face mesh").
 *
 * <p>ROI (MediaPipe's tracking convention): square of side S = meshRoiScale x max(w, h) centred on the YuNet box,
 * rotated by theta = atan2(kp1.y - kp0.y, kp1.x - kp0.x) so the eyes are level; sampled bilinearly (replicated
 * border) to 256 x 256, NHWC R, G, B in 0..1. Outputs read by name: "Identity" (478 x (x, y, z) in 256-px units)
 * and "Identity_1" (presence logit); "Identity_2" is ignored. Back-projection with dx = (x/256 - 0.5) S,
 * dy = (y/256 - 0.5) S: X = cx + cos dx - sin dy, Y = cy + sin dx + cos dy, Z = (z/256) S.
 *
 * <p>One instance per analyzer: buffers and the returned {@link MeshResult} are reused; not thread-safe.
 */
public final class FaceMeshLandmarker {

    static final int SIZE = 256;
    private static final String INPUT = "input_12";
    private static final String LANDMARKS = "Identity";
    private static final String PRESENCE = "Identity_1";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final double roiScale;
    private final double presenceThreshold;
    private final float[] input = new float[SIZE * SIZE * 3];
    private final MeshResult result = new MeshResult();

    public FaceMeshLandmarker(OrtEnvironment env, OrtSession session, AnalyzerConfig config) {
        if (env == null || session == null || config == null) throw new IllegalArgumentException("null argument");
        this.env = env;
        this.session = session;
        this.roiScale = config.meshRoiScale;
        this.presenceThreshold = config.meshPresenceThreshold;
    }

    /** The mesh of {@code face}, or null when the presence value is below the threshold. */
    public MeshResult estimate(byte[] rgb, int width, int height, FaceDetection face) throws OrtException {
        Affine m = prepare(rgb, width, height, face);
        float[] raw = null;
        float logit = Float.NaN;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, SIZE, SIZE, 3});
             OrtSession.Result out = session.run(Collections.singletonMap(INPUT, tensor))) {
            for (Map.Entry<String, OnnxValue> e : out) {
                if (LANDMARKS.equals(e.getKey())) {
                    FloatBuffer fb = ((OnnxTensor) e.getValue()).getFloatBuffer();
                    raw = new float[fb.remaining()];
                    fb.get(raw);
                } else if (PRESENCE.equals(e.getKey())) {
                    logit = ((OnnxTensor) e.getValue()).getFloatBuffer().get(0);
                }
            }
        }
        if (raw == null || raw.length != MeshResult.POINTS * 3 || Float.isNaN(logit)) {
            throw new IllegalStateException("face mesh outputs missing or malformed");
        }
        double presence = 1.0 / (1.0 + Math.exp(-(double) logit));
        result.presence = (float) presence;
        if (presence < presenceThreshold) return null;
        double zScale = result.side / SIZE;
        for (int n = 0; n < MeshResult.POINTS; n++) {
            double x = raw[3 * n], y = raw[3 * n + 1], z = raw[3 * n + 2];
            float[] p = result.points[n];
            p[0] = (float) m.mapX(x, y);
            p[1] = (float) m.mapY(x, y);
            p[2] = (float) (z / SIZE * result.side);
        }
        return result;
    }

    /** Sets the ROI on the reused result, fills the input buffer and returns the ROI affine. */
    Affine prepare(byte[] rgb, int width, int height, FaceDetection face) {
        result.cx = face.x + face.w / 2;
        result.cy = face.y + face.h / 2;
        result.side = roiScale * Math.max(face.w, face.h);
        result.theta = Math.atan2(face.keypointY(1) - face.keypointY(0), face.keypointX(1) - face.keypointX(0));
        Affine m = Affine.rotatedSquare(result.cx, result.cy, result.side, result.theta, SIZE);
        ImageOps.sample(rgb, width, height, m, SIZE, SIZE, ImageOps.Layout.NHWC_RGB_0_1, input);
        return m;
    }

    /** The last input tensor (for parity tests). */
    float[] input() {
        return input;
    }

    /** The reused result; holds the ROI even when estimate() returned null. */
    MeshResult lastResult() {
        return result;
    }
}
