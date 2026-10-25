package io.mosip.registration.liveness.analyzer.detect;

import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Java YuNet vs the Python reference on the golden PNGs (and their in-memory mirror images). */
class YuNetParityTest {

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    @SuppressWarnings("unchecked")
    void matchesTheReferencePipeline(String name) throws Exception {
        OnnxModels models = TestModels.shared();
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        YuNetDetector detector = new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), DetectorConfig.defaults());

        // Stage 1: the input tensor.
        float[] input = detector.prepare(img.rgb, img.width, img.height);
        Map<String, Object> yunet = MiniJson.getObject(golden, "yunet");
        assertThat(detector.paddedWidth()).isEqualTo(MiniJson.getInt(yunet, "paddedW"));
        assertThat(detector.paddedHeight()).isEqualTo(MiniJson.getInt(yunet, "paddedH"));
        Map<String, Object> stats = MiniJson.getObject(MiniJson.getObject(golden, "tensorStats"), "yunet");
        int n = 3 * detector.paddedWidth() * detector.paddedHeight();
        double meanDiff = Math.abs(GoldenFixtures.mean(input, n) - MiniJson.getDouble(stats, "mean"));
        assertThat(meanDiff).isLessThan(1e-3);
        double[] first8 = MiniJson.toDoubles(MiniJson.getArray(stats, "first8"));
        for (int i = 0; i < 8; i++) assertThat((double) input[i]).isCloseTo(first8[i], within(1e-3));

        // Stage 2: detections.
        List<FaceDetection> faces = detector.detect(img.rgb, img.width, img.height);
        List<Object> expected = MiniJson.getArray(golden, "detections");
        assertThat(faces).hasSameSizeAs(expected);
        double worstIou = 1, worstKp = 0, worstScore = 0;
        for (int i = 0; i < faces.size(); i++) {
            FaceDetection want = GoldenFixtures.detection((Map<String, Object>) expected.get(i));
            FaceDetection got = faces.get(i);
            double iou = FaceDetection.iou(got, want);
            assertThat(iou).as("%s face %d IoU", name, i).isGreaterThan(0.99);
            for (int k = 0; k < 5; k++) {
                double err = Math.hypot(got.keypointX(k) - want.keypointX(k), got.keypointY(k) - want.keypointY(k));
                assertThat(err).as("%s face %d kp %d", name, i, k).isLessThan(0.5);
                worstKp = Math.max(worstKp, err);
            }
            assertThat(got.score).isCloseTo(want.score, within(0.005));
            worstIou = Math.min(worstIou, iou);
            worstScore = Math.max(worstScore, Math.abs(got.score - want.score));
        }
        assertThat(YuNetDetector.countFaces(faces)).isEqualTo(MiniJson.getInt(golden, "faceCount"));
        System.out.printf("YuNet parity %-26s faces %d  min IoU %.6f  max kp err %.4f px  max score err %.2e  input mean err %.2e%n",
                name, faces.size(), worstIou, worstKp, worstScore, meanDiff);
    }
}
