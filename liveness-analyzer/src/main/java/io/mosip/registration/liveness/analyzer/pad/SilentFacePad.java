package io.mosip.registration.liveness.analyzer.pad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.image.Affine;
import io.mosip.registration.liveness.analyzer.image.ImageOps;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * Passive PAD with Silent-Face's MiniFASNetV2 (crop scale 2.7) and MiniFASNetV1SE (crop scale 4.0)
 * ({@code reference_pipeline.py}, section "Silent-Face PAD").
 *
 * <p>For each model: integer box [floor(x), floor(y), round(w), round(h)] (round = floor(v + 0.5)), crop box from
 * {@link #newBox}, the inclusive crop resized to 80 x 80 with {@link ImageOps}, an NCHW tensor of raw 0..255
 * B, G, R values (no /255, no normalisation), softmax of the logits. Score = (p_v2[1] + p_v1se[1]) / 2.
 *
 * <p>One instance per analyzer: it reuses its input buffer and is not thread-safe. Sessions may be shared.
 */
public final class SilentFacePad {

    public static final double V2_SCALE = 2.7;
    public static final double V1SE_SCALE = 4.0;
    static final int SIZE = 80;
    static final int REAL_CLASS = 1;
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";

    private final OrtEnvironment env;
    private final OrtSession v2;
    private final OrtSession v1se;
    private final float[] input = new float[3 * SIZE * SIZE];

    public SilentFacePad(OrtEnvironment env, OrtSession v2, OrtSession v1se) {
        if (env == null || v2 == null || v1se == null) throw new IllegalArgumentException("null argument");
        this.env = env;
        this.v2 = v2;
        this.v1se = v1se;
    }

    public PadResult assess(byte[] rgb, int width, int height, FaceDetection face) throws OrtException {
        int[] box = intBox(face);
        int[] v2Box = newBox(width, height, box, V2_SCALE);
        float[] pV2 = run(v2, rgb, width, height, v2Box);
        int[] v1seBox = newBox(width, height, box, V1SE_SCALE);
        float[] pV1se = run(v1se, rgb, width, height, v1seBox);
        float score = (float) (((double) pV2[REAL_CLASS] + pV1se[REAL_CLASS]) / 2.0);
        return new PadResult(pV2, pV1se, score, v2Box, v1seBox);
    }

    /** Fills the input buffer with the 80 x 80 crop for one model (exposed for parity tests). */
    float[] prepare(byte[] rgb, int width, int height, int[] cropBox) {
        int ltx = cropBox[0], lty = cropBox[1], rbx = cropBox[2], rby = cropBox[3];
        ImageOps.sample(rgb, width, height, Affine.crop(ltx, lty, rbx - ltx + 1, rby - lty + 1, SIZE, SIZE),
                SIZE, SIZE, ImageOps.Layout.NCHW_BGR_0_255, input);
        return input;
    }

    private float[] run(OrtSession session, byte[] rgb, int width, int height, int[] cropBox) throws OrtException {
        prepare(rgb, width, height, cropBox);
        float[] logits = null;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(INPUT, tensor))) {
            for (Map.Entry<String, OnnxValue> e : result) {
                if (OUTPUT.equals(e.getKey())) {
                    FloatBuffer fb = ((OnnxTensor) e.getValue()).getFloatBuffer();
                    logits = new float[fb.remaining()];
                    fb.get(logits);
                }
            }
        }
        if (logits == null || logits.length != 3) throw new IllegalStateException("MiniFASNet output missing or not [1,3]");
        return softmax(logits);
    }

    /** Integer box fed to {@link #newBox}: [floor(x), floor(y), round(w), round(h)]. */
    public static int[] intBox(FaceDetection face) {
        return new int[] {(int) Math.floor(face.x), (int) Math.floor(face.y),
                (int) Math.floor(face.w + 0.5), (int) Math.floor(face.h + 0.5)};
    }

    /**
     * Verbatim port of Silent-Face {@code CropImage._get_new_box}: the box scaled about its centre (capped so it
     * fits the frame), then shifted inside the frame. Python {@code int()} truncates toward zero, as does a Java
     * cast. Returns inclusive {left_top_x, left_top_y, right_bottom_x, right_bottom_y}.
     */
    public static int[] newBox(int srcW, int srcH, int[] bbox, double scale) {
        double x = bbox[0];
        double y = bbox[1];
        double boxW = bbox[2];
        double boxH = bbox[3];

        scale = Math.min((srcH - 1) / boxH, Math.min((srcW - 1) / boxW, scale));

        double newWidth = boxW * scale;
        double newHeight = boxH * scale;
        double centerX = boxW / 2 + x, centerY = boxH / 2 + y;

        double leftTopX = centerX - newWidth / 2;
        double leftTopY = centerY - newHeight / 2;
        double rightBottomX = centerX + newWidth / 2;
        double rightBottomY = centerY + newHeight / 2;

        if (leftTopX < 0) {
            rightBottomX -= leftTopX;
            leftTopX = 0;
        }

        if (leftTopY < 0) {
            rightBottomY -= leftTopY;
            leftTopY = 0;
        }

        if (rightBottomX > srcW - 1) {
            leftTopX -= rightBottomX - srcW + 1;
            rightBottomX = srcW - 1;
        }

        if (rightBottomY > srcH - 1) {
            leftTopY -= rightBottomY - srcH + 1;
            rightBottomY = srcH - 1;
        }

        return new int[] {(int) leftTopX, (int) leftTopY, (int) rightBottomX, (int) rightBottomY};
    }

    /** Softmax in double, stored as float. */
    static float[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float v : logits) max = Math.max(max, v);
        double[] e = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            e[i] = Math.exp(logits[i] - max);
            sum += e[i];
        }
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) out[i] = (float) (e[i] / sum);
        return out;
    }
}
