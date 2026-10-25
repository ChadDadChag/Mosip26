package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BindingEvaluationTest {

    @Test
    void portraitsOfTheSamePersonShareAnIdentityAndCropsShareASource() {
        assertThat(BindingEvaluation.identity("Akihoshide.jpg")).isEqualTo("Akihiko Hoshide");
        assertThat(BindingEvaluation.identity("Akihiko_Hoshide,_official_portrait_(2020).jpg")).isEqualTo("Akihiko Hoshide");
        assertThat(BindingEvaluation.identity("ASTRONAUT_EDWARD_H._WHITE_--_Portrait.jpg")).isEqualTo("Edward White");
        assertThat(BindingEvaluation.identity("AMERICAN_AND_SOVIET_ASTRONAUTS_-_WHITE_-_GEMINI_4_-_NARA_-_17447938.jpg"))
                .isEqualTo("Edward White");
        assertThat(BindingEvaluation.identity("Andrew_M._Allen_-_Official_portrait_of_1987_astronaut_candidate.jpg"))
                .isEqualTo(BindingEvaluation.identity("Andrew_allen.jpg"));
        assertThat(BindingEvaluation.identity("Jonny_Kim_official_portrait.jpg")).isEqualTo("Jonny_Kim_official_portrait.jpg");
        assertThat(BindingEvaluation.source("Al_Worden_Apollo_15_CMP_(3x4_cropped).jpg")).isEqualTo("Al_Worden_Apollo_15_CMP.jpg");
        assertThat(BindingEvaluation.source("Anna_Fisher_-_1979_(cropped).jpg")).isEqualTo("Anna_Fisher_-_1979.jpg");
        assertThat(BindingEvaluation.source("Anna_L._Fisher_Portrait.jpg")).isEqualTo("Anna_L._Fisher_Portrait.jpg");
    }

    @Test
    void errorRatesCountTheRightSideOfTheThreshold() {
        BindingEvaluation.Scores genuine = new BindingEvaluation.Scores("genuine: g", true);
        BindingEvaluation.Scores impostor = new BindingEvaluation.Scores("impostor: i", false);
        for (double v : new double[] {0.30, 0.50, 0.70, 0.90}) genuine.values.add(v);
        for (double v : new double[] {-0.05, 0.10, 0.363, 0.40}) impostor.values.add(v);
        assertThat(genuine.errors(0.363)).isEqualTo(1);
        assertThat(genuine.errorRate(0.60)).isEqualTo(0.5);
        assertThat(impostor.errors(0.363)).as("at the threshold is a match").isEqualTo(2);
        assertThat(impostor.errorRate(0.45)).isEqualTo(0.0);
        assertThat(new BindingEvaluation.Scores("x", true).errorRate(0.5)).isNaN();

        Map<String, BindingEvaluation.Scores> sets = new LinkedHashMap<String, BindingEvaluation.Scores>();
        sets.put(genuine.name, genuine);
        sets.put(impostor.name, impostor);
        Map<String, String> notes = new LinkedHashMap<String, String>();
        notes.put("faces", "test");
        BindingEvaluation.Speed speed = new BindingEvaluation.Speed();
        speed.preloadMs = 100;
        speed.firstCompareMs = 900;
        speed.compareMs = 25.5;
        speed.embedMs = 12.5;
        String md = BindingEvaluation.report(sets, notes, "pad-test", speed, "20261025", 1);
        assertThat(md).startsWith("# Face binding (F1) evaluation: PROVISIONAL, SINGLE SUBJECT");
        assertThat(BindingEvaluation.report(sets, notes, "pad-test", speed, "20261025", 3))
                .startsWith("# Face binding (F1) evaluation: PROVISIONAL, 3 SUBJECTS");
        assertThat(md).contains("| genuine: g | 4 | 0.300 | 0.300 | 0.500 | 0.900 | 0.900 |");
        assertThat(md).contains("| 0.363 | 1/4 (25.0%) | 2/4 (50.0%) |");
        assertThat(md).contains("12.5 ms").contains("no images, features or per-take values");
        assertThat(md).contains("preload (verify the 5 files, build the 4 per-frame sessions): 100 ms")
                .contains("first comparison (two images), which builds the MATCHER session: 900 ms")
                .doesNotContain("warmUpMatcher");
        speed.warmUpMs = 800;
        assertThat(speed.lines("pad-test")).contains("warmUpMatcher (build the MATCHER session): 800 ms")
                .contains("first comparison (two images), after the warm-up: 900 ms");
        assertThat(BindingEvaluation.f(new double[0], 50)).isEqualTo("-");
    }

    @Test
    void recordedFacesAreTheSamePersonOnlyWithinASubject() {
        // Unit vectors: the same direction is cosine 1, orthogonal ones 0.
        float[] a = {1, 0, 0}, b = {0, 1, 0}, nasa = {0, 0, 1};
        BindingEvaluation.Recorded r = new BindingEvaluation.Recorded();
        assertThat(BindingEvaluation.Recorded.kind(take("bona_fide", "normal"))).isEqualTo(BindingEvaluation.Recorded.Kind.LIVE);
        BindingEvaluation.Recorded.Kind own = BindingEvaluation.Recorded.kind(take("print_curved", "selfie_close"));
        BindingEvaluation.Recorded.Kind other = BindingEvaluation.Recorded.kind(take("screen_photo_close", "nasa_fill"));
        assertThat(own).isEqualTo(BindingEvaluation.Recorded.Kind.OWN_PHOTO);
        assertThat(other).isEqualTo(BindingEvaluation.Recorded.Kind.NASA_PHOTO);
        assertThat(BindingEvaluation.Recorded.kind(take("screen_replay", "selfie_video"))).as("videos are not used").isNull();
        assertThat(BindingEvaluation.Recorded.kind(take("print_flat", null))).isNull();

        r.add("s01", BindingEvaluation.Recorded.Kind.LIVE, a);
        r.add("s01", BindingEvaluation.Recorded.Kind.LIVE, a);
        r.add("s02", BindingEvaluation.Recorded.Kind.LIVE, b);
        r.add("s01", own, a);
        r.add("s01", other, nasa);
        r.noFace = 1;
        Map<String, BindingEvaluation.Scores> sets = new LinkedHashMap<String, BindingEvaluation.Scores>();
        BindingEvaluation.recordedSets(sets, r);

        assertThat(values(sets, "genuine: each recorded subject's bona fide takes")).containsExactly(1.0);
        assertThat(values(sets, "impostor: recorded subjects vs each other")).containsExactly(0.0, 0.0);
        assertThat(values(sets, "same person via a photo")).containsExactly(1.0, 1.0);
        assertThat(values(sets, "impostor: a subject vs photos of another subject's selfie")).containsExactly(0.0);
        assertThat(values(sets, "impostor: the subjects vs screen photos and prints of NASA")).containsExactly(0.0, 0.0, 0.0);
        assertThat(sets.values()).allMatch(s -> s.name.startsWith("genuine") || s.name.startsWith("same person")
                ? s.genuine : !s.genuine);
        assertThat(r.summary()).isEqualTo("2 subjects (s01: 2 bona fide, 1 photos of their own selfie; "
                + "s02: 1 bona fide, 0 photos of their own selfie), 1 photos of a NASA portrait; "
                + "1 without a usable single-face frame");
    }

    private static EvalDataset.TakeRef take(String species, String condition) {
        return new EvalDataset.TakeRef(species, "s01", 1, condition, 10, new java.io.File("."));
    }

    private static java.util.List<Double> values(Map<String, BindingEvaluation.Scores> sets, String prefix) {
        for (BindingEvaluation.Scores s : sets.values()) {
            if (s.name.startsWith(prefix)) {
                java.util.List<Double> rounded = new java.util.ArrayList<Double>();
                for (double v : s.values) rounded.add(Math.round(v * 1000) / 1000.0);
                return rounded;
            }
        }
        throw new AssertionError("no set " + prefix + " in " + sets.keySet());
    }
}
