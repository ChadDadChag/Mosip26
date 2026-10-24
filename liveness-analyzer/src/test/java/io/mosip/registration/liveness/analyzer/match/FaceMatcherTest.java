package io.mosip.registration.liveness.analyzer.match;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessEngineException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** FaceMatcher outcomes on the public-domain golden images (spec: reference_pipeline.face_match). */
class FaceMatcherTest {

    static GoldenFixtures.Image portrait, mirrored, crew;
    static double goldenMirrorCosine;

    @BeforeAll
    static void load() throws Exception {
        portrait = GoldenFixtures.image("portrait_frontal");
        mirrored = GoldenFixtures.image("portrait_frontal_flipped");
        crew = GoldenFixtures.image("crew_multi");
        goldenMirrorCosine = (Double) MiniJson.getObject(GoldenFixtures.json("binding"), "cosines")
                .get("portrait~portrait_flipped");
    }

    static FaceMatcher matcher(AnalyzerConfig config) throws Exception {
        return new FaceMatcher(TestModels.shared(), config);
    }

    static Frame frame(GoldenFixtures.Image img) {
        return GoldenFixtures.frame(img);
    }

    @Test
    void thePortraitMatchesItsMirror() throws Exception {
        try (FaceMatcher m = matcher(AnalyzerConfig.defaults())) {
            MatchResult r = m.compare(frame(portrait), frame(mirrored));
            assertThat(r.outcome).isEqualTo(MatchResult.Outcome.MATCH);
            assertThat(r.matched()).isTrue();
            assertThat(r.similarity).isCloseTo(goldenMirrorCosine, within(1e-3));
            assertThat(r.threshold).isEqualTo(0.363);
            assertThat(r.toString()).startsWith("MATCH");
        }
    }

    @Test
    void anotherPersonDoesNotMatch() throws Exception {
        // One crew member alone: a crop around crew3 (box about [210, 151, 49, 65]); the neighbours start at 362 and end at 121.
        GoldenFixtures.Image one = crop(crew, 160, 90, 150, 200);
        try (FaceMatcher m = matcher(AnalyzerConfig.defaults())) {
            MatchResult r = m.compare(portrait.rgb, portrait.width, portrait.height, one.rgb, one.width, one.height);
            assertThat(r.outcome).as("%s", r).isEqualTo(MatchResult.Outcome.NO_MATCH);
            assertThat(r.similarity).isLessThan(0.363);
            assertThat(r.matched()).isFalse();
        }
    }

    @Test
    void eachImageMustHoldExactlyOneFace() throws Exception {
        GoldenFixtures.Image grey = GoldenFixtures.raw(fill(portrait.rgb.length, (byte) 128), portrait.width, portrait.height);
        try (FaceMatcher m = matcher(AnalyzerConfig.defaults())) {
            assertThat(m.compare(frame(grey), frame(portrait)).outcome).isEqualTo(MatchResult.Outcome.NO_FACE_A);
            assertThat(m.compare(frame(portrait), frame(grey)).outcome).isEqualTo(MatchResult.Outcome.NO_FACE_B);
            assertThat(m.compare(frame(crew), frame(portrait)).outcome).isEqualTo(MatchResult.Outcome.MULTIPLE_FACES_A);
            MatchResult r = m.compare(frame(portrait), frame(crew));
            assertThat(r.outcome).isEqualTo(MatchResult.Outcome.MULTIPLE_FACES_B);
            assertThat(r.similarity).isNaN();
        }
    }

    @Test
    void theThresholdComesFromTheConfig() throws Exception {
        Map<String, String> cfg = new HashMap<String, String>();
        cfg.put(AnalyzerConfig.BINDING_THRESHOLD_KEY, "0.99");
        try (FaceMatcher m = matcher(AnalyzerConfig.fromConfig(cfg::get))) {
            assertThat(m.threshold()).isEqualTo(0.99);
            MatchResult r = m.compare(frame(portrait), frame(mirrored));
            assertThat(r.outcome).as("0.956 < 0.99").isEqualTo(MatchResult.Outcome.NO_MATCH);
        }
    }

    @Test
    void badInputAndAClosedMatcherFail() throws Exception {
        FaceMatcher m = matcher(AnalyzerConfig.defaults());
        assertThatThrownBy(() -> m.compare(null, frame(portrait)))
                .isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.INVALID_FRAME));
        assertThatThrownBy(() -> m.compare(new byte[3], 2, 2, portrait.rgb, portrait.width, portrait.height))
                .isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.INVALID_FRAME));
        m.close();
        assertThatThrownBy(() -> m.compare(frame(portrait), frame(mirrored)))
                .isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.ENGINE_ERROR));
    }

    @Test
    void evaluationHelpersReturnFeatures() throws Exception {
        try (FaceMatcher m = matcher(AnalyzerConfig.defaults())) {
            float[] single = m.featureOfSingleFace(portrait.rgb, portrait.width, portrait.height);
            assertThat(single).hasSize(SFaceEmbedder.FEATURE_LENGTH);
            assertThat(m.featureOfSingleFace(crew.rgb, crew.width, crew.height)).as("four faces").isNull();
            java.util.List<float[]> all = m.featuresOfAllFaces(crew.rgb, crew.width, crew.height);
            assertThat(all).hasSize(4);
            assertThat(SFaceEmbedder.cosine(all.get(0), single)).isLessThan(0.363);
            m.close();
            assertThatThrownBy(() -> m.featureOfSingleFace(portrait.rgb, portrait.width, portrait.height))
                    .isInstanceOf(LivenessEngineException.class);
            assertThatThrownBy(() -> m.featuresOfAllFaces(crew.rgb, crew.width, crew.height))
                    .isInstanceOf(LivenessEngineException.class);
        }
    }

    @Test
    void theFactoryCreatesAMatcherOnTheSignedBundle() throws Exception {
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(); FaceMatcher m = f.createMatcher()) {
            assertThat(m.compare(frame(portrait), frame(mirrored)).outcome).isEqualTo(MatchResult.Outcome.MATCH);
        }
    }

    static byte[] fill(int n, byte v) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, v);
        return out;
    }

    static GoldenFixtures.Image crop(GoldenFixtures.Image img, int x, int y, int w, int h) {
        byte[] out = new byte[w * h * 3];
        for (int r = 0; r < h; r++) System.arraycopy(img.rgb, ((y + r) * img.width + x) * 3, out, r * w * 3, w * 3);
        return GoldenFixtures.raw(out, w, h);
    }
}
