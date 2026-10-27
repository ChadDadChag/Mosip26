package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig.Accelerator;
import io.mosip.registration.liveness.api.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzerConfigTest {

    private static ConfigProvider provider(final Map<String, String> values) {
        return new ConfigProvider() {
            @Override public String get(String key) {
                return values.get(key);
            }
        };
    }

    private static Map<String, String> keys(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(AnalyzerConfig.KEY_PREFIX + kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void meshTrackingIsOnUnlessSetToFalse() {
        assertThat(AnalyzerConfig.defaults().meshTracking).isTrue();
        AnalyzerConfig off = AnalyzerConfig.fromConfig(provider(keys("meshTracking", " FALSE ")));
        assertThat(off.meshTracking).isFalse();
        assertThat(off.toBuilder().build().meshTracking).isFalse();
        assertThat(AnalyzerConfig.fromConfig(provider(keys("meshTracking", "true"))).meshTracking).isTrue();
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("meshTracking", "yes"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("meshTracking");
    }

    @Test
    void theRelativeBlurRatioCanBeSetOrSwitchedOff() {
        assertThat(AnalyzerConfig.defaults().qualityRelativeBlurRatio).isEqualTo(0.45);
        assertThat(AnalyzerConfig.fromConfig(provider(keys("qualityRelativeBlurRatio", "0.3"))).qualityRelativeBlurRatio)
                .isEqualTo(0.3);
        AnalyzerConfig off = AnalyzerConfig.fromConfig(provider(keys("qualityRelativeBlurRatio", "off")));
        assertThat(off.qualityRelativeBlurRatio).isNaN();
        assertThat(off.toBuilder().build().qualityRelativeBlurRatio).isNaN();
        assertThatThrownBy(() -> AnalyzerConfig.builder().qualityRelativeBlurRatio(1.5).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("qualityRelativeBlurRatio");
    }

    @Test
    void theBindingThresholdHasItsOwnKey() {
        assertThat(AnalyzerConfig.defaults().bindingCosineThreshold).isEqualTo(0.45);
        Map<String, String> m = new HashMap<String, String>();
        m.put(AnalyzerConfig.BINDING_THRESHOLD_KEY, " 0.363 ");
        AnalyzerConfig c = AnalyzerConfig.fromConfig(provider(m));
        assertThat(c.bindingCosineThreshold).isEqualTo(0.363);
        assertThat(c.toBuilder().build().bindingCosineThreshold).isEqualTo(0.363);
        m.put(AnalyzerConfig.BINDING_THRESHOLD_KEY, "high");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(m)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(AnalyzerConfig.BINDING_THRESHOLD_KEY);
        assertThatThrownBy(() -> AnalyzerConfig.builder().bindingCosineThreshold(1.5).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cosineThreshold");
        assertThatThrownBy(() -> AnalyzerConfig.builder().bindingCosineThreshold(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsMatchTheReferencePipeline() {
        AnalyzerConfig c = AnalyzerConfig.defaults();
        assertThat(c.intraOpThreads).isEqualTo(2);
        assertThat(c.accelerator).isEqualTo(Accelerator.CPU);
        assertThat(c.detector.maxWidth).isEqualTo(320);
        assertThat(c.meshPresenceThreshold).isEqualTo(0.5);
        assertThat(c.meshRoiScale).isEqualTo(1.5);
        assertThat(c.pitchBiasDeg).isEqualTo(-9.5);
        assertThat(c.padMaxYawDeg).isEqualTo(30);
        assertThat(c.padMaxPitchDeg).isEqualTo(25);
        assertThat(c.tooDarkLuma).isEqualTo(40);
        assertThat(c.tooBrightLuma).isEqualTo(220);
        assertThat(c.blurLapVar).isEqualTo(15);
        assertThat(c.minAreaFrac).isEqualTo(0.04);
        assertThat(c.minFaceWidthPx).isEqualTo(80);
        assertThat(c.maxAreaFrac).isEqualTo(0.60);
        assertThat(c.maxCentreOffset).isEqualTo(0.25);
        assertThat(c.qualityMaxClippedFraction).isEqualTo(0.30);
        assertThat(c.moireCapThreshold).isEqualTo(1.40);
        assertThat(c.moireCapScore).isEqualTo(0.60);
        assertThat(c.moireWindow).isEqualTo(10);
        assertThat(c.moireMinFrames).isEqualTo(3);
        assertThat(c.moireVetoThreshold).isNaN();
        assertThat(AnalyzerConfig.fromConfig(null).blurLapVar).isEqualTo(15);
        assertThat(AnalyzerConfig.fromConfig(provider(new HashMap<String, String>())).padMaxYawDeg).isEqualTo(30);
    }

    @Test
    void readsEveryOptionalKey() {
        AnalyzerConfig c = AnalyzerConfig.fromConfig(provider(keys(
                "intraOpThreads", "4", "accelerator", " xnnpack ", "detectorMaxWidth", "480",
                "detectorScoreThreshold", "0.8", "detectorNmsIou", "0.4", "detectorSecondFaceMinAreaRatio", "0.3",
                "meshPresenceThreshold", "0.6", "meshRoiScale", "1.6", "pitchBiasDeg", "-8", "padMaxYawDeg", "35",
                "padMaxPitchDeg", "20", "tooDarkLuma", "30", "tooBrightLuma", "230", "blurLapVar", "12",
                "minAreaFrac", "0.03", "minFaceWidthPx", "90", "maxAreaFrac", "0.7", "maxCentreOffset", "0.3",
                "moireCapThreshold", "1.5", "moireCapScore", "0.5", "moireWindow", "12", "moireMinFrames", "4",
                "moireVetoThreshold", "1.8", "qualityMaxClippedFraction", "0.4", "unrelated", "x")));
        assertThat(c.intraOpThreads).isEqualTo(4);
        assertThat(c.accelerator).isEqualTo(Accelerator.XNNPACK);
        assertThat(c.detector.maxWidth).isEqualTo(480);
        assertThat(c.detector.scoreThreshold).isEqualTo(0.8);
        assertThat(c.detector.nmsIou).isEqualTo(0.4);
        assertThat(c.detector.secondFaceMinAreaRatio).isEqualTo(0.3);
        assertThat(c.meshPresenceThreshold).isEqualTo(0.6);
        assertThat(c.meshRoiScale).isEqualTo(1.6);
        assertThat(c.pitchBiasDeg).isEqualTo(-8);
        assertThat(c.padMaxYawDeg).isEqualTo(35);
        assertThat(c.padMaxPitchDeg).isEqualTo(20);
        assertThat(c.tooDarkLuma).isEqualTo(30);
        assertThat(c.tooBrightLuma).isEqualTo(230);
        assertThat(c.blurLapVar).isEqualTo(12);
        assertThat(c.minAreaFrac).isEqualTo(0.03);
        assertThat(c.minFaceWidthPx).isEqualTo(90);
        assertThat(c.maxAreaFrac).isEqualTo(0.7);
        assertThat(c.maxCentreOffset).isEqualTo(0.3);
        assertThat(c.moireCapThreshold).isEqualTo(1.5);
        assertThat(c.moireCapScore).isEqualTo(0.5);
        assertThat(c.moireWindow).isEqualTo(12);
        assertThat(c.moireMinFrames).isEqualTo(4);
        assertThat(c.moireVetoThreshold).isEqualTo(1.8);
        assertThat(c.qualityMaxClippedFraction).isEqualTo(0.4);
        AnalyzerConfig copy = c.toBuilder().build();
        assertThat(copy.qualityMaxClippedFraction).isEqualTo(0.4);
        assertThat(copy.blurLapVar).isEqualTo(12);
        assertThat(copy.accelerator).isEqualTo(Accelerator.XNNPACK);
        assertThat(copy.moireWindow).isEqualTo(12);
        assertThat(copy.moireVetoThreshold).isEqualTo(1.8);
    }

    @Test
    void moireThresholdsCanBeSwitchedOff() {
        AnalyzerConfig off = AnalyzerConfig.fromConfig(provider(keys("moireCapThreshold", " OFF ", "moireVetoThreshold", "none")));
        assertThat(off.moireCapThreshold).isNaN();
        assertThat(off.moireVetoThreshold).isNaN();
        assertThat(AnalyzerConfig.fromConfig(provider(keys("moireCapThreshold", "nan"))).moireCapThreshold).isNaN();
        assertThat(AnalyzerConfig.builder().moireCapThreshold(Double.NaN).build().toBuilder().build().moireCapThreshold).isNaN();
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("moireCapThreshold", "high"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(AnalyzerConfig.KEY_PREFIX + "moireCapThreshold");
    }

    @Test
    void badValuesNameTheKey() {
        String p = AnalyzerConfig.KEY_PREFIX;
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("intraOpThreads", "two"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(p + "intraOpThreads");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("blurLapVar", "abc"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(p + "blurLapVar");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("padMaxYawDeg", "NaN"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(p + "padMaxYawDeg");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("accelerator", "gpu"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(p + "accelerator");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("detectorMaxWidth", "16"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(p + "detectorMaxWidth");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("padMaxPitchDeg", "120"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(p + "padMaxPitchDeg");
    }

    @Test
    void validateRejectsOutOfRangeValues() {
        assertInvalid(AnalyzerConfig.builder().intraOpThreads(0), "intraOpThreads");
        assertInvalid(AnalyzerConfig.builder().accelerator(null), "accelerator");
        assertInvalid(AnalyzerConfig.builder().detector(null), "detector");
        assertInvalid(AnalyzerConfig.builder().detector(new io.mosip.registration.liveness.analyzer.detect.DetectorConfig(320, 1.5, 0.3, 0.2)), "detectorScoreThreshold");
        assertInvalid(AnalyzerConfig.builder().detector(new io.mosip.registration.liveness.analyzer.detect.DetectorConfig(320, 0.7, 0, 0.2)), "detectorNmsIou");
        assertInvalid(AnalyzerConfig.builder().detector(new io.mosip.registration.liveness.analyzer.detect.DetectorConfig(320, 0.7, 0.3, 2)), "detectorSecondFaceMinAreaRatio");
        assertInvalid(AnalyzerConfig.builder().meshPresenceThreshold(1), "meshPresenceThreshold");
        assertInvalid(AnalyzerConfig.builder().meshRoiScale(0.5), "meshRoiScale");
        assertInvalid(AnalyzerConfig.builder().pitchBiasDeg(45), "pitchBiasDeg");
        assertInvalid(AnalyzerConfig.builder().padMaxYawDeg(0), "padMaxYawDeg");
        assertInvalid(AnalyzerConfig.builder().padMaxPitchDeg(91), "padMaxPitchDeg");
        assertInvalid(AnalyzerConfig.builder().tooDarkLuma(-1), "tooDarkLuma");
        assertInvalid(AnalyzerConfig.builder().tooBrightLuma(300), "tooBrightLuma");
        assertInvalid(AnalyzerConfig.builder().tooDarkLuma(200).tooBrightLuma(100), "tooDarkLuma");
        assertInvalid(AnalyzerConfig.builder().blurLapVar(-1), "blurLapVar");
        assertInvalid(AnalyzerConfig.builder().minAreaFrac(2), "minAreaFrac");
        assertInvalid(AnalyzerConfig.builder().maxAreaFrac(0), "maxAreaFrac");
        assertInvalid(AnalyzerConfig.builder().minAreaFrac(0.5).maxAreaFrac(0.4), "minAreaFrac");
        assertInvalid(AnalyzerConfig.builder().minFaceWidthPx(-5), "minFaceWidthPx");
        assertInvalid(AnalyzerConfig.builder().maxCentreOffset(0.6), "maxCentreOffset");
        assertInvalid(AnalyzerConfig.builder().qualityMaxClippedFraction(0), "qualityMaxClippedFraction");
        assertInvalid(AnalyzerConfig.builder().qualityMaxClippedFraction(1.5), "qualityMaxClippedFraction");
        assertInvalid(AnalyzerConfig.builder().moireCapThreshold(0), "moireCapThreshold");
        assertInvalid(AnalyzerConfig.builder().moireCapScore(0), "moireCapScore");
        assertInvalid(AnalyzerConfig.builder().moireCapScore(1), "moireCapScore");
        assertInvalid(AnalyzerConfig.builder().moireMinFrames(0), "moireMinFrames");
        assertInvalid(AnalyzerConfig.builder().moireWindow(2), "moireWindow"); // below moireMinFrames 3
        assertInvalid(AnalyzerConfig.builder().moireVetoThreshold(-1), "moireVetoThreshold");
    }

    @Test
    void thePrintGuardsAreOnByDefaultAndCanBeSetOrSwitchedOff() {
        AnalyzerConfig d = AnalyzerConfig.defaults();
        assertThat(d.padLatchThreshold).isEqualTo(0.30);
        assertThat(d.padLatchCapScore).isEqualTo(0.60);
        assertThat(d.volatilityMinScore).isEqualTo(0.85);
        assertThat(d.volatilityCapScore).isEqualTo(0.60);
        AnalyzerConfig set = AnalyzerConfig.fromConfig(provider(keys("padLatchThreshold", "0.25", "padLatchCapScore",
                "0.65", "volatilityMinScore", "0.9", "volatilityCapScore", "0.5")));
        assertThat(set.padLatchThreshold).isEqualTo(0.25);
        assertThat(set.padLatchCapScore).isEqualTo(0.65);
        assertThat(set.volatilityMinScore).isEqualTo(0.9);
        assertThat(set.volatilityCapScore).isEqualTo(0.5);
        assertThat(set.toBuilder().build().volatilityCapScore).isEqualTo(0.5);
        AnalyzerConfig off = AnalyzerConfig.fromConfig(provider(keys("padLatchThreshold", "off", "volatilityMinScore",
                "OFF")));
        assertThat(off.padLatchThreshold).isNaN();
        assertThat(off.volatilityMinScore).isNaN();
        assertThat(off.toBuilder().build().padLatchThreshold).isNaN();

        assertInvalid(AnalyzerConfig.builder().padLatchThreshold(0), "padLatchThreshold");
        assertInvalid(AnalyzerConfig.builder().padLatchThreshold(1), "padLatchThreshold");
        assertInvalid(AnalyzerConfig.builder().padLatchCapScore(1), "padLatchCapScore");
        // The cap must stay above the latch threshold: a latched person goes to a challenge, never to PAD_FAILURE.
        assertInvalid(AnalyzerConfig.builder().padLatchThreshold(0.5).padLatchCapScore(0.4), "padLatchCapScore");
        assertThat(AnalyzerConfig.builder().padLatchThreshold(Double.NaN).padLatchCapScore(0.2).build().padLatchCapScore)
                .isEqualTo(0.2);
        assertInvalid(AnalyzerConfig.builder().volatilityMinScore(0), "volatilityMinScore");
        assertInvalid(AnalyzerConfig.builder().volatilityMinScore(1.2), "volatilityMinScore");
        assertInvalid(AnalyzerConfig.builder().volatilityCapScore(0), "volatilityCapScore");
        assertThatThrownBy(() -> AnalyzerConfig.fromConfig(provider(keys("volatilityCapScore", "high"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("volatilityCapScore");
    }

    private static void assertInvalid(AnalyzerConfig.Builder b, String key) {
        assertThatThrownBy(b::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AnalyzerConfig.KEY_PREFIX + key);
    }
}
