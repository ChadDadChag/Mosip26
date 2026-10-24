package io.mosip.registration.liveness.analyzer.match;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.image.ImageOps;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * The SFace feature of one detected face, as OpenCV's FaceRecognizerSF computes it ({@code reference_pipeline.py},
 * section "Face binding"; OpenCV 5.0.0 {@code modules/objdetect/src/face_recognize.cpp}):
 * <ol>
 *   <li>a similarity transform from the 5 YuNet keypoints to the 112 x 112 ArcFace template, an Umeyama fit
 *       ({@link #transform}, face_recognize.cpp:79-189);</li>
 *   <li>{@code warpAffine} to 112 x 112, INTER_LINEAR, zero border, 8-bit
 *       ({@link ImageOps#warpAffineOpenCv}, :54);</li>
 *   <li>{@code blobFromImage(aligned, 1, 112 x 112, 0, swapRB=true)}: NCHW R, G, B, raw 0..255 (:58; Java frames are
 *       already RGB);</li>
 *   <li>the network: input "data" [1, 3, 112, 112], output "fc1" [1, 128], not normalised.</li>
 * </ol>
 * One instance per matcher: the input buffer is reused; not thread-safe.
 */
public final class SFaceEmbedder {

    public static final int SIZE = 112;
    public static final int FEATURE_LENGTH = 128;
    private static final String INPUT = "data";
    private static final String OUTPUT = "fc1";
    /** face_recognize.cpp:80: right eye, left eye, nose tip, right and left mouth corner (YuNet keypoint order). */
    static final float[][] TEMPLATE = {
            {38.2946f, 51.6963f}, {73.5318f, 51.5014f}, {56.0252f, 71.7366f}, {41.5493f, 92.3655f}, {70.7299f, 92.2041f}};
    /** face_recognize.cpp:85, as written in the source. */
    static final float[] TEMPLATE_MEAN = {56.0262f, 71.9008f};

    private final OrtEnvironment env;
    private final OrtSession session;
    private final float[] input = new float[3 * SIZE * SIZE];

    public SFaceEmbedder(OrtEnvironment env, OrtSession session) {
        if (env == null || session == null) throw new IllegalArgumentException("null argument");
        this.env = env;
        this.session = session;
    }

    /** The 128-value feature of {@code face} in the RGB888 frame. */
    public float[] embed(byte[] rgb, int width, int height, FaceDetection face) throws OrtException {
        ImageOps.warpAffineOpenCv(rgb, width, height, transform(face), SIZE, SIZE, input);
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, SIZE, SIZE});
             OrtSession.Result out = session.run(Collections.singletonMap(INPUT, tensor))) {
            for (Map.Entry<String, OnnxValue> e : out) {
                if (OUTPUT.equals(e.getKey())) {
                    FloatBuffer fb = ((OnnxTensor) e.getValue()).getFloatBuffer();
                    if (fb.remaining() != FEATURE_LENGTH) break;
                    float[] feature = new float[FEATURE_LENGTH];
                    fb.get(feature);
                    return feature;
                }
            }
        }
        throw new IllegalStateException("SFace output missing or malformed");
    }

    /** The last input tensor (parity tests). */
    float[] input() {
        return input;
    }

    /**
     * FaceRecognizerSFImpl::getSimilarityTransformMatrix (face_recognize.cpp:79-189): the 2 x 3 map (row major) from
     * frame pixel indices to template pixel indices. Points, means and de-meaned points in float, sums in double,
     * as the C++.
     */
    static double[] transform(FaceDetection face) {
        float[][] src = new float[5][2];
        for (int n = 0; n < 5; n++) {
            src[n][0] = (float) face.keypointX(n);
            src[n][1] = (float) face.keypointY(n);
        }
        float[] srcMean = new float[2];
        for (int k = 0; k < 2; k++) {
            float s = src[0][k];
            for (int n = 1; n < 5; n++) s = s + src[n][k];
            srcMean[k] = s / 5f;
        }
        float[][] srcDemean = new float[5][2], dstDemean = new float[5][2];
        for (int n = 0; n < 5; n++) {
            for (int k = 0; k < 2; k++) {
                srcDemean[n][k] = src[n][k] - srcMean[k];
                dstDemean[n][k] = TEMPLATE[n][k] - TEMPLATE_MEAN[k];
            }
        }
        double a00 = meanOfProducts(dstDemean, 0, srcDemean, 0), a01 = meanOfProducts(dstDemean, 0, srcDemean, 1);
        double a10 = meanOfProducts(dstDemean, 1, srcDemean, 0), a11 = meanOfProducts(dstDemean, 1, srcDemean, 1);
        double d1 = a00 * a11 - a01 * a10 < 0 ? -1 : 1;

        // A = U diag(s0, s1) Vt with s0 >= s1 >= 0 (closed form for 2 x 2): A = Rot(phi) diag(q + r, q - r) Rot(theta).
        double e = (a00 + a11) / 2, f = (a00 - a11) / 2, g = (a10 + a01) / 2, h = (a10 - a01) / 2;
        double q = Math.sqrt(e * e + h * h), r = Math.sqrt(f * f + g * g);
        double s0 = q + r, s1 = q - r;
        double alpha = Math.atan2(g, f), beta = Math.atan2(h, e);
        double theta = (beta - alpha) / 2, phi = (beta + alpha) / 2;
        double sign = s1 < 0 ? -1 : 1; // a negative second value moves its sign into U's second column
        s1 = Math.abs(s1);
        double cp = Math.cos(phi), sp = Math.sin(phi), ct = Math.cos(theta), st = Math.sin(theta);
        double[][] u = {{cp, -sp * sign}, {sp, cp * sign}};
        double[][] vt = {{ct, -st}, {st, ct}};

        double tol = Math.max(s0, s1) * 2 * Float.MIN_NORMAL;
        int rank = (s0 > tol ? 1 : 0) + (s1 > tol ? 1 : 0);
        double dr = d1;
        if (rank == 1) dr = det(u) * det(vt) > 0 ? 1 : -1; // :135-158 (degenerate: collinear keypoints)
        double[][] rot = {
                {u[0][0] * vt[0][0] + u[0][1] * dr * vt[1][0], u[0][0] * vt[0][1] + u[0][1] * dr * vt[1][1]},
                {u[1][0] * vt[0][0] + u[1][1] * dr * vt[1][0], u[1][0] * vt[0][1] + u[1][1] * dr * vt[1][1]}};
        double var = meanOfProducts(srcDemean, 0, srcDemean, 0) + meanOfProducts(srcDemean, 1, srcDemean, 1);
        double scale = 1.0 / var * (s0 + s1 * d1);
        double tx = rot[0][0] * srcMean[0] + rot[0][1] * srcMean[1];
        double ty = rot[1][0] * srcMean[0] + rot[1][1] * srcMean[1];
        return new double[] {
                scale * rot[0][0], scale * rot[0][1], TEMPLATE_MEAN[0] - scale * tx,
                scale * rot[1][0], scale * rot[1][1], TEMPLATE_MEAN[1] - scale * ty};
    }

    /** (1/5) sum of p[i][pc] * q[i][qc]: products in float, sum in double (face_recognize.cpp:103-115, :169-176). */
    private static double meanOfProducts(float[][] p, int pc, float[][] q, int qc) {
        double acc = 0;
        for (int i = 0; i < 5; i++) acc += p[i][pc] * q[i][qc];
        return acc / 5;
    }

    private static double det(double[][] m) {
        return m[0][0] * m[1][1] - m[0][1] * m[1][0];
    }

    /** FaceRecognizerSF::match, FR_COSINE (face_recognize.cpp:62-69): the dot product of the L2-normalised features. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            throw new IllegalArgumentException("features of different lengths");
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na > 0 && nb > 0 ? dot / Math.sqrt(na * nb) : Double.NaN;
    }
}
