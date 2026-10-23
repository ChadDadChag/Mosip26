package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEngineException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OnnxFaceAnalyzerTest {

    private static OnnxFaceAnalyzer analyzer(AnalyzerConfig cfg) throws Exception {
        return new OnnxFaceAnalyzer(TestModels.shared(), cfg, "test-version");
    }

    private static Frame frame(String name) throws Exception {
        return GoldenFixtures.frame(GoldenFixtures.image(name));
    }

    @Test
    void portraitIsUsableWithAScoreThatMatchesTheReference() throws Exception {
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            FrameAnalysis r = a.analyze(frame("portrait_frontal"));
            assertThat(r.faceCount).isEqualTo(1);
            assertThat(r.faceBox).hasSize(4);
            assertThat(r.landmarks).isNotNull();
            assertThat(r.landmarks.leftIris).isNotNull();
            assertThat(r.landmarks.rightIris).isNotNull();
            assertThat(Math.abs(r.yawDeg)).isLessThan(10);
            assertThat(Math.abs(r.pitchDeg)).isLessThan(10);
            assertThat(r.qualityIssues).isEmpty();
            assertThat(r.usable()).isTrue();
            double want = MiniJson.getDouble(GoldenFixtures.json("portrait_frontal"), "livenessScore");
            assertThat((double) r.livenessScore).isCloseTo(want, within(1e-4));
            assertThat(r.padDetails.keySet()).containsExactly("minifas_v2_live", "minifas_v1se_live", "minifas_v2_c0",
                    "minifas_v2_c2", "minifas_v1se_spoof", "moire", "moire_med", "moire_cap");
            // One golden frame: moire 1.25 is below the 1.40 cap, the window has one value, so the score is unchanged.
            assertThat(r.padDetails.get("moire")).isLessThan(1.40f);
            assertThat(r.padDetails.get("moire_med")).isEqualTo(r.padDetails.get("moire"));
            assertThat(r.padDetails.get("moire_cap")).isZero();
            assertThat(a.lastReplayCues()).isNotNull();
            assertThat(a.lastReplayCues().moire).isEqualTo(r.padDetails.get("moire"));
            assertThat(a.lastReplayCues().glare).isBetween(0f, 1f);
            assertThat(r.processingMs).isGreaterThanOrEqualTo(0);
            assertThat(a.modelVersion()).isEqualTo("test-version");
            AnalyzerStats s = a.stats();
            assertThat(s.frames()).isEqualTo(1);
            assertThat(s.framesOneFace()).isEqualTo(1);
            assertThat(s.lastDetectNs()).isPositive();
            assertThat(s.lastMeshNs()).isPositive();
            assertThat(s.lastPoseNs()).isPositive();
            assertThat(s.lastQualityNs()).isPositive();
            assertThat(s.lastPadNs()).isPositive();
            assertThat(s.lastCuesNs()).isPositive();
            assertThat(s.lastTotalNs()).isGreaterThanOrEqualTo(s.lastDetectNs() + s.lastPadNs());
            System.out.printf("Portrait FrameAnalysis: box %s yaw %.2f pitch %.2f roll %.2f issues %s score %.6f processingMs %d%n"
                    + "  padDetails %s%n", Arrays.toString(r.faceBox), r.yawDeg, r.pitchDeg, r.rollDeg, r.qualityIssues,
                    r.livenessScore, r.processingMs, r.padDetails);
        }
    }

    @Test
    void severalFacesGiveTheBoxOnly() throws Exception {
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            FrameAnalysis r = a.analyze(frame("crew_multi"));
            assertThat(r.faceCount).isEqualTo(4);
            assertThat(r.faceBox).hasSize(4);
            assertThat(r.landmarks).isNull();
            assertThat(r.livenessScore).isNaN();
            assertThat(r.yawDeg).isNaN();
            assertThat(r.padDetails).isEmpty();
            assertThat(r.qualityIssues).isEmpty();
            assertThat(a.stats().framesMultiFace()).isEqualTo(1);
        }
    }

    @Test
    void aUniformFrameHasNoFace() throws Exception {
        byte[] grey = new byte[320 * 240 * 3];
        Arrays.fill(grey, (byte) 128);
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            FrameAnalysis r = a.analyze(new Frame(grey, 320, 240, 0L, 0L, "test"));
            assertThat(r.faceCount).isZero();
            assertThat(r.faceBox).isNull();
            assertThat(r.landmarks).isNull();
            assertThat(r.livenessScore).isNaN();
            assertThat(r.padDetails).isEmpty();
            assertThat(a.stats().framesNoFace()).isEqualTo(1);
        }
    }

    @Test
    void poseGate() throws Exception {
        AnalyzerConfig d = AnalyzerConfig.defaults();
        assertThat(OnnxFaceAnalyzer.poseGated(29f, 24f, d)).isFalse();
        assertThat(OnnxFaceAnalyzer.poseGated(-31f, 0f, d)).isTrue();
        assertThat(OnnxFaceAnalyzer.poseGated(0f, 26f, d)).isTrue();
        assertThat(OnnxFaceAnalyzer.poseGated(Float.NaN, 0f, d)).isTrue();
        assertThat(OnnxFaceAnalyzer.poseGated(0f, Float.NaN, d)).isTrue();
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.builder().padMaxYawDeg(0.001).build())) {
            FrameAnalysis r = a.analyze(frame("portrait_frontal"));
            assertThat(r.faceCount).isEqualTo(1);
            assertThat(r.landmarks).isNotNull();
            assertThat(r.yawDeg).isNotNaN();
            assertThat(r.livenessScore).isNaN();
            assertThat(r.padDetails).containsOnlyKeys(OnnxFaceAnalyzer.POSE_GATED, "moire", "moire_med");
            assertThat(r.padDetails.get(OnnxFaceAnalyzer.POSE_GATED)).isEqualTo(1f);
            assertThat(a.stats().framesPoseGated()).isEqualTo(1);
            assertThat(a.stats().lastPadNs()).isZero();
        }
    }

    @Test
    void withoutAMeshThePoseIsUnknownSoThereIsNoScore() throws Exception {
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.builder().meshPresenceThreshold(0.999999999).build())) {
            FrameAnalysis r = a.analyze(frame("portrait_frontal"));
            assertThat(r.faceCount).isEqualTo(1);
            assertThat(r.landmarks).isNull();
            assertThat(r.yawDeg).isNaN();
            assertThat(r.livenessScore).isNaN();
            assertThat(r.padDetails).containsOnlyKeys("moire", "moire_med");
            assertThat(r.qualityIssues).isEmpty();
        }
    }

    @Test
    void sessionMoireCapAppliesFromTheThirdFrameAndResetsOnFaceChanges() throws Exception {
        Frame portrait = frame("portrait_frontal");
        byte[] grey = new byte[320 * 240 * 3];
        Arrays.fill(grey, (byte) 128);
        Frame empty = new Frame(grey, 320, 240, 0L, 0L, "test");
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            float raw = a.analyze(portrait).livenessScore;
            assertThat(raw).isGreaterThan(0.60f);
            a.moireOverride = 1.5f; // a screen-like moire, injected: no screen image needed
            FrameAnalysis second = a.analyze(portrait), third = a.analyze(portrait);
            // Window: 1.25 (real), 1.5, 1.5 -> the third frame has 3 values and median 1.5 >= 1.40.
            assertThat(second.livenessScore).isEqualTo(raw);
            assertThat(second.padDetails.get("moire_cap")).isZero();
            assertThat(third.livenessScore).isEqualTo(0.60f);
            assertThat(third.padDetails.get("moire_cap")).isEqualTo(1f);
            assertThat(third.padDetails.get("moire")).isEqualTo(1.5f);
            assertThat(third.padDetails.get("moire_med")).isEqualTo(1.5f);
            assertThat(third.padDetails.get("minifas_v2_live")).isGreaterThan(0.60f); // the model outputs stay raw

            assertThat(a.analyze(empty).faceCount).isZero(); // face lost: the window restarts
            assertThat(a.lastReplayCues()).isNull();
            assertThat(a.analyze(portrait).livenessScore).isEqualTo(raw);
            assertThat(a.analyze(portrait).livenessScore).isEqualTo(raw);
            assertThat(a.analyze(portrait).livenessScore).isEqualTo(0.60f);
            assertThat(a.analyze(frame("crew_multi")).faceCount).isEqualTo(4); // several faces: restarts too
            assertThat(a.analyze(portrait).livenessScore).isEqualTo(raw);

            a.moireOverride = 1.39f; // just below the threshold: never capped
            for (int i = 0; i < 12; i++) assertThat(a.analyze(portrait).padDetails.get("moire_cap")).isZero();
        }
    }

    @Test
    void moireCapCanBeDisabledAndTheVetoEnabled() throws Exception {
        Frame portrait = frame("portrait_frontal");
        try (OnnxFaceAnalyzer off = analyzer(AnalyzerConfig.builder().moireCapThreshold(Double.NaN).build());
             OnnxFaceAnalyzer veto = analyzer(AnalyzerConfig.builder().moireVetoThreshold(1.45).build())) {
            off.moireOverride = 3f;
            veto.moireOverride = 1.5f;
            FrameAnalysis o = null, v = null;
            for (int i = 0; i < 3; i++) {
                o = off.analyze(portrait);
                v = veto.analyze(portrait);
            }
            assertThat(o.livenessScore).isGreaterThan(0.60f);
            assertThat(o.padDetails.get("moire_cap")).isZero();
            assertThat(v.livenessScore).isZero();
            assertThat(v.padDetails.get("moire_cap")).isEqualTo(1f);
        }
    }

    @Test
    void grayFramesAreExpandedAndAnalysed() throws Exception {
        GoldenFixtures.Image img = GoldenFixtures.image("portrait_frontal");
        byte[] luma = new byte[img.width * img.height];
        for (int i = 0; i < luma.length; i++) {
            int r = img.rgb[3 * i] & 0xFF, g = img.rgb[3 * i + 1] & 0xFF, b = img.rgb[3 * i + 2] & 0xFF;
            luma[i] = (byte) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
        }
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            FrameAnalysis r = a.analyze(new Frame(luma, Frame.PixelFormat.GRAY8, img.width, img.height, 0L, 0L, "nir"));
            assertThat(r.faceCount).isEqualTo(1);
            assertThat(r.landmarks).isNotNull();
        }
    }

    @Test
    void errorsAndLifecycle() throws Exception {
        OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults());
        assertThatThrownBy(() -> a.analyze(null)).isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.INVALID_FRAME));
        a.close();
        a.close(); // idempotent
        assertThatThrownBy(() -> a.analyze(frame("portrait_frontal"))).isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.ENGINE_ERROR));
        // Closing an analyzer never closes the shared models: another analyzer still works.
        try (OnnxFaceAnalyzer b = analyzer(AnalyzerConfig.defaults())) {
            assertThat(b.analyze(frame("portrait_frontal")).faceCount).isEqualTo(1);
        }
        assertThatThrownBy(() -> new OnnxFaceAnalyzer(null, AnalyzerConfig.defaults(), "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeFailuresBecomeEngineErrors() throws Exception {
        try (OnnxModels closedModels = OnnxModels.create(TestModels.load(), 1)) {
            OnnxFaceAnalyzer a = new OnnxFaceAnalyzer(closedModels, AnalyzerConfig.defaults(), "v");
            closedModels.close(); // the sessions are gone underneath the analyzer
            assertThatThrownBy(() -> a.analyze(frame("portrait_frontal"))).isInstanceOf(LivenessEngineException.class)
                    .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.ENGINE_ERROR));
        }
    }

    @Test
    void twoAnalyzersSharingOneModelSetAgreeAcrossThreads() throws Exception {
        final Frame portrait = frame("portrait_frontal");
        final FrameAnalysis reference;
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            reference = a.analyze(portrait);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<FrameAnalysis>>> futures = new ArrayList<Future<List<FrameAnalysis>>>();
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(new Callable<List<FrameAnalysis>>() {
                    @Override public List<FrameAnalysis> call() throws Exception {
                        List<FrameAnalysis> out = new ArrayList<FrameAnalysis>();
                        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
                            for (int i = 0; i < 20; i++) out.add(a.analyze(portrait));
                        }
                        return out;
                    }
                }));
            }
            for (Future<List<FrameAnalysis>> f : futures) {
                for (FrameAnalysis r : f.get()) {
                    assertThat(r.faceBox).containsExactly(reference.faceBox);
                    assertThat(r.livenessScore).isEqualTo(reference.livenessScore);
                    assertThat(r.yawDeg).isEqualTo(reference.yawDeg);
                    assertThat(r.pitchDeg).isEqualTo(reference.pitchDeg);
                    assertThat(r.rollDeg).isEqualTo(reference.rollDeg);
                    assertThat(r.padDetails).isEqualTo(reference.padDetails);
                    assertThat(r.landmarks.leftEye[1]).containsExactly(reference.landmarks.leftEye[1]);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
