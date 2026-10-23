package io.mosip.registration.liveness.analyzer.pad;

import io.mosip.registration.liveness.analyzer.detect.DetectorConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Java MiniFASNet PAD vs the Python reference on the golden PNGs (and their in-memory mirror images). */
class SilentFacePadParityTest {

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    void matchesTheReferencePipeline(String name) throws Exception {
        OnnxModels models = TestModels.shared();
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        Map<String, Object> pad = MiniJson.getObject(golden, "pad");
        SilentFacePad sfp = new SilentFacePad(models.env(), models.session(ModelRole.PAD_V2), models.session(ModelRole.PAD_V1SE));

        // Isolated from the detector: the reference's own primary face box.
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(golden, "primary"));
        assertThat(SilentFacePad.intBox(primary)).containsExactly(toInts(MiniJson.getArray(pad, "intBox")));
        PadResult r = sfp.assess(img.rgb, img.width, img.height, primary);

        double worst = 0;
        for (String key : new String[] {"v2", "v1se"}) {
            Map<String, Object> want = MiniJson.getObject(pad, key);
            int[] box = key.equals("v2") ? r.v2Box : r.v1seBox;
            float[] sm = key.equals("v2") ? r.v2 : r.v1se;
            assertThat(box).as("%s %s crop box", name, key).containsExactly(toInts(MiniJson.getArray(want, "cropBox")));
            double[] wantSm = MiniJson.toDoubles(MiniJson.getArray(want, "softmax"));
            for (int i = 0; i < 3; i++) {
                assertThat((double) sm[i]).as("%s %s softmax[%d]", name, key, i).isCloseTo(wantSm[i], within(0.005));
                worst = Math.max(worst, Math.abs(sm[i] - wantSm[i]));
            }
            // Input tensor of this model.
            Map<String, Object> stats = MiniJson.getObject(MiniJson.getObject(golden, "tensorStats"), "pad_" + key);
            float[] input = sfp.prepare(img.rgb, img.width, img.height, box);
            assertThat(GoldenFixtures.mean(input, input.length)).isCloseTo(MiniJson.getDouble(stats, "mean"), within(1e-3));
        }
        double wantScore = MiniJson.getDouble(golden, "livenessScore");
        assertThat((double) r.score).isCloseTo(wantScore, within(0.005));

        // End to end: Java detector -> Java PAD.
        YuNetDetector detector = new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), DetectorConfig.defaults());
        PadResult e2e = sfp.assess(img.rgb, img.width, img.height, YuNetDetector.primary(detector.detect(img.rgb, img.width, img.height)));
        assertThat((double) e2e.score).isCloseTo(wantScore, within(0.005));
        System.out.printf("PAD parity %-26s max softmax err %.2e  score err %.2e  end-to-end score err %.2e  same crop boxes e2e: %b%n",
                name, worst, Math.abs(r.score - wantScore), Math.abs(e2e.score - wantScore),
                java.util.Arrays.equals(e2e.v2Box, r.v2Box) && java.util.Arrays.equals(e2e.v1seBox, r.v1seBox));
    }

    private static int[] toInts(java.util.List<Object> values) throws Exception {
        double[] d = MiniJson.toDoubles(values);
        int[] out = new int[d.length];
        for (int i = 0; i < d.length; i++) out[i] = MiniJson.toInt(d[i], "box");
        return out;
    }
}
