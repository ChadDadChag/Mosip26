package io.mosip.registration.liveness.analyzer.detect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Decoding and NMS on hand-built YuNet outputs for a 32 x 32 padded input (16 + 4 + 1 anchors). */
class YuNetDecodeTest {

    private static Map<String, float[]> emptyOutputs() {
        Map<String, float[]> out = new HashMap<String, float[]>();
        for (int stride : YuNetDetector.STRIDES) {
            int anchors = (32 / stride) * (32 / stride);
            out.put("cls_" + stride, new float[anchors]);
            out.put("obj_" + stride, new float[anchors]);
            out.put("bbox_" + stride, new float[4 * anchors]);
            out.put("kps_" + stride, new float[10 * anchors]);
        }
        return out;
    }

    /** Puts a candidate at (row, col) of a stride with cls = obj = score (so sqrt(cls * obj) = score). */
    private static void put(Map<String, float[]> out, int stride, int r, int c, float score, float[] bbox, float kpOffset) {
        int cols = 32 / stride, idx = r * cols + c;
        out.get("cls_" + stride)[idx] = score;
        out.get("obj_" + stride)[idx] = score;
        System.arraycopy(bbox, 0, out.get("bbox_" + stride), 4 * idx, 4);
        for (int k = 0; k < 10; k++) out.get("kps_" + stride)[10 * idx + k] = kpOffset;
    }

    @Test
    void decodesBoxesAndKeypointsLikeOpenCv() {
        Map<String, float[]> out = emptyOutputs();
        // stride 8, row 1, col 2: cx = (2 + 0.5) 8 = 20, cy = (1 + 0.25) 8 = 10, w = e^ln4 8 = 32, h = e^ln2 8 = 16
        put(out, 8, 1, 2, 0.9f, new float[] {0.5f, 0.25f, (float) Math.log(4), (float) Math.log(2)}, 0.5f);
        List<FaceDetection> faces = YuNetDetector.decode(out, 32, 32, DetectorConfig.defaults());
        assertThat(faces).hasSize(1);
        FaceDetection f = faces.get(0);
        assertThat(f.x).isCloseTo(20 - 16, within(1e-5));
        assertThat(f.y).isCloseTo(10 - 8, within(1e-5));
        assertThat(f.w).isCloseTo(32, within(1e-4));
        assertThat(f.h).isCloseTo(16, within(1e-4));
        assertThat(f.score).isCloseTo(0.9, within(1e-6));
        // keypoint n = ((k + c) * s, (k + r) * s) with k = 0.5
        assertThat(f.keypointX(3)).isCloseTo((0.5 + 2) * 8, within(1e-6));
        assertThat(f.keypointY(3)).isCloseTo((0.5 + 1) * 8, within(1e-6));
        assertThat(f.area()).isCloseTo(512, within(1e-2));
        assertThat(f.toBox()).hasSize(4);
    }

    @Test
    void clampsScoresAndAppliesTheThreshold() {
        Map<String, float[]> out = emptyOutputs();
        put(out, 32, 0, 0, 0.69f, new float[] {0.5f, 0.5f, 0f, 0f}, 0f); // below 0.7
        out.get("cls_16")[0] = 1.7f; // clamped to 1 -> sqrt(1 * 0.81) = 0.9
        out.get("obj_16")[0] = 0.81f;
        out.get("cls_16")[1] = -3f;  // clamped to 0
        out.get("obj_16")[1] = 1f;
        List<FaceDetection> faces = YuNetDetector.decode(out, 32, 32, DetectorConfig.defaults());
        assertThat(faces).hasSize(1);
        assertThat(faces.get(0).score).isCloseTo(0.9, within(1e-6));
    }

    @Test
    void nmsKeepsTheHigherScoreAndSortsByScore() {
        Map<String, float[]> out = emptyOutputs();
        float[] big = {0.5f, 0.5f, (float) Math.log(2), (float) Math.log(2)}; // 16 x 16 at stride 8
        put(out, 8, 1, 1, 0.80f, big, 0f);
        put(out, 8, 1, 2, 0.95f, big, 0f);   // overlaps the first one, higher score: kept
        put(out, 32, 0, 0, 0.75f, new float[] {0.5f, 0.5f, -2f, -2f}, 0f); // tiny, separate
        List<FaceDetection> faces = YuNetDetector.decode(out, 32, 32, DetectorConfig.defaults());
        assertThat(faces).hasSize(2);
        assertThat(faces.get(0).score).isCloseTo(0.95, within(1e-6));
        assertThat(faces.get(1).score).isCloseTo(0.75, within(1e-6));
    }

    @Test
    void missingOrShortOutputsAreRejected() {
        Map<String, float[]> out = emptyOutputs();
        out.remove("kps_32");
        assertThatThrownBy(() -> YuNetDetector.decode(out, 32, 32, DetectorConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kps_32");
        Map<String, float[]> shortOut = emptyOutputs();
        shortOut.put("cls_8", new float[3]);
        assertThatThrownBy(() -> YuNetDetector.decode(shortOut, 32, 32, DetectorConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countFacesUsesTheAreaRatioAgainstTheLargestFace() {
        List<FaceDetection> faces = new ArrayList<FaceDetection>(Arrays.asList(
                face(0, 100, 0.9), face(200, 50, 0.95), face(400, 44, 0.8)));
        // areas 10000, 2500 (0.25 x), 1936 (0.19 x)
        assertThat(YuNetDetector.countFaces(faces)).isEqualTo(2);
        assertThat(YuNetDetector.countFaces(faces, 0.1)).isEqualTo(3);
        assertThat(YuNetDetector.primary(faces).x).isEqualTo(0);
        assertThat(YuNetDetector.countFaces(new ArrayList<FaceDetection>())).isZero();
        assertThat(YuNetDetector.primary(new ArrayList<FaceDetection>())).isNull();
    }

    @Test
    void geometryHelpers() {
        assertThat(FaceDetection.iou(face(0, 10, 1), face(5, 10, 1))).isCloseTo(50.0 / 150.0, within(1e-12));
        assertThat(FaceDetection.iou(face(0, 0, 1), face(5, 0, 1))).isZero();
        assertThat(YuNetDetector.roundHalfUp(2.5)).isEqualTo(3);
        assertThat(YuNetDetector.roundHalfUp(-0.5)).isEqualTo(0);
        assertThatThrownBy(() -> new FaceDetection(0, 0, 1, 1, 1, new double[4])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DetectorConfig(16, 0.7, 0.3, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new YuNetDetector(null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static FaceDetection face(double x, double side, double score) {
        return new FaceDetection(x, 0, side, side, score, new double[10]);
    }
}
