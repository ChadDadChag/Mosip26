package io.mosip.registration.liveness.analyzer.mesh;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.core.detect.FaceGeometry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Named contract landmarks and EAR (computed by Part 3's FaceGeometry) vs the Python reference. */
class MeshIndicesTest {

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    void namedPointsAndEarMatchTheReference(String name) throws Exception {
        OnnxModels models = TestModels.shared();
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(golden, "primary"));
        MeshResult r = new FaceMeshLandmarker(models.env(), models.session(ModelRole.LANDMARKS), AnalyzerConfig.defaults())
                .estimate(img.rgb, img.width, img.height, primary);
        FaceLandmarks l = MeshIndices.toFaceLandmarks(r.points);
        Map<String, Object> want = MiniJson.getObject(golden, "landmarks");

        assertClose(l.leftEye, GoldenFixtures.points(MiniJson.getArray(want, "leftEye")));
        assertClose(l.rightEye, GoldenFixtures.points(MiniJson.getArray(want, "rightEye")));
        for (String key : new String[] {"mouthLeft", "mouthRight", "mouthUpperInner", "mouthLowerInner", "noseTip", "chin",
                "leftIris", "rightIris"}) {
            assertClose(new float[][] {field(l, key)}, new double[][] {MiniJson.toDoubles(MiniJson.getArray(want, key))});
        }
        assertThat(l.leftIris).isNotNull();
        assertThat(l.rightIris).isNotNull();
        assertThat((double) FaceGeometry.eyeAspectRatio(l.leftEye)).isCloseTo(MiniJson.getDouble(golden, "earLeft"), within(1e-3));
        assertThat((double) FaceGeometry.eyeAspectRatio(l.rightEye)).isCloseTo(MiniJson.getDouble(golden, "earRight"), within(1e-3));
    }

    private static float[] field(FaceLandmarks l, String key) {
        switch (key) {
            case "mouthLeft": return l.mouthLeft;
            case "mouthRight": return l.mouthRight;
            case "mouthUpperInner": return l.mouthUpperInner;
            case "mouthLowerInner": return l.mouthLowerInner;
            case "noseTip": return l.noseTip;
            case "chin": return l.chin;
            case "leftIris": return l.leftIris;
            default: return l.rightIris;
        }
    }

    private static void assertClose(float[][] got, double[][] want) {
        assertThat(got.length).isEqualTo(want.length);
        for (int i = 0; i < want.length; i++) {
            assertThat(got[i]).hasSize(2);
            assertThat((double) got[i][0]).isCloseTo(want[i][0], within(0.05));
            assertThat((double) got[i][1]).isCloseTo(want[i][1], within(0.05));
        }
    }
}
