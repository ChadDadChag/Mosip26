package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.detect.DetectorConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.pad.SilentFacePad;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/** Prints per-call timings for the detector and PAD (no assertion: machines differ). The benchmark is Phase 5. */
class TimingSmokeTest {

    private static final int WARMUP = 5, RUNS = 30;

    @Test
    void printTimings() throws Exception {
        OnnxModels models = TestModels.shared();
        YuNetDetector detector = new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), DetectorConfig.defaults());
        SilentFacePad pad = new SilentFacePad(models.env(), models.session(ModelRole.PAD_V2), models.session(ModelRole.PAD_V1SE));
        for (String name : new String[] {"portrait_frontal", "crew_multi"}) {
            GoldenFixtures.Image img = GoldenFixtures.image(name);
            FaceDetection face = YuNetDetector.primary(detector.detect(img.rgb, img.width, img.height));
            double[] detectMs = new double[RUNS], padMs = new double[RUNS];
            for (int i = -WARMUP; i < RUNS; i++) {
                long t0 = System.nanoTime();
                detector.detect(img.rgb, img.width, img.height);
                long t1 = System.nanoTime();
                pad.assess(img.rgb, img.width, img.height, face);
                long t2 = System.nanoTime();
                if (i >= 0) {
                    detectMs[i] = (t1 - t0) / 1e6;
                    padMs[i] = (t2 - t1) / 1e6;
                }
            }
            System.out.printf("Timing %-16s %dx%d  detect p50 %.2f ms p95 %.2f ms | PAD (both models) p50 %.2f ms p95 %.2f ms%n",
                    name, img.width, img.height, pct(detectMs, 50), pct(detectMs, 95), pct(padMs, 50), pct(padMs, 95));
        }
    }

    private static double pct(double[] values, int p) {
        double[] s = values.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.ceil(p / 100.0 * s.length) - 1)];
    }
}
