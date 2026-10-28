package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingEvaluationTest {

    private static final Map<String, String> NO_MAP = new java.util.TreeMap<String, String>();

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

    @Test
    void labelledPhotosAreGenuineWithinAPersonAndASubjectCodeIsThatSubject() {
        float[] x = {1, 0, 0}, y = {0, 1, 0}, nasa = {0, 0, 1};
        BindingEvaluation.Recorded r = new BindingEvaluation.Recorded();
        r.add("s01", BindingEvaluation.Recorded.Kind.LIVE, x);
        r.add("s02", BindingEvaluation.Recorded.Kind.LIVE, y);
        Map<String, java.util.List<float[]>> byPerson = new java.util.TreeMap<String, java.util.List<float[]>>();
        byPerson.put("p01", java.util.Arrays.asList(y, y, y));
        byPerson.put("s01", java.util.Arrays.asList(x));
        Map<String, BindingEvaluation.Scores> sets = new LinkedHashMap<String, BindingEvaluation.Scores>();
        BindingEvaluation.labelledSets(sets, byPerson, r, java.util.Arrays.asList(nasa), NO_MAP);

        assertThat(values(sets, "genuine: labelled photos of the same person")).containsExactly(1.0, 1.0, 1.0);
        assertThat(values(sets, "impostor: labelled photos of different people")).containsExactly(0.0, 0.0, 0.0);
        assertThat(values(sets, "same person: labelled photos vs their own recorded")).containsExactly(1.0);
        // p01 vs s01 and s02 (3 photos x 2 subjects), the s01 photo vs s02.
        assertThat(values(sets, "impostor: labelled photos vs the recorded subjects")).hasSize(7).contains(1.0);
        assertThat(values(sets, "impostor: labelled photos vs the NASA portraits")).containsExactly(0.0, 0.0, 0.0, 0.0);
        assertThat(sets.values()).allMatch(s -> s.name.startsWith("genuine") || s.name.startsWith("same person")
                ? s.genuine : !s.genuine);

        byPerson.remove("s01");
        Map<String, BindingEvaluation.Scores> without = new LinkedHashMap<String, BindingEvaluation.Scores>();
        BindingEvaluation.labelledSets(without, byPerson, r, java.util.Arrays.asList(nasa), NO_MAP);
        assertThat(without.keySet()).as("no empty 'same person' set").noneMatch(k -> k.startsWith("same person"));
        BindingEvaluation.Labelled l = new BindingEvaluation.Labelled();
        l.byPerson.putAll(byPerson);
        l.photos = 3;
        assertThat(l.summary(false)).contains("3 photos of 1 people (1 in two or more photos)", "PROVISIONAL");
        assertThat(l.summary(true)).contains("confirmed").doesNotContain("PROVISIONAL");
    }

    @Test
    void theSubjectMapTakesCodesOnly() {
        Map<String, String> m = BindingEvaluation.subjectMap(" s02=p05, s03=p11 ");
        assertThat(m).containsExactly(java.util.Map.entry("s02", "p05"), java.util.Map.entry("s03", "p11"));
        assertThat(BindingEvaluation.personOf("s02", m)).isEqualTo("p05");
        assertThat(BindingEvaluation.personOf("s01", m)).as("a subject code is its own person_id").isEqualTo("s01");
        assertThat(BindingEvaluation.subjectMap(null)).isEmpty();
        for (String bad : new String[] {"s02=Alice", "s2=p05", "s02:p05", "s02=p05,s02=p06", "s02=p05,s03=p05"}) {
            assertThatThrownBy(() -> BindingEvaluation.subjectMap(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void identitySetsUseTheFirstNormalTakeAsTheEnrollmentAndTheSubjectMap() {
        float[] x = {1, 0, 0}, y = {0, 1, 0}, z = {0, 0, 1};
        BindingEvaluation.Recorded r = new BindingEvaluation.Recorded();
        r.addTake("s02", 4, "close", x);
        r.addTake("s02", 3, "normal", x);
        r.addTake("s02", 1, "normal", x); // take normal#1, whatever the scan order
        r.addTake("s02", 6, "far", y);
        r.addTake("s03", 1, "normal", z);
        r.addTake("s04", 1, "close", z); // no normal take: not enrolled
        assertThat(r.enrollment).containsExactly(java.util.Map.entry("s02", 1), java.util.Map.entry("s03", 1));
        assertThat(r.enrollmentSummary()).isEqualTo("s02: take 1 (the first normal take), 3 other takes; "
                + "s03: take 1 (the first normal take), 0 other takes; s04: no normal take, not enrolled");
        Map<String, java.util.List<float[]>> labelled = new java.util.TreeMap<String, java.util.List<float[]>>();
        labelled.put("p05", java.util.Arrays.asList(x, x)); // s02's photos
        labelled.put("p07", java.util.Arrays.asList(y));
        Map<String, BindingEvaluation.Scores> sets = new LinkedHashMap<String, BindingEvaluation.Scores>();
        BindingEvaluation.IdentitySets s = BindingEvaluation.identitySets(sets, r, labelled,
                BindingEvaluation.subjectMap("s02=p05"));

        assertThat(rounded(s.onboarding)).as("2 photos x 4 takes of s02").hasSize(8).containsOnly(1.0, 0.0);
        assertThat(s.onboarding.errors(0.5)).as("the far take looks like y").isEqualTo(2);
        assertThat(rounded(s.sameDay)).as("s02's enrollment vs takes 3, 4, 6").containsExactly(1.0, 1.0, 0.0);
        // p05 vs p07 (2), p07 vs s02's 4 takes, p05 and p07 vs s03 and s04 (3 x 1 x 2).
        assertThat(s.labelledImpostors.values).hasSize(2 + 4 + 6);
        // s02's enrollment vs s03 and s04 (2), s03's vs s02 (4) and s04 (1).
        assertThat(rounded(s.enrollmentImpostors)).hasSize(7);
        assertThat(s.onboarding.genuine && s.sameDay.genuine).isTrue();
        assertThat(s.labelledImpostors.genuine || s.enrollmentImpostors.genuine).isFalse();
        assertThat(rounded(s.photoPairs)).as("p05's two photos").containsExactly(1.0);
        assertThat(s.photoPairs.genuine).isTrue();
        assertThat(s.onboardingPeople).as("p05, through the subject map").isEqualTo(1);
        assertThat(sets).hasSize(5);
    }

    @Test
    void theF15BreakdownSplitsTheRulesPairsByTypeConditionSubjectAndPhoto() {
        float[] x = {1, 0, 0}, y = {0, 1, 0}, z = {0, 0, 1};
        BindingEvaluation.Recorded r = new BindingEvaluation.Recorded();
        r.addTake("s02", 6, "far", y);
        r.addTake("s02", 4, "close", x);
        r.addTake("s02", 3, "normal", x);
        r.addTake("s02", 1, "normal", x);
        r.addTake("s03", 1, "normal", z);
        r.addTake("s04", 1, "close", z);
        Map<String, java.util.List<float[]>> labelled = new java.util.TreeMap<String, java.util.List<float[]>>();
        labelled.put("p05", java.util.Arrays.asList(x, y));
        labelled.put("p07", java.util.Arrays.asList(y));
        Map<String, java.util.List<String>> quality = new java.util.TreeMap<String, java.util.List<String>>();
        quality.put("p05", java.util.Arrays.asList("q1", "q2"));
        Map<String, String> map = BindingEvaluation.subjectMap("s02=p05");
        BindingEvaluation.IdentitySets rule = BindingEvaluation.identitySets(
                new LinkedHashMap<String, BindingEvaluation.Scores>(), r, labelled, map);
        String s = BindingEvaluation.identityBreakdown(r, labelled, quality, map);

        assertThat(s).contains("## F15 breakdown (context, not part of the rule)")
                // the rule's sets, with the same sizes: 3 same-day, 8 onboarding-like, 7 enrollment impostor pairs
                .contains("| **genuine, same day: each subject's enrollment take (normal#1) vs their other takes** | 3 "
                        + "| 1/3 (33.3%) | 1/3 (33.3%) | 0.000 | 1.000 | 1.000 |")
                .contains("| condition far | 1 | 1/1 (100.0%) | 1/1 (100.0%) | 0.000 | 0.000 | 0.000 |")
                .contains("| **genuine, onboarding-like: a labelled photo vs the same person's live takes** | 8 "
                        + "| 4/8 (50.0%) | 4/8 (50.0%) |")
                .contains("| condition normal | 4 | 2/4 (50.0%) |")
                .contains("| s02 photo 1 of 2 (q1) | 4 | 1/4 (25.0%) |").contains("| s02 photo 2 of 2 (q2) | 4 | 3/4 (75.0%) |")
                .as("several templates: each take matches its best photo")
                .contains("| best of the person's photos, one value per live take | 4 | 0/4 (0.0%) |")
                .contains("| **impostor: labelled photos of different people** | 2 | 1/2 (50.0%) |")
                // p07 vs s02's 4 takes; p05 and p07 vs s03 and s04
                .contains("| **impostor: a labelled photo vs other people's live takes** | 10 | 1/10 (10.0%) |")
                .contains("| **impostor: each subject's enrollment take vs other subjects' takes** | 7 | 1/7 (14.3%) |");
        assertThat(s).contains(String.format(java.util.Locale.ROOT,
                "| **impostor: the rule's labelled impostors (the two rows above)** | %d |", rule.labelledImpostors.values.size()));
        assertThat(rule.onboarding.values).hasSize(8);
        assertThat(rule.sameDay.values).hasSize(3);
        assertThat(rule.enrollmentImpostors.values).hasSize(7);
        int same = s.indexOf("same day"), onboarding = s.indexOf("**genuine, onboarding-like");
        assertThat(s.indexOf("| condition normal", same)).as("run sheet order")
                .isLessThan(s.indexOf("| condition close", same)).isLessThan(s.indexOf("| condition far", same));
        assertThat(s.indexOf("| condition normal", onboarding)).isLessThan(s.indexOf("| condition far", onboarding));
    }

    @Test
    void aNonFiniteSimilarityIsANonMatchAsInIdentify() {
        // identify: a readable template without a finite similarity gives NO_MATCH, never a match, and the best
        // similarity is taken over the finite ones.
        BindingEvaluation.Scores genuine = new BindingEvaluation.Scores("g", true);
        genuine.values.add(Double.NaN);
        genuine.values.add(0.70);
        assertThat(genuine.errors(0.40)).isEqualTo(1);
        assertThat(genuine.errorRate(0.40)).isEqualTo(0.5);
        BindingEvaluation.Scores impostor = new BindingEvaluation.Scores("i", false);
        impostor.values.add(Double.NaN);
        assertThat(impostor.errors(0.40)).as("NaN is never a false match").isZero();

        float[] x = {1, 0, 0}, y = {0, 1, 0}, zero = {0, 0, 0};
        BindingEvaluation.Recorded r = new BindingEvaluation.Recorded();
        r.addTake("s02", 1, "normal", x);
        Map<String, java.util.List<float[]>> labelled = new java.util.TreeMap<String, java.util.List<float[]>>();
        labelled.put("p05", java.util.Arrays.asList(y, zero));
        String s = BindingEvaluation.identityBreakdown(r, labelled, new java.util.TreeMap<String, java.util.List<String>>(),
                BindingEvaluation.subjectMap("s02=p05"));
        assertThat(s).contains("| **genuine, onboarding-like: a labelled photo vs the same person's live takes** | 2 "
                        + "| 2/2 (100.0%) |")
                .as("the best finite similarity, 0, is below 0.40")
                .contains("| best of the person's photos, one value per live take | 1 | 1/1 (100.0%) |");
    }

    @Test
    void theBestOfRowSkipsGroupPhotosAsIdentifyDoesAndPhotosKeepTheirLabelsCsvRows() {
        // labels.csv: p05 has a (one face), b (no face) and c (a group photo); p07 has d, a group photo only.
        Map<String, String> rows = new LinkedHashMap<String, String>();
        rows.put("a.jpg", "p05");
        rows.put("b.jpg", "p05");
        rows.put("c.jpg", "p05");
        rows.put("d.jpg", "p07");
        Map<String, Integer> faces = new LinkedHashMap<String, Integer>();
        faces.put("a.jpg", 1);
        faces.put("b.jpg", 0);
        faces.put("c.jpg", 3);
        faces.put("d.jpg", 2);
        Map<String, java.util.List<BindingEvaluation.PhotoRef>> refs = BindingEvaluation.Labelled.photoRefs(rows, faces);
        assertThat(refs.get("p05")).extracting(p -> p.row + " of " + p.of + (p.readable ? "" : ", unreadable"))
                .as("b has no face, so it is not a photo of byPerson, but it keeps its row")
                .containsExactly("1 of 3", "3 of 3, unreadable");
        assertThat(refs.get("p07")).extracting(p -> p.row + " of " + p.of + (p.readable ? "" : ", unreadable"))
                .containsExactly("1 of 1, unreadable");

        float[] x = {1, 0, 0}, y = {0, 1, 0};
        BindingEvaluation.Recorded r = new BindingEvaluation.Recorded();
        r.addTake("s02", 1, "normal", x);
        r.addTake("s03", 1, "normal", x);
        Map<String, java.util.List<float[]>> labelled = new java.util.TreeMap<String, java.util.List<float[]>>();
        labelled.put("p05", java.util.Arrays.asList(y, x)); // a, and c's largest face
        labelled.put("p07", java.util.Arrays.asList(x));
        String s = BindingEvaluation.identityBreakdown(r, labelled, new java.util.TreeMap<String, java.util.List<String>>(),
                refs, BindingEvaluation.subjectMap("s02=p05,s03=p07"));
        assertThat(s).contains("| s02 photo 1 of 3 | 1 | 1/1 (100.0%) |").contains("| s02 photo 3 of 3 | 1 | 0/1 (0.0%) |")
                .as("the group photo would match, but identify cannot read it: the best readable photo, a, misses")
                .contains("| best of the person's photos, one value per live take | 1 | 1/1 (100.0%) |")
                .as("s03's person has only a group photo: not bound, so not in the best-of row")
                .contains("leaves out 1 live take whose person has only group photos");
    }

    @Test
    void theIdentityRulePicksTheLowestFmrWithFnmrAtMostFivePercent() {
        BindingEvaluation.Scores onboarding = new BindingEvaluation.Scores("F15 genuine, onboarding-like", true);
        BindingEvaluation.Scores impostors = new BindingEvaluation.Scores("F15 impostor, labelled", false);
        BindingEvaluation.Scores empty = new BindingEvaluation.Scores("x", true);
        BindingEvaluation.Scores emptyImpostors = new BindingEvaluation.Scores("y", false);
        // 60 genuine pairs: two at 0.43 (3.3% FNMR from 0.44), two at 0.47 (6.7% from 0.48).
        for (int i = 0; i < 56; i++) onboarding.values.add(0.70);
        onboarding.values.addAll(java.util.Arrays.asList(0.43, 0.43, 0.47, 0.47));
        for (double v : new double[] {0.10, 0.37, 0.41, 0.45, 0.49}) impostors.values.add(v);
        BindingEvaluation.IdentityRule rule = BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(onboarding, empty, impostors, emptyImpostors, empty, 3));
        assertThat(rule.met).isTrue();
        assertThat(rule.chosen).as("0.46 has FMR 1/5 and FNMR 3.3%; 0.48 has FNMR 6.7%").isEqualTo(0.46);
        assertThat(rule.section()).contains("| 0.46 (chosen) | 2/60 (3.3%) | - | 1/5 (20.0%) | - | yes | - |")
                .contains("| 0.48 | 4/60 (6.7%)").contains("**Result: 0.46** meets the rule.")
                .contains("password AND a similar face").contains("at least 3 people and 50 pairs")
                .contains("Only 60 onboarding-like pairs");

        // Ties go to the lower value: with one impostor at 0.20, 0.36 to 0.46 all have FMR 0.
        BindingEvaluation.Scores low = new BindingEvaluation.Scores("F15 impostor, labelled", false);
        low.values.add(0.20);
        assertThat(BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(onboarding, empty, low, emptyImpostors, empty, 3)).chosen)
                .isEqualTo(0.36);

        // Decidable, but nothing qualifies: half the genuine pairs are below every candidate.
        BindingEvaluation.Scores poor = new BindingEvaluation.Scores("F15 genuine, onboarding-like", true);
        for (int i = 0; i < 30; i++) poor.values.add(0.30);
        for (int i = 0; i < 30; i++) poor.values.add(0.70);
        BindingEvaluation.IdentityRule none = BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(poor, empty, impostors, emptyImpostors, empty, 4));
        assertThat(none.met).isFalse();
        assertThat(none.chosen).isEqualTo(0.40);
        assertThat(none.section()).contains("no value meets the rule").contains("30/60 (50.0%)").contains("re-onboarded")
                .contains("| 0.40 (stays) |").doesNotContain("(chosen)");
    }

    @Test
    void theRuleIsNotDecidableWithFewerThanThreePeopleOrFiftyPairs() {
        BindingEvaluation.Scores empty = new BindingEvaluation.Scores("x", true);
        BindingEvaluation.Scores emptyImpostors = new BindingEvaluation.Scores("y", false);
        BindingEvaluation.Scores impostors = new BindingEvaluation.Scores("F15 impostor, labelled", false);
        impostors.values.add(0.10);
        BindingEvaluation.Scores sixty = new BindingEvaluation.Scores("F15 genuine, onboarding-like", true);
        for (int i = 0; i < 60; i++) sixty.values.add(0.70);
        BindingEvaluation.Scores eleven = new BindingEvaluation.Scores("F15 genuine, onboarding-like", true);
        for (int i = 0; i < 11; i++) eleven.values.add(0.70);

        BindingEvaluation.IdentityRule twoPeople = BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(sixty, empty, impostors, emptyImpostors, empty, 2));
        assertThat(twoPeople.met).isFalse();
        assertThat(twoPeople.chosen).isEqualTo(0.40);
        assertThat(twoPeople.section()).contains("**Result: not decidable: 2 people / 60 pairs**")
                .contains("needs at least 3 people and 50 pairs").contains("| 0.40 (stays) |")
                .doesNotContain("(chosen)").doesNotContain("no value meets");

        // Tonight's shape: one person (s01), 11 pairs. The table is still reported in full.
        BindingEvaluation.IdentityRule tonight = BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(eleven, empty, impostors, emptyImpostors, empty, 1));
        assertThat(tonight.met).isFalse();
        assertThat(tonight.section()).contains("not decidable: 1 person / 11 pairs").contains("| 0.50 | 0/11 (0.0%)");

        BindingEvaluation.Scores fortyNine = new BindingEvaluation.Scores("F15 genuine, onboarding-like", true);
        for (int i = 0; i < 49; i++) fortyNine.values.add(0.70);
        assertThat(BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(fortyNine, empty, impostors, emptyImpostors, empty, 5)).met).isFalse();
        assertThat(BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(sixty, empty, impostors, emptyImpostors, empty, 3)).met)
                .as("exactly 3 people and 50+ pairs decide").isTrue();

        BindingEvaluation.IdentityRule noImpostors = BindingEvaluation.IdentityRule.apply(
                new BindingEvaluation.IdentitySets(sixty, empty, emptyImpostors, emptyImpostors, empty, 3));
        assertThat(noImpostors.met).isFalse();
        assertThat(noImpostors.section()).contains("not decidable: 3 people / 60 pairs").contains("no labelled impostors");
    }

    private static java.util.List<Double> rounded(BindingEvaluation.Scores s) {
        java.util.List<Double> out = new java.util.ArrayList<Double>();
        for (double v : s.values) out.add(Math.round(v * 1000) / 1000.0);
        return out;
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
