package io.mosip.registration.liveness.analyzer.runtime;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig.Accelerator;
import io.mosip.registration.liveness.analyzer.detect.DetectorConfig;
import io.mosip.registration.liveness.analyzer.detect.YuNetDetector;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxModelsAcceleratorTest {

    private static int faces(OnnxModels models) throws Exception {
        GoldenFixtures.Image img = GoldenFixtures.image("crew_multi");
        return new YuNetDetector(models.env(), models.session(ModelRole.DETECTOR), DetectorConfig.defaults())
                .detect(img.rgb, img.width, img.height).size();
    }

    @Test
    void cpuWorks() throws Exception {
        try (OnnxModels m = OnnxModels.create(TestModels.load(), AnalyzerConfig.defaults())) {
            assertThat(m.activeAccelerator()).isEqualTo(Accelerator.CPU);
            assertThat(faces(m)).isEqualTo(4);
        }
    }

    @Test
    void nnapiOnTheDesktopFallsBackToCpu() throws Exception {
        AnalyzerConfig cfg = AnalyzerConfig.builder().accelerator(Accelerator.NNAPI).build();
        try (OnnxModels m = OnnxModels.create(TestModels.load(), cfg)) {
            assertThat(m.activeAccelerator()).isEqualTo(Accelerator.CPU);
            assertThat(faces(m)).isEqualTo(4);
        }
    }

    @Test
    void xnnpackIsUsedWhenAvailableAndOtherwiseFallsBack() throws Exception {
        AnalyzerConfig cfg = AnalyzerConfig.builder().accelerator(Accelerator.XNNPACK).build();
        try (OnnxModels m = OnnxModels.create(TestModels.load(), cfg)) {
            assertThat(m.activeAccelerator()).isIn(Accelerator.XNNPACK, Accelerator.CPU);
            assertThat(faces(m)).isEqualTo(4);
            System.out.println("XNNPACK requested on this machine -> active accelerator " + m.activeAccelerator());
        }
    }

    @Test
    void nullConfigIsRejected() {
        assertThatThrownBy(() -> OnnxModels.create(TestModels.load(), (AnalyzerConfig) null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
