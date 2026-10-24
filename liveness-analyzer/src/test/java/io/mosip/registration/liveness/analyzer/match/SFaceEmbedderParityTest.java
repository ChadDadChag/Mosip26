package io.mosip.registration.liveness.analyzer.match;

import io.mosip.registration.liveness.analyzer.detect.DetectorConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Java SFace vs the Python reference ({@code golden/binding.json}, made by make_golden.py, whose features match
 * OpenCV's FaceRecognizerSF at cosine >= 0.9999995).
 */
class SFaceEmbedderParityTest {

    static Map<String, Object> golden;
    static List<Map<String, Object>> faces;
    static SFaceEmbedder embedder;
    static YuNetDetector detector;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        golden = GoldenFixtures.json("binding");
        faces = new ArrayList<Map<String, Object>>();
        for (Object o : MiniJson.getArray(golden, "faces")) faces.add((Map<String, Object>) o);
        OnnxModels models = TestModels.shared();
        embedder = new SFaceEmbedder(models.env(), models.session(ModelRole.MATCHER));
        detector = new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), DetectorConfig.defaults());
    }

    static GoldenFixtures.Image image(Map<String, Object> face) throws Exception {
        String png = MiniJson.getString(face, "image");
        String name = png.substring(0, png.length() - 4) + (Boolean.TRUE.equals(face.get("flipped")) ? "_flipped" : "");
        return GoldenFixtures.image(name);
    }

    static float[] feature(Map<String, Object> face) throws Exception {
        double[] d = MiniJson.toDoubles(MiniJson.getArray(face, "feature"));
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
        return f;
    }

    @Test
    void sameDetectionGivesTheReferenceFeature() throws Exception {
        assertThat(faces).hasSize(6);
        double worst = 1;
        for (Map<String, Object> face : faces) {
            GoldenFixtures.Image img = image(face);
            FaceDetection det = GoldenFixtures.detection(face);
            float[] got = embedder.embed(img.rgb, img.width, img.height, det);
            double cos = SFaceEmbedder.cosine(got, feature(face));
            assertThat(cos).as("%s feature cosine with Python", face.get("id")).isGreaterThanOrEqualTo(0.9999);
            worst = Math.min(worst, cos);
            // The aligned input: same pixels up to rounding.
            Map<String, Object> stats = MiniJson.getObject(face, "alignedStats");
            float[] input = embedder.input();
            assertThat(GoldenFixtures.mean(input, input.length)).isCloseTo(MiniJson.getDouble(stats, "mean"), within(0.05));
        }
        System.out.printf("SFace parity (golden detection): worst feature cosine %.7f%n", worst);
    }

    @Test
    @SuppressWarnings("unchecked")
    void javaDetectionGivesTheReferenceFeaturesAndSimilarities() throws Exception {
        Map<String, float[]> byId = new LinkedHashMap<String, float[]>();
        double worst = 1;
        for (Map<String, Object> face : faces) {
            GoldenFixtures.Image img = image(face);
            FaceDetection want = GoldenFixtures.detection(face);
            FaceDetection best = null;
            for (FaceDetection d : detector.detect(img.rgb, img.width, img.height)) {
                if (best == null || FaceDetection.iou(d, want) > FaceDetection.iou(best, want)) best = d;
            }
            assertThat(best).isNotNull();
            assertThat(FaceDetection.iou(best, want)).isGreaterThan(0.99);
            float[] got = embedder.embed(img.rgb, img.width, img.height, best);
            double cos = SFaceEmbedder.cosine(got, feature(face));
            assertThat(cos).as("%s end to end", face.get("id")).isGreaterThanOrEqualTo(0.9999);
            worst = Math.min(worst, cos);
            byId.put(MiniJson.getString(face, "id"), got);
        }
        double worstSim = 0;
        Map<String, Object> cosines = MiniJson.getObject(golden, "cosines");
        for (Map.Entry<String, Object> e : cosines.entrySet()) {
            String[] ids = e.getKey().split("~");
            double sim = SFaceEmbedder.cosine(byId.get(ids[0]), byId.get(ids[1]));
            assertThat(sim).as(e.getKey()).isCloseTo((Double) e.getValue(), within(1e-3));
            worstSim = Math.max(worstSim, Math.abs(sim - (Double) e.getValue()));
        }
        System.out.printf("SFace parity (Java YuNet): worst feature cosine %.7f, worst similarity difference %.2e%n",
                worst, worstSim);
    }

    @Test
    void theTransformMapsTheKeypointsOntoTheTemplate() throws Exception {
        for (Map<String, Object> face : faces) {
            FaceDetection det = GoldenFixtures.detection(face);
            double[] m = SFaceEmbedder.transform(det);
            assertThat(m[0]).as("no reflection, uniform scale").isCloseTo(m[4], within(1e-9));
            assertThat(m[1]).isCloseTo(-m[3], within(1e-9));
            for (int n = 0; n < 5; n++) {
                double x = m[0] * det.keypointX(n) + m[1] * det.keypointY(n) + m[2];
                double y = m[3] * det.keypointX(n) + m[4] * det.keypointY(n) + m[5];
                double err = Math.hypot(x - SFaceEmbedder.TEMPLATE[n][0], y - SFaceEmbedder.TEMPLATE[n][1]);
                assertThat(err).as("%s keypoint %d lands near the template", face.get("id"), n).isLessThan(8);
            }
        }
    }

    @Test
    void cosineHandlesDegenerateInput() {
        assertThat(SFaceEmbedder.cosine(new float[] {1, 0}, new float[] {2, 0})).isCloseTo(1, within(1e-12));
        assertThat(SFaceEmbedder.cosine(new float[] {1, 0}, new float[] {0, 3})).isCloseTo(0, within(1e-12));
        assertThat(SFaceEmbedder.cosine(new float[] {0, 0}, new float[] {1, 0})).isNaN();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SFaceEmbedder.cosine(new float[] {1}, new float[] {1, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
