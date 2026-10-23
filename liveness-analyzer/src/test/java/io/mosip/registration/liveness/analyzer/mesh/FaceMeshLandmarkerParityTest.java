package io.mosip.registration.liveness.analyzer.mesh;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Java FaceMesh V2 vs the Python reference, on the reference's own primary face box. */
class FaceMeshLandmarkerParityTest {

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    void matchesTheReferencePipeline(String name) throws Exception {
        OnnxModels models = TestModels.shared();
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(golden, "primary"));
        FaceMeshLandmarker lm = new FaceMeshLandmarker(models.env(), models.session(ModelRole.LANDMARKS), AnalyzerConfig.defaults());

        MeshResult r = lm.estimate(img.rgb, img.width, img.height, primary);
        assertThat(r).isNotNull();
        Map<String, Object> roi = MiniJson.getObject(golden, "meshRoi");
        assertThat(r.cx).isCloseTo(MiniJson.getDouble(roi, "cx"), within(1e-6));
        assertThat(r.cy).isCloseTo(MiniJson.getDouble(roi, "cy"), within(1e-6));
        assertThat(r.side).isCloseTo(MiniJson.getDouble(roi, "S"), within(1e-6));
        assertThat(r.theta).isCloseTo(MiniJson.getDouble(roi, "theta"), within(1e-6));
        assertThat((double) r.presence).isCloseTo(MiniJson.getDouble(golden, "meshFlag"), within(1e-4));

        double meanDiff = Math.abs(GoldenFixtures.mean(lm.input(), lm.input().length)
                - MiniJson.getDouble(MiniJson.getObject(MiniJson.getObject(golden, "tensorStats"), "mesh"), "mean"));
        assertThat(meanDiff).isLessThan(1e-6);

        double[][] want = GoldenFixtures.points(MiniJson.getArray(golden, "meshPoints"));
        assertThat(want.length).isEqualTo(MeshResult.POINTS);
        double dx = 0, dy = 0, dz = 0;
        for (int i = 0; i < MeshResult.POINTS; i++) {
            dx = Math.max(dx, Math.abs(r.points[i][0] - want[i][0]));
            dy = Math.max(dy, Math.abs(r.points[i][1] - want[i][1]));
            dz = Math.max(dz, Math.abs(r.points[i][2] - want[i][2]));
        }
        assertThat(dx).isLessThan(0.05);
        assertThat(dy).isLessThan(0.05);
        assertThat(dz).isLessThan(0.1);
        System.out.printf("Mesh parity %-26s max |dx| %.2e |dy| %.2e |dz| %.2e px  presence err %.2e  input mean err %.2e%n",
                name, dx, dy, dz, Math.abs(r.presence - MiniJson.getDouble(golden, "meshFlag")), meanDiff);
    }

    @Test
    void returnsNullBelowThePresenceThresholdButKeepsTheRoi() throws Exception {
        OnnxModels models = TestModels.shared();
        GoldenFixtures.Image img = GoldenFixtures.image("portrait_frontal");
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(GoldenFixtures.json("portrait_frontal"), "primary"));
        AnalyzerConfig strict = AnalyzerConfig.builder().meshPresenceThreshold(0.999999999).build();
        FaceMeshLandmarker lm = new FaceMeshLandmarker(models.env(), models.session(ModelRole.LANDMARKS), strict);
        assertThat(lm.estimate(img.rgb, img.width, img.height, primary)).isNull();
        assertThat(lm.lastResult().side).isGreaterThan(0);
        assertThatThrownBy(() -> new FaceMeshLandmarker(null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
