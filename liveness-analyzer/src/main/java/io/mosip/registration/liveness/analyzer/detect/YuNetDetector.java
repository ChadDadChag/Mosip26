package io.mosip.registration.liveness.analyzer.detect;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.mosip.registration.liveness.analyzer.image.Affine;
import io.mosip.registration.liveness.analyzer.image.ImageOps;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YuNet 2026may face detector ({@code reference_pipeline.YuNet}).
 *
 * <p>Input: the frame downscaled to at most {@code maxWidth} wide (s = min(1, maxWidth / W),
 * rw = round(W s), rh = round(H s), round = floor(v + 0.5)), zero-padded right and bottom to multiples of 32,
 * as an NCHW float tensor of raw 0..255 B, G, R values. Outputs are decoded as OpenCV
 * {@code FaceDetectorYN::postProcess}, filtered by score, reduced by greedy NMS and mapped back by 1/s.
 *
 * <p>One instance per analyzer: it reuses its input buffer and is not thread-safe. The session may be shared.
 */
public final class YuNetDetector {

    static final int[] STRIDES = {8, 16, 32};
    private static final String INPUT = "input";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final DetectorConfig config;

    private float[] input = new float[0];
    private int paddedW, paddedH;
    private double scale;

    public YuNetDetector(OrtEnvironment env, OrtSession session, DetectorConfig config) {
        if (env == null || session == null || config == null) throw new IllegalArgumentException("null argument");
        this.env = env;
        this.session = session;
        this.config = config;
    }

    /** Detections in NMS order (highest score first), in frame coordinates. */
    public List<FaceDetection> detect(byte[] rgb, int width, int height) throws OrtException {
        prepare(rgb, width, height);
        Map<String, float[]> outputs = new HashMap<String, float[]>();
        long[] shape = {1, 3, paddedH, paddedW};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input, 0, 3 * paddedW * paddedH), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(INPUT, tensor))) {
            for (Map.Entry<String, OnnxValue> e : result) {
                FloatBuffer fb = ((OnnxTensor) e.getValue()).getFloatBuffer();
                float[] values = new float[fb.remaining()];
                fb.get(values);
                outputs.put(e.getKey(), values);
            }
        }
        List<FaceDetection> faces = decode(outputs, paddedW, paddedH, config);
        if (scale == 1.0) return faces;
        List<FaceDetection> mapped = new ArrayList<FaceDetection>(faces.size());
        for (FaceDetection f : faces) {
            double[] kps = new double[10];
            for (int n = 0; n < 5; n++) {
                kps[2 * n] = f.keypointX(n) / scale;
                kps[2 * n + 1] = f.keypointY(n) / scale;
            }
            mapped.add(new FaceDetection(f.x / scale, f.y / scale, f.w / scale, f.h / scale, f.score, kps));
        }
        return mapped;
    }

    /** Fills the input buffer for this frame and returns it; valid length is 3 * paddedWidth * paddedHeight. */
    float[] prepare(byte[] rgb, int width, int height) {
        scale = Math.min(1.0, (double) config.maxWidth / width);
        int rw = roundHalfUp(width * scale), rh = roundHalfUp(height * scale);
        paddedW = (rw + 31) / 32 * 32;
        paddedH = (rh + 31) / 32 * 32;
        int needed = 3 * paddedW * paddedH;
        if (input.length < needed) input = new float[needed];
        ImageOps.sample(rgb, width, height, Affine.scale(width, height, rw, rh), rw, rh,
                ImageOps.Layout.NCHW_BGR_0_255, input, paddedW, paddedH);
        return input;
    }

    int paddedWidth() {
        return paddedW;
    }

    int paddedHeight() {
        return paddedH;
    }

    /**
     * Decodes raw outputs (by name: cls_/obj_/bbox_/kps_ + stride) into faces in YuNet-input pixels, then NMS.
     * For stride s: cols = padW / s, anchor idx = r * cols + c;
     * score = sqrt(clamp(cls) * clamp(obj)); cx = (c + b0) s; cy = (r + b1) s; w = exp(b2) s; h = exp(b3) s;
     * box = [cx - w/2, cy - h/2, w, h]; keypoint n = ((k[2n] + c) s, (k[2n+1] + r) s).
     */
    static List<FaceDetection> decode(Map<String, float[]> outputs, int padW, int padH, DetectorConfig config) {
        List<FaceDetection> faces = new ArrayList<FaceDetection>();
        for (int stride : STRIDES) {
            int cols = padW / stride, rows = padH / stride;
            float[] cls = require(outputs, "cls_" + stride);
            float[] obj = require(outputs, "obj_" + stride);
            float[] bbox = require(outputs, "bbox_" + stride);
            float[] kps = require(outputs, "kps_" + stride);
            int anchors = rows * cols;
            if (cls.length < anchors || obj.length < anchors || bbox.length < 4 * anchors || kps.length < 10 * anchors) {
                throw new IllegalArgumentException("YuNet output smaller than expected for stride " + stride);
            }
            for (int idx = 0; idx < anchors; idx++) {
                double score = Math.sqrt(clamp01(cls[idx]) * clamp01(obj[idx]));
                if (score < config.scoreThreshold) continue;
                int r = idx / cols, c = idx % cols;
                double cx = (c + (double) bbox[4 * idx]) * stride;
                double cy = (r + (double) bbox[4 * idx + 1]) * stride;
                double bw = Math.exp(bbox[4 * idx + 2]) * stride;
                double bh = Math.exp(bbox[4 * idx + 3]) * stride;
                double[] k = new double[10];
                for (int n = 0; n < 5; n++) {
                    k[2 * n] = (kps[10 * idx + 2 * n] + (double) c) * stride;
                    k[2 * n + 1] = (kps[10 * idx + 2 * n + 1] + (double) r) * stride;
                }
                faces.add(new FaceDetection(cx - bw / 2, cy - bh / 2, bw, bh, score, k));
            }
        }
        // Stable sort by score, highest first; ties keep stride-then-anchor order.
        Collections.sort(faces, new Comparator<FaceDetection>() {
            @Override public int compare(FaceDetection p, FaceDetection q) {
                return Double.compare(q.score, p.score);
            }
        });
        List<FaceDetection> kept = new ArrayList<FaceDetection>();
        for (FaceDetection f : faces) {
            boolean keep = true;
            for (FaceDetection k : kept) {
                if (FaceDetection.iou(f, k) > config.nmsIou) {
                    keep = false;
                    break;
                }
            }
            if (keep) kept.add(f);
        }
        return kept;
    }

    /** faceCount rule: faces whose area is at least {@code minAreaRatio} x the largest face's area. */
    public static int countFaces(List<FaceDetection> faces, double minAreaRatio) {
        FaceDetection largest = primary(faces);
        if (largest == null) return 0;
        int count = 0;
        for (FaceDetection f : faces) {
            if (f.area() >= minAreaRatio * largest.area()) count++;
        }
        return count;
    }

    /** {@link #countFaces(List, double)} with the default 0.2 ratio. */
    public static int countFaces(List<FaceDetection> faces) {
        return countFaces(faces, DetectorConfig.defaults().secondFaceMinAreaRatio);
    }

    /** The largest face (the first in NMS order on an exact tie), or null. */
    public static FaceDetection primary(List<FaceDetection> faces) {
        FaceDetection best = null;
        for (FaceDetection f : faces) {
            if (best == null || f.area() > best.area()) best = f;
        }
        return best;
    }

    static int roundHalfUp(double v) {
        return (int) Math.floor(v + 0.5);
    }

    private static double clamp01(float v) {
        return v < 0f ? 0.0 : v > 1f ? 1.0 : v;
    }

    private static float[] require(Map<String, float[]> outputs, String name) {
        float[] v = outputs.get(name);
        if (v == null) throw new IllegalArgumentException("YuNet output missing: " + name);
        return v;
    }
}
