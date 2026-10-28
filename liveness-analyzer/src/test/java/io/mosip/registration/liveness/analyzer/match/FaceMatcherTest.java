package io.mosip.registration.liveness.analyzer.match;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
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
            assertThat(r.threshold).isEqualTo(0.45);
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
            assertThat(r.similarity).isLessThan(0.363); // below OpenCV's default too
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
    void identifyUsesTheBestReadableTemplate() throws Exception {
        GoldenFixtures.Image other = crop(crew, 160, 90, 150, 200);
        GoldenFixtures.Image grey = GoldenFixtures.raw(fill(portrait.rgb.length, (byte) 128), portrait.width, portrait.height);
        try (FaceMatcher m = matcher(AnalyzerConfig.defaults())) {
            assertThat(m.identityThreshold()).isEqualTo(0.40);
            IdentityResult self = m.identify(frame(portrait), java.util.Arrays.asList(frame(mirrored)));
            assertThat(self.outcome).isEqualTo(IdentityResult.Outcome.MATCH);
            assertThat(self.similarity).isCloseTo(goldenMirrorCosine, within(1e-3));
            assertThat(self.threshold).isEqualTo(0.40);
            assertThat(self.templates).isEqualTo(1);
            assertThat(self.unreadable).isZero();

            IdentityResult someoneElse = m.identify(frame(portrait), java.util.Arrays.asList(frame(other)));
            assertThat(someoneElse.outcome).as("%s", someoneElse).isEqualTo(IdentityResult.Outcome.NO_MATCH);
            assertThat(someoneElse.matched()).isFalse();
            assertThat(someoneElse.notBound()).isFalse();

            // Several templates: the best similarity decides; unreadable ones (null, no face, several faces) are counted.
            IdentityResult several = m.identify(frame(portrait),
                    java.util.Arrays.asList(frame(other), null, frame(grey), frame(crew), frame(mirrored)));
            assertThat(several.outcome).isEqualTo(IdentityResult.Outcome.MATCH);
            assertThat(several.similarity).isCloseTo(self.similarity, within(1e-9));
            assertThat(several.templates).isEqualTo(5);
            assertThat(several.unreadable).isEqualTo(3);
        }
    }

    @Test
    void identifyIsNotBoundWithoutAReadableTemplate() throws Exception {
        GoldenFixtures.Image grey = GoldenFixtures.raw(fill(portrait.rgb.length, (byte) 128), portrait.width, portrait.height);
        try (FaceMatcher m = matcher(AnalyzerConfig.defaults())) {
            IdentityResult none = m.identify(frame(portrait), java.util.Collections.<Frame>emptyList());
            assertThat(none.outcome).isEqualTo(IdentityResult.Outcome.NOT_BOUND_NO_TEMPLATE);
            assertThat(none.notBound()).isTrue();
            assertThat(m.identify(frame(portrait), null).outcome).isEqualTo(IdentityResult.Outcome.NOT_BOUND_NO_TEMPLATE);
            IdentityResult unreadable = m.identify(frame(portrait), java.util.Arrays.asList(frame(grey), frame(crew), null));
            assertThat(unreadable.outcome).as("no face, four faces, undecodable")
                    .isEqualTo(IdentityResult.Outcome.NOT_BOUND_UNREADABLE);
            assertThat(unreadable.similarity).isNaN();
            assertThat(unreadable.unreadable).isEqualTo(3);
            assertThat(unreadable.notBound()).isTrue();
            assertThat(m.identify(frame(crew), java.util.Arrays.asList(frame(portrait))).outcome)
                    .isEqualTo(IdentityResult.Outcome.NO_LIVE_FACE);
            assertThat(m.identify(frame(grey), java.util.Arrays.asList(frame(portrait))).outcome)
                    .isEqualTo(IdentityResult.Outcome.NO_LIVE_FACE);
        }
    }

    @Test
    void aReadableTemplateWithoutAFiniteSimilarityIsNoMatchNotUnbound() {
        // A degenerate live feature (zero norm) gives NaN against every readable template: that must block, not
        // count as "not bound", which the host may accept on presence alone.
        assertThat(FaceMatcher.identityOutcome(2, 0, Double.NaN, 0.40)).isEqualTo(IdentityResult.Outcome.NO_MATCH);
        assertThat(FaceMatcher.identityOutcome(3, 2, Double.NaN, 0.40)).isEqualTo(IdentityResult.Outcome.NO_MATCH);
        assertThat(FaceMatcher.identityOutcome(3, 3, Double.NaN, 0.40))
                .isEqualTo(IdentityResult.Outcome.NOT_BOUND_UNREADABLE);
        assertThat(FaceMatcher.identityOutcome(0, 0, Double.NaN, 0.40))
                .isEqualTo(IdentityResult.Outcome.NOT_BOUND_NO_TEMPLATE);
        assertThat(FaceMatcher.identityOutcome(1, 0, 0.40, 0.40)).isEqualTo(IdentityResult.Outcome.MATCH);
        assertThat(FaceMatcher.identityOutcome(1, 0, 0.39, 0.40)).isEqualTo(IdentityResult.Outcome.NO_MATCH);
    }

    @Test
    void theIdentityThresholdIsSeparateFromTheBindingThreshold() throws Exception {
        Map<String, String> cfg = new HashMap<String, String>();
        cfg.put(AnalyzerConfig.IDENTITY_THRESHOLD_KEY, "0.99");
        try (FaceMatcher m = matcher(AnalyzerConfig.fromConfig(cfg::get))) {
            assertThat(m.threshold()).as("F1 unchanged").isEqualTo(0.45);
            assertThat(m.identityThreshold()).isEqualTo(0.99);
            assertThat(m.compare(frame(portrait), frame(mirrored)).outcome).isEqualTo(MatchResult.Outcome.MATCH);
            assertThat(m.identify(frame(portrait), java.util.Arrays.asList(frame(mirrored))).outcome)
                    .as("0.956 < 0.99").isEqualTo(IdentityResult.Outcome.NO_MATCH);
            m.close();
            assertThatThrownBy(() -> m.identify(frame(portrait), java.util.Arrays.asList(frame(mirrored))))
                    .isInstanceOf(LivenessEngineException.class);
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
            FaceMatcher.PrimaryFeature one = m.featureOfPrimaryFace(portrait.rgb, portrait.width, portrait.height);
            assertThat(one.faceCount).isEqualTo(1);
            assertThat(one.feature).containsExactly(single);
            FaceMatcher.PrimaryFeature group = m.featureOfPrimaryFace(crew.rgb, crew.width, crew.height);
            assertThat(group.faceCount).as("four faces, the largest one used").isEqualTo(4);
            assertThat(all).anySatisfy(f -> assertThat(f).containsExactly(group.feature));
            byte[] grey = new byte[64 * 48 * 3];
            java.util.Arrays.fill(grey, (byte) 128);
            assertThat(m.featureOfPrimaryFace(grey, 64, 48)).as("no face").isNull();
            m.close();
            assertThatThrownBy(() -> m.featureOfSingleFace(portrait.rgb, portrait.width, portrait.height))
                    .isInstanceOf(LivenessEngineException.class);
            assertThatThrownBy(() -> m.featuresOfAllFaces(crew.rgb, crew.width, crew.height))
                    .isInstanceOf(LivenessEngineException.class);
            assertThatThrownBy(() -> m.featureOfPrimaryFace(portrait.rgb, portrait.width, portrait.height))
                    .isInstanceOf(LivenessEngineException.class);
        }
    }

    @Test
    void theFactoryCreatesAMatcherOnTheSignedBundle() throws Exception {
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(); FaceMatcher m = f.createMatcher()) {
            assertThat(m.compare(frame(portrait), frame(mirrored)).outcome).isEqualTo(MatchResult.Outcome.MATCH);
        }
    }

    @Test
    void theMatcherSessionIsBuiltByTheFirstCallOrByTheWarmUp() throws Exception {
        OnnxModels own = OnnxModels.create(TestModels.load(), AnalyzerConfig.defaults());
        try (FaceMatcher m = matcher(own)) {
            assertThat(own.lazySessionBuilt()).as("creating a matcher builds nothing").isFalse();
            assertThat(m.compare(frame(portrait), frame(mirrored)).outcome).isEqualTo(MatchResult.Outcome.MATCH);
            assertThat(own.lazySessionBuilt()).isTrue();
            assertThat(own.lazyBytesRetained()).isFalse();
        } finally {
            own.close();
        }
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory()) {
            assertThat(f.matcherReady()).isFalse();
            f.preload();
            assertThat(f.matcherReady()).as("preload builds only the per-frame sessions").isFalse();
            f.warmUpMatcher();
            assertThat(f.matcherReady()).isTrue();
            f.warmUpMatcher(); // idempotent
            try (FaceMatcher m = f.createMatcher()) {
                assertThat(m.compare(frame(portrait), frame(mirrored)).outcome).isEqualTo(MatchResult.Outcome.MATCH);
            }
            f.close();
            assertThat(f.matcherReady()).isFalse();
            assertThatThrownBy(f::warmUpMatcher).isInstanceOf(LivenessEngineException.class);
        }
    }

    static FaceMatcher matcher(OnnxModels models) {
        return new FaceMatcher(models, AnalyzerConfig.defaults());
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
