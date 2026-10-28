package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PadEvaluationTest {

    static PadEvaluation.FrameRec frame(float v2, float v1se, float moire, float area) {
        return new PadEvaluation.FrameRec(1, EnumSet.noneOf(QualityIssue.class), 0f, 0f, 0f, (v2 + v1se) / 2, v2, v1se,
                moire, 0f, area);
    }

    static PadEvaluation.FrameRec noFace() {
        return new PadEvaluation.FrameRec(0, EnumSet.noneOf(QualityIssue.class), Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    }

    static List<PadEvaluation.FrameRec> frames(int n, float v2, float v1se, float moire, float area) {
        List<PadEvaluation.FrameRec> fs = new ArrayList<PadEvaluation.FrameRec>();
        for (int i = 0; i < n; i++) fs.add(frame(v2, v1se, moire, area));
        return fs;
    }

    private static PadEvaluation.Outcome eval(List<PadEvaluation.FrameRec> fs, PadEvaluation.Candidate c) {
        return PadEvaluation.evaluate(fs, 10, c, new LivenessPolicy(), EngineTuning.defaults());
    }

    @Test
    void setChangesAnalyzerSettings() {
        assertThat(PadEvaluation.analyzerConfig(java.util.Arrays.asList("qualityRelativeBlurRatio=off", "intraOpThreads = 3"))
                .qualityRelativeBlurRatio).isNaN();
        assertThat(PadEvaluation.analyzerConfig(java.util.Collections.<String>emptyList()).qualityRelativeBlurRatio)
                .isEqualTo(0.45);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PadEvaluation.analyzerConfig(java.util.Collections.singletonList("novalue")))
                .isInstanceOf(ModelBundleTool.UsageException.class);
    }

    @Test
    void candidateScorers() {
        PadEvaluation.FrameRec f = frame(0.9f, 0.5f, 1.9f, 0.30f);
        List<PadEvaluation.Candidate> fixed = PadEvaluation.fixedCandidates();
        assertThat(fixed).hasSize(16);
        assertThat(fixed.get(0).score(f)).isCloseTo(0.7f, within(1e-6f)); // production: the analyzer's score as delivered
        assertThat(fixed.get(1).score(f)).isCloseTo(0.7f, within(1e-6f)); // 1a: (v2 + v1se) / 2 recomputed, no cap
        assertThat(fixed.get(2).score(f)).isEqualTo(0.9f);
        assertThat(fixed.get(3).score(f)).isEqualTo(0.5f);
        assertThat(fixed.get(4).score(f)).isEqualTo(0.5f);
        // A capped production frame: production keeps the delivered 0.60, 1a recomputes the raw mean.
        PadEvaluation.FrameRec capped = new PadEvaluation.FrameRec(1, EnumSet.noneOf(QualityIssue.class), 0f, 0f, 0f, 0.60f,
                0.99f, 0.97f, 1.5f, 0f, 0.3f, 1.5f, true);
        assertThat(PadEvaluation.production().score(capped)).isEqualTo(0.60f);
        assertThat(PadEvaluation.uncapped().score(capped)).isCloseTo(0.98f, within(1e-6f));
        assertThat(new PadEvaluation.Candidate("5", "", PadEvaluation.Base.MEAN, 1.8f, Float.NaN).score(f)).isZero();
        assertThat(new PadEvaluation.Candidate("5", "", PadEvaluation.Base.MEAN, 2.0f, Float.NaN).score(f)).isCloseTo(0.7f, within(1e-6f));
        assertThat(new PadEvaluation.Candidate("4", "", PadEvaluation.Base.MIN, Float.NaN, Float.NaN).score(frame(Float.NaN, 0.5f, 0, 0)))
                .isNaN();

        FrameAnalysis gated = new PadEvaluation.Candidate("6", "", PadEvaluation.Base.MEAN, Float.NaN, 0.25f).analysis(f);
        assertThat(gated.qualityIssues).containsExactly(QualityIssue.FACE_TOO_LARGE);
        assertThat(gated.usable()).isFalse();
        assertThat(new PadEvaluation.Candidate("6", "", PadEvaluation.Base.MEAN, Float.NaN, 0.35f).analysis(f).usable()).isTrue();
        assertThat(f.issues).isEmpty(); // the stored frame is never changed

        PadEvaluation.FrameRec heur = new PadEvaluation.FrameRec(1, EnumSet.noneOf(QualityIssue.class), Float.NaN, Float.NaN,
                Float.NaN, 0.6f, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0.1f);
        FrameAnalysis h = PadEvaluation.heuristic().analysis(heur);
        assertThat(h.yawDeg).isZero(); // no pose: treated as frontal
        assertThat(h.livenessScore).isEqualTo(0.6f);

        List<PadEvaluation.Candidate> combos = PadEvaluation.combinations(1.8f, 0.25f);
        assertThat(combos).extracting(c -> c.name).containsExactly("MIN, moire veto >= 1.8", "MIN, area gate > 0.25",
                "MEAN, moire veto >= 1.8, area gate > 0.25", "MIN, moire veto >= 1.8, area gate > 0.25");
    }

    @Test
    void presentationScoreIsTheFirstFullWindowMedian() {
        List<PadEvaluation.FrameRec> fs = frames(8, 0.9f, 0.9f, 0f, 0.1f);
        fs.add(frame(0.1f, 0.1f, 0f, 0.1f));
        fs.add(frame(0.1f, 0.1f, 0f, 0.1f));
        fs.addAll(frames(5, 0.0f, 0.0f, 0f, 0.1f)); // after the first window: no effect on the presentation score
        PadEvaluation.Outcome o = eval(fs, PadEvaluation.production());
        assertThat(o.median).isCloseTo(0.9f, within(1e-6f));
        assertThat(o.verdict).isEqualTo(PassiveEvaluator.Verdict.PASS);
        assertThat(o.eligible).isEqualTo(15);
        assertThat(o.minRolling).isZero(); // but PadMonitor's 3-frame rolling median does see it
    }

    @Test
    void noFullWindowIsANonResponse() {
        assertThat(eval(frames(9, 0.9f, 0.9f, 0f, 0.1f), PadEvaluation.production()).median).isNaN();

        List<PadEvaluation.FrameRec> lost = frames(6, 0.9f, 0.9f, 0f, 0.1f);
        lost.add(noFace()); // the window restarts when the face disappears
        lost.addAll(frames(6, 0.9f, 0.9f, 0f, 0.1f));
        PadEvaluation.Outcome o = eval(lost, PadEvaluation.production());
        assertThat(o.median).isNaN();
        assertThat(o.verdict).isEqualTo(PassiveEvaluator.Verdict.PENDING);

        // A face-size gate can turn a close-up into a non-response: its frames become ineligible.
        List<PadEvaluation.FrameRec> close = frames(20, 0.99f, 0.99f, 0f, 0.40f);
        assertThat(eval(close, PadEvaluation.production()).median).isCloseTo(0.99f, within(1e-6f));
        assertThat(eval(close, new PadEvaluation.Candidate("6", "", PadEvaluation.Base.MEAN, Float.NaN, 0.35f)).median).isNaN();
    }

    static PadEvaluation.TakeData take(String species, int take, String condition, List<PadEvaluation.FrameRec> fs) {
        return new PadEvaluation.TakeData(new EvalDataset.TakeRef(species, "s01", take, condition, 10, new File("x")), fs, null);
    }

    @Test
    void reportHasAggregatesOnlyAndMarksMissingSpecies() {
        List<PadEvaluation.TakeData> runs = new ArrayList<PadEvaluation.TakeData>();
        runs.add(take("bona_fide", 1, "normal", frames(20, 0.98f, 0.96f, 1.2f, 0.10f)));
        runs.add(take("bona_fide", 2, "close", frames(20, 0.97f, 0.95f, 1.3f, 0.30f)));
        runs.add(take("screen_photo", 1, "selfie", frames(20, 0.10f, 0.20f, 2.1f, 0.08f)));
        runs.add(take("screen_photo_close", 1, "selfie", frames(20, 0.99f, 0.95f, 1.9f, 0.32f)));
        PadEvaluation.Evaluation e = PadEvaluation.Evaluation.of(runs, new LivenessPolicy(), EngineTuning.defaults());
        assertThat(e.candidates).hasSize(20); // 1, 1a, 2-6 (16) + 4 combinations; no heuristic run here

        // The close-up passes passively under production but not with a moire veto (bona fide moire <= 1.3).
        Map<String, int[]> prod = PadMetrics.zones(e.presentations(e.candidates.get(0)), 0.85f, 0.30f);
        assertThat(prod.get("screen_photo_close")[0]).isEqualTo(1);
        assertThat(e.bestVeto).isEqualTo(1.4f); // 1.3 vetoes the bona fide close-up; 1.4 and up separate, ties keep the first

        String md = e.report("pad-test", "20261024", "eval-data");
        assertThat(md).doesNotContain("subject codes in all").contains("(provisional, 1 bona fide subject)");
        assertThat(md).contains("SINGLE SUBJECT", "Not yet evaluated", "`print_flat`", "`screen_replay`", "non-normative",
                "| 4 | MIN(v2, v1se) |", "Before the moire cap (candidate 1a)", "Moire cap effect", "Close-up analysis", "bona_fide (close)", "screen_photo_close (selfie)", "Recommended thresholds");
        assertThat(md).doesNotContain("s01"); // aggregates only: no per-take rows
        String scores = e.scoresCsv();
        assertThat(scores.split("\n")).hasSize(5);
        assertThat(scores).startsWith("subject,species,take,condition,frames,eligible,\"1 MEAN + session moire cap (production)\","
                + "\"1a MEAN, no moire cap (before)\"");
        assertThat(e.framesCsv().split("\n")).hasSize(81);
        assertThat(e.scatterCsv()).contains("s01,screen_photo_close,1,selfie,0.9700,0.3200,1.9000");
    }

    @Test
    void theReportCountsBonaFideSubjectsApartFromAttackSubjectCodes() {
        List<PadEvaluation.TakeData> runs = new ArrayList<PadEvaluation.TakeData>();
        String[][] takes = {{"bona_fide", "s01"}, {"bona_fide", "s02"}, {"screen_photo_close", "s01"},
                {"screen_photo_multi", "p05"}, {"print_multi", "p07"}};
        for (int i = 0; i < takes.length; i++) {
            boolean bonaFide = "bona_fide".equals(takes[i][0]);
            runs.add(new PadEvaluation.TakeData(new EvalDataset.TakeRef(takes[i][0], takes[i][1], i + 1, "normal", 10,
                    new File("x")), frames(20, bonaFide ? 0.98f : 0.10f, bonaFide ? 0.96f : 0.20f, 1.2f, 0.10f), null));
        }
        String md = PadEvaluation.Evaluation.of(runs, new LivenessPolicy(), EngineTuning.defaults())
                .report("pad-test", "20261028", "eval-data");
        assertThat(md).startsWith("# Part 2 PAD evaluation: PROVISIONAL, 2 bona fide subjects\n")
                .contains("- **2 bona fide subjects.** Every number below comes from 2 people")
                .contains("- **4 subject codes in all**, attack takes included")
                .contains("## Recommended thresholds (provisional, 2 bona fide subjects)")
                .doesNotContain("SINGLE SUBJECT").doesNotContain("4 subjects")
                .doesNotContain("s01").doesNotContain("p05"); // aggregates only
    }

    @Test
    void scansTheDatasetLayout(@TempDir File root) throws Exception {
        meta(new File(root, "bona_fide/s01/1"), "{\"label\": \"bona_fide\", \"condition\": \"normal\", \"fps\": 10.0,"
                + " \"device\": {\"deviceId\": \"webcam:0\"}}");
        Files.write(new File(root, "bona_fide/s01/1/frame_00001.jpg").toPath(), new byte[1]);
        Files.write(new File(root, "bona_fide/s01/1/frame_00000.jpg").toPath(), new byte[1]);
        Files.write(new File(root, "bona_fide/s01/1/notes.txt").toPath(), new byte[1]);
        meta(new File(root, "attack/screen_photo_close/s01/2"), "{\"condition\": \"nasa\", \"fps\": 8}");
        meta(new File(root, "attack/mask_3d/s02/1"), "{}");
        new File(root, "attack/screen_photo/s01/3").mkdirs(); // no meta.json: not a take
        new File(root, "attack/print_flat").mkdirs();

        List<EvalDataset.TakeRef> takes = EvalDataset.scan(root);
        assertThat(takes).extracting(Object::toString)
                .containsExactly("bona_fide/s01/1", "screen_photo_close/s01/2", "mask_3d/s02/1");
        EvalDataset.TakeRef b = takes.get(0);
        assertThat(b.attack()).isFalse();
        assertThat(b.condition).isEqualTo("normal");
        assertThat(b.fps).isEqualTo(10.0);
        assertThat(b.frames()).extracting(File::getName).containsExactly("frame_00000.jpg", "frame_00001.jpg");
        assertThat(takes.get(1).fps).isEqualTo(8.0);
        assertThat(takes.get(2).attack()).isTrue();
        assertThat(takes.get(2).fps).isEqualTo(10.0); // default
        Map<String, Integer> counts = EvalDataset.counts(takes);
        assertThat(counts).containsEntry("bona_fide", 1).containsEntry("screen_photo_close", 1)
                .containsEntry("print_flat", 0).containsEntry("mask_3d", 1);
        assertThat(EvalDataset.readMeta("{\"take\": 3, \"device\": {\"take\": 9}}")).containsEntry("take", "3");
    }

    private static void meta(File dir, String json) throws Exception {
        assertThat(dir.mkdirs()).isTrue();
        Files.write(new File(dir, "meta.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }
}
