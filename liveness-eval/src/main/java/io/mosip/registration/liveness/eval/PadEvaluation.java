package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.pad.ReplayCues;
import io.mosip.registration.liveness.analyzer.heuristic.HeuristicFaceAnalyzer;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.passive.PadMonitor;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PAD evaluation over the recorded dataset (development only; {@code docs/part2-dataset-protocol.md}), with
 * ISO/IEC 30107-3:2023 metrics ({@link PadMetrics}).
 * <pre>
 *   PadEvaluation [--data eval-data] [--out eval-data/reports/pad-eval-yyyyMMdd] [--no-heuristic]
 * </pre>
 * Per take: a fresh analyzer (one per take, as one per session), frames in order with timestamps 1000/fps apart.
 * The presentation score is what production decides on: the median of the FIRST full window of Part 3's
 * {@link PassiveEvaluator} (default RESIDENT policy, default tuning). A take without a full window is a
 * non-response. Also kept: the minimum 3-frame rolling median over the take, as {@link PadMonitor} sees it during
 * challenges. Candidate scorers are recomputed per frame from the stored model outputs and go through the same window
 * logic. Writes report.md (aggregates only), scores.csv (per take), frames.csv (per frame features, no pixels or
 * landmarks) and scatter.csv; the default output folder is under the gitignored {@code eval-data/}.
 */
public final class PadEvaluation {

    static final float PASSIVE = 0.85f, REJECT = 0.30f;
    /** 1.6 to 2.2 from the Phase 6 brief; 1.3 to 1.5 added because round 1's close-up attacks sit at 1.4 to 1.6. */
    static final float[] MOIRE_VETOES = {1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 2.0f, 2.2f};
    static final float[] AREA_GATES = {0.20f, 0.25f, 0.35f, 0.60f};

    private PadEvaluation() { }

    // ---------------------------------------------------------------------------------------------------------
    // per-frame features and candidate scorers
    // ---------------------------------------------------------------------------------------------------------

    /** What the evaluation keeps of one FrameAnalysis: numbers only, no pixels and no landmarks. */
    static final class FrameRec {
        final int faces;
        final Set<QualityIssue> issues;
        final float yaw, pitch, roll, score, v2, v1se, moire, glare, area, moireMed;
        final boolean capped;

        FrameRec(int faces, Set<QualityIssue> issues, float yaw, float pitch, float roll, float score, float v2,
                 float v1se, float moire, float glare, float area) {
            this(faces, issues, yaw, pitch, roll, score, v2, v1se, moire, glare, area, Float.NaN, false);
        }

        FrameRec(int faces, Set<QualityIssue> issues, float yaw, float pitch, float roll, float score, float v2,
                 float v1se, float moire, float glare, float area, float moireMed, boolean capped) {
            this.faces = faces;
            this.issues = issues.isEmpty() ? EnumSet.noneOf(QualityIssue.class) : EnumSet.copyOf(issues);
            this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.score = score;
            this.v2 = v2; this.v1se = v1se; this.moire = moire; this.glare = glare; this.area = area;
            this.moireMed = moireMed; this.capped = capped;
        }

        /** score is the analyzer's livenessScore as delivered (after the session moire cap). */
        static FrameRec of(FrameAnalysis a, int width, int height, float glare) {
            float area = a.faceBox == null ? Float.NaN : a.faceBox[2] * a.faceBox[3] / ((float) width * height);
            return new FrameRec(a.faceCount, a.qualityIssues, a.yawDeg, a.pitchDeg, a.rollDeg, a.livenessScore,
                    detail(a, "minifas_v2_live"), detail(a, "minifas_v1se_live"), detail(a, "moire"), glare, area,
                    detail(a, "moire_med"), detail(a, "moire_cap") == 1f);
        }

        private static float detail(FrameAnalysis a, String key) {
            Float v = a.padDetails.get(key);
            return v == null ? Float.NaN : v;
        }
    }

    /**
     * How a candidate turns a frame into a score: PRODUCTION = the analyzer's livenessScore as delivered (MEAN with
     * the session moire cap); MEAN = (v2 + v1se) / 2 recomputed without the cap; HEURISTIC = the heuristic score.
     */
    enum Base { PRODUCTION, MEAN, V2, V1SE, MIN, HEURISTIC }

    /** One candidate scorer: a base score, an optional moire veto and an optional face-size gate. */
    static final class Candidate {
        final String id, name;
        final Base base;
        final float moireVeto, areaGate; // NaN = none

        Candidate(String id, String name, Base base, float moireVeto, float areaGate) {
            this.id = id; this.name = name; this.base = base; this.moireVeto = moireVeto; this.areaGate = areaGate;
        }

        /** The frame's score under this candidate; NaN when it has none. */
        float score(FrameRec f) {
            float s;
            switch (base) {
                case V2: s = f.v2; break;
                case V1SE: s = f.v1se; break;
                case MIN: s = Float.isNaN(f.v2) || Float.isNaN(f.v1se) ? Float.NaN : Math.min(f.v2, f.v1se); break;
                case MEAN: s = Float.isNaN(f.v2) || Float.isNaN(f.v1se) ? Float.NaN : (f.v2 + f.v1se) / 2f; break;
                default: s = f.score; break; // PRODUCTION and HEURISTIC: as the analyzer delivered it
            }
            if (!Float.isNaN(moireVeto) && !Float.isNaN(s) && f.moire >= moireVeto) s = 0f;
            return s;
        }

        /** The frame as the engine would see it under this candidate. */
        FrameAnalysis analysis(FrameRec f) {
            EnumSet<QualityIssue> issues = f.issues.isEmpty() ? EnumSet.noneOf(QualityIssue.class) : EnumSet.copyOf(f.issues);
            if (!Float.isNaN(areaGate) && f.area > areaGate) issues.add(QualityIssue.FACE_TOO_LARGE);
            // The heuristic analyzer estimates no pose; treat it as frontal, or it could never fill a window.
            float yaw = base == Base.HEURISTIC && Float.isNaN(f.yaw) ? 0f : f.yaw;
            float pitch = base == Base.HEURISTIC && Float.isNaN(f.pitch) ? 0f : f.pitch;
            return new FrameAnalysis(null, f.faces, null, null, yaw, pitch, f.roll, issues, score(f),
                    Collections.<String, Float>emptyMap(), 0);
        }
    }

    static Candidate production() {
        return new Candidate("1", "MEAN + session moire cap (production)", Base.PRODUCTION, Float.NaN, Float.NaN);
    }

    /** Phase 6 production: MEAN without the session moire cap, the baseline the cap is measured against. */
    static Candidate uncapped() {
        return new Candidate("1a", "MEAN, no moire cap (before)", Base.MEAN, Float.NaN, Float.NaN);
    }

    /** Candidates 1 to 6; 7 is built from the best of 4 to 6 once those are scored, 8 is the heuristic analyzer. */
    static List<Candidate> fixedCandidates() {
        List<Candidate> c = new ArrayList<Candidate>();
        c.add(production());
        c.add(uncapped());
        c.add(new Candidate("2", "V2 only", Base.V2, Float.NaN, Float.NaN));
        c.add(new Candidate("3", "V1SE only", Base.V1SE, Float.NaN, Float.NaN));
        c.add(new Candidate("4", "MIN(v2, v1se)", Base.MIN, Float.NaN, Float.NaN));
        for (float m : MOIRE_VETOES) c.add(new Candidate("5", "MEAN, moire veto >= " + m, Base.MEAN, m, Float.NaN));
        for (float a : AREA_GATES) c.add(new Candidate("6", "MEAN, area gate > " + a, Base.MEAN, Float.NaN, a));
        return c;
    }

    static List<Candidate> combinations(float bestVeto, float bestGate) {
        List<Candidate> c = new ArrayList<Candidate>();
        c.add(new Candidate("7", "MIN, moire veto >= " + bestVeto, Base.MIN, bestVeto, Float.NaN));
        c.add(new Candidate("7", "MIN, area gate > " + bestGate, Base.MIN, Float.NaN, bestGate));
        c.add(new Candidate("7", "MEAN, moire veto >= " + bestVeto + ", area gate > " + bestGate, Base.MEAN, bestVeto, bestGate));
        c.add(new Candidate("7", "MIN, moire veto >= " + bestVeto + ", area gate > " + bestGate, Base.MIN, bestVeto, bestGate));
        return c;
    }

    static Candidate heuristic() {
        return new Candidate("8", "HeuristicFaceAnalyzer (baseline)", Base.HEURISTIC, Float.NaN, Float.NaN);
    }

    // ---------------------------------------------------------------------------------------------------------
    // one take through the engine's window logic
    // ---------------------------------------------------------------------------------------------------------

    /** A candidate's result on one take. */
    static final class Outcome {
        final float median;               // first full window's median; NaN = non-response
        final PassiveEvaluator.Verdict verdict;
        final int eligible;
        final float minRolling;           // min 3-frame rolling median over the take (PadMonitor's view); NaN if never full

        Outcome(float median, PassiveEvaluator.Verdict verdict, int eligible, float minRolling) {
            this.median = median; this.verdict = verdict; this.eligible = eligible; this.minRolling = minRolling;
        }
    }

    static Outcome evaluate(List<FrameRec> frames, double fps, Candidate c, LivenessPolicy policy, EngineTuning tuning) {
        PassiveEvaluator ev = new PassiveEvaluator(policy, tuning);
        ev.reset(0);
        PadMonitor monitor = new PadMonitor(policy.padRejectThreshold, tuning.padMonitorFrames);
        PassiveEvaluator.Verdict verdict = PassiveEvaluator.Verdict.PENDING;
        float median = Float.NaN, minRolling = Float.NaN;
        int eligible = 0, fed = 0;
        for (int i = 0; i < frames.size(); i++) {
            long now = Math.round(i * 1000.0 / fps);
            FrameAnalysis a = c.analysis(frames.get(i));
            if (eligible(a, tuning)) eligible++;
            if (verdict == PassiveEvaluator.Verdict.PENDING) {
                PassiveEvaluator.Verdict v = ev.offer(a, now);
                if (v != PassiveEvaluator.Verdict.PENDING) {
                    verdict = v;
                    median = v == PassiveEvaluator.Verdict.TIMEOUT ? Float.NaN : ev.median();
                }
            }
            if (a.faceCount == 1 && !Float.isNaN(a.livenessScore)) {
                monitor.attack(a);
                if (++fed >= tuning.padMonitorFrames) {
                    float m = monitor.median();
                    if (Float.isNaN(minRolling) || m < minRolling) minRolling = m;
                }
            }
        }
        return new Outcome(median, verdict, eligible, minRolling);
    }

    /** PassiveEvaluator's eligibility rule (usable, scored, within the passive pose range). */
    static boolean eligible(FrameAnalysis a, EngineTuning t) {
        return a.usable() && !Float.isNaN(a.livenessScore) && Math.abs(a.yawDeg) <= t.maxPassivePoseDeg
                && Math.abs(a.pitchDeg) <= t.maxPassivePoseDeg;
    }

    // ---------------------------------------------------------------------------------------------------------
    // the dataset run
    // ---------------------------------------------------------------------------------------------------------

    /** One take's per-frame features from the ONNX analyzer and (optionally) the heuristic analyzer. */
    static final class TakeData {
        final EvalDataset.TakeRef ref;
        final List<FrameRec> onnx, heuristic;

        TakeData(EvalDataset.TakeRef ref, List<FrameRec> onnx, List<FrameRec> heuristic) {
            this.ref = ref; this.onnx = onnx; this.heuristic = heuristic;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0, Arrays.asList("--no-heuristic"));
            File data = new File(a.has("--data") ? a.single("--data") : "eval-data");
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            File outDir = new File(a.has("--out") ? a.single("--out") : "eval-data/reports/pad-eval-" + date);
            List<EvalDataset.TakeRef> takes = EvalDataset.scan(data);
            if (takes.isEmpty()) {
                err.println("no takes under " + data + " (expected bona_fide/<subject>/<take>/ and attack/<species>/...)");
                return 1;
            }
            OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory();
            String modelVersion = factory.modelVersion();
            List<TakeData> runs = new ArrayList<TakeData>();
            try {
                for (EvalDataset.TakeRef t : takes) {
                    FaceAnalyzer onnx = factory.create();
                    List<FrameRec> o, h = null;
                    try {
                        o = analyze(onnx, t);
                    } finally {
                        onnx.close();
                    }
                    if (!a.has("--no-heuristic")) {
                        HeuristicFaceAnalyzer heur = new HeuristicFaceAnalyzer();
                        try {
                            h = analyze(heur, t);
                        } finally {
                            heur.close();
                        }
                    }
                    runs.add(new TakeData(t, o, h));
                    out.println("analysed " + t + " (" + o.size() + " frames)");
                }
            } finally {
                factory.close();
            }
            Evaluation e = Evaluation.of(runs, new LivenessPolicy(), EngineTuning.defaults());
            if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
            write(new File(outDir, "report.md"), e.report(modelVersion, date, data.getPath()));
            write(new File(outDir, "scores.csv"), e.scoresCsv());
            write(new File(outDir, "frames.csv"), e.framesCsv());
            write(new File(outDir, "scatter.csv"), e.scatterCsv());
            out.println("wrote " + outDir + "/report.md, scores.csv, frames.csv, scatter.csv");
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println("usage: PadEvaluation [--data eval-data] [--out eval-data/reports/pad-eval-yyyyMMdd] [--no-heuristic]");
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static List<FrameRec> analyze(FaceAnalyzer analyzer, EvalDataset.TakeRef t) throws Exception {
        List<FrameRec> recs = new ArrayList<FrameRec>();
        List<File> files = t.frames();
        for (int i = 0; i < files.size(); i++) {
            Frame f = load(files.get(i), Math.round(i * 1000.0 / t.fps), i, "eval:" + t);
            FrameAnalysis a = analyzer.analyze(f);
            ReplayCues.Cues cues = analyzer instanceof OnnxFaceAnalyzer ? ((OnnxFaceAnalyzer) analyzer).lastReplayCues() : null;
            recs.add(FrameRec.of(a, f.width(), f.height(), cues == null ? Float.NaN : cues.glare));
        }
        return recs;
    }

    static Frame load(File file, long timestampMs, long seq, String deviceId) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) throw new IOException("not an image: " + file);
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0; i < px.length; i++) {
            rgb[3 * i] = (byte) (px[i] >> 16);
            rgb[3 * i + 1] = (byte) (px[i] >> 8);
            rgb[3 * i + 2] = (byte) px[i];
        }
        return new Frame(rgb, w, h, timestampMs, seq, deviceId);
    }

    // ---------------------------------------------------------------------------------------------------------
    // scoring every candidate and writing the outputs
    // ---------------------------------------------------------------------------------------------------------

    /** All candidates' outcomes on all takes, and the report and CSV writers. */
    static final class Evaluation {
        final List<TakeData> runs;
        final List<Candidate> candidates = new ArrayList<Candidate>();
        final Map<Candidate, List<Outcome>> outcomes = new LinkedHashMap<Candidate, List<Outcome>>();
        float bestVeto = Float.NaN, bestGate = Float.NaN;

        private Evaluation(List<TakeData> runs) {
            this.runs = runs;
        }

        static Evaluation of(List<TakeData> runs, LivenessPolicy policy, EngineTuning tuning) {
            Evaluation e = new Evaluation(runs);
            for (Candidate c : fixedCandidates()) e.score(c, policy, tuning);
            e.bestVeto = e.best("5").moireVeto;
            e.bestGate = e.best("6").areaGate;
            for (Candidate c : combinations(e.bestVeto, e.bestGate)) e.score(c, policy, tuning);
            boolean heur = true;
            for (TakeData t : runs) heur &= t.heuristic != null;
            if (heur) e.score(heuristic(), policy, tuning);
            return e;
        }

        private void score(Candidate c, LivenessPolicy policy, EngineTuning tuning) {
            List<Outcome> os = new ArrayList<Outcome>();
            for (TakeData t : runs) {
                os.add(evaluate(c.base == Base.HEURISTIC ? t.heuristic : t.onnx, t.ref.fps, c, policy, tuning));
            }
            candidates.add(c);
            outcomes.put(c, os);
        }

        List<PadMetrics.Presentation> presentations(Candidate c) {
            List<PadMetrics.Presentation> ps = new ArrayList<PadMetrics.Presentation>();
            List<Outcome> os = outcomes.get(c);
            for (int i = 0; i < runs.size(); i++) ps.add(new PadMetrics.Presentation(runs.get(i).ref.species, os.get(i).median));
            return ps;
        }

        /**
         * The best variant of a candidate group: lowest EER, then lowest max APCER at 0.85, then fewest bona fide
         * non-responses, then the stricter (smaller) veto or gate value.
         */
        Candidate best(String group) {
            Candidate best = null;
            double[] bestKey = null;
            for (Candidate c : candidates) {
                if (!c.id.equals(group)) continue;
                List<PadMetrics.Presentation> ps = presentations(c);
                double eer = PadMetrics.eer(ps)[0], apcer = PadMetrics.maxApcer(ps, PASSIVE);
                double[] key = {nanHigh(eer), nanHigh(apcer), PadMetrics.nonResponse(ps, false).x};
                if (best == null || less(key, bestKey)) {
                    best = c;
                    bestKey = key;
                }
            }
            return best;
        }

        private static double nanHigh(double v) {
            return Double.isNaN(v) ? Double.MAX_VALUE : v;
        }

        private static boolean less(double[] a, double[] b) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] < b[i] - 1e-12) return true;
                if (a[i] > b[i] + 1e-12) return false;
            }
            return false;
        }

        // ----- report.md -----

        String report(String modelVersion, String date, String dataPath) {
            StringBuilder b = new StringBuilder();
            Candidate prod = candidates.get(0);
            List<PadMetrics.Presentation> prodPs = presentations(prod);
            Map<String, Integer> counts = EvalDataset.counts(refs());
            int subjects = subjects().size();
            b.append("# Part 2 PAD evaluation: PROVISIONAL, ").append(subjects == 1 ? "SINGLE SUBJECT" : subjects + " subjects")
                    .append("\n\n");
            b.append(String.format(Locale.ROOT, "Generated %s by `liveness-eval` `PadEvaluation` over `%s` (model bundle %s). "
                    + "Aggregates only: no images, no landmarks, no per-take rows.%n%n", date, dataPath, modelVersion));
            b.append("## Limitations (read first)\n\n");
            b.append(String.format(Locale.ROOT, "- **%d subject%s.** Every number below comes from %s, one webcam, one room "
                            + "and one phone as the attack instrument. It shows tendencies and failure modes; it is not an "
                            + "estimate of field error rates.%n", subjects, subjects == 1 ? "" : "s",
                    subjects == 1 ? "one person" : subjects + " people"));
            b.append("- Small N: a 0/N result is reported with its one-sided 95% Clopper-Pearson upper bound in brackets "
                    + "(about 3/N, the rule of three). 0/6 means \"at most about 39%\", not \"0%\".\n");
            List<String> missing = new ArrayList<String>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() == 0) missing.add("`" + e.getKey() + "`");
            if (!missing.isEmpty()) b.append("- **Not yet evaluated** (no takes): ").append(join(missing)).append(".\n");
            b.append("- Thresholds recommended here are provisional and need more subjects before they become defaults.\n\n");

            b.append("## Dataset\n\n| class | takes | subjects | frames | conditions |\n|---|---|---|---|---|\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                int frames = 0;
                Map<String, Integer> conds = new LinkedHashMap<String, Integer>();
                List<String> subj = new ArrayList<String>();
                for (TakeData t : runs) {
                    if (!t.ref.species.equals(e.getKey())) continue;
                    frames += t.onnx.size();
                    if (!subj.contains(t.ref.subject)) subj.add(t.ref.subject);
                    String c = t.ref.condition == null ? "-" : t.ref.condition;
                    conds.put(c, (conds.containsKey(c) ? conds.get(c) : 0) + 1);
                }
                StringBuilder cs = new StringBuilder();
                for (Map.Entry<String, Integer> c : conds.entrySet()) cs.append(cs.length() > 0 ? ", " : "").append(c.getKey()).append(" x").append(c.getValue());
                b.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %s |%n", e.getKey(), e.getValue(), subj.size(), frames,
                        e.getValue() == 0 ? "not yet evaluated" : cs.toString()));
            }

            b.append("\n## Method\n\n");
            b.append("- ISO/IEC 30107-3:2023 terms. One presentation = one take (6 s at 10 fps). Every take gets a fresh "
                    + "analyzer, as every session does, and its frames in order with timestamps 1000/fps apart.\n");
            b.append("- Presentation score = the median of the FIRST full window of Part 3's `PassiveEvaluator` (default "
                    + "RESIDENT policy: window 10 eligible frames; eligible = one face, no quality issue, scored, |yaw| and "
                    + "|pitch| within 25 deg). This is exactly what production decides on.\n");
            b.append("- A take with no full window is a **non-response** (APNRR / BPNRR). It is not an acceptance, and it is "
                    + "left out of the APCER and BPCER denominators.\n");
            b.append("- A presentation is classified bona fide when score >= tau. APCER is per species; the headline is the "
                    + "maximum over evaluated species. ACER = (max APCER + BPCER) / 2 is **non-normative in the 2023 "
                    + "edition** and shown only for continuity.\n");
            b.append("- Candidate 1 is production as shipped: the analyzer's livenessScore, which is MEAN with the session "
                    + "moire cap (rolling median of the last 10 single-face moire values >= 1.40 over at least 3 values caps "
                    + "the score at 0.60, forcing a challenge). Candidate 1a recomputes MEAN without the cap (Phase 6 "
                    + "production). Candidates 2 to 7 start from the uncapped model outputs.\n");
            b.append("- Candidates are recomputed per frame from the stored model outputs and go through the same window logic. "
                    + "Moire veto: frame score 0 when moire >= M. Area gate: frames with face area fraction > A are "
                    + "ineligible, as FACE_TOO_LARGE makes them (0.60 is today's quality limit). Candidate 7 combines the "
                    + "best moire veto (" + bestVeto + ") and the best area gate (" + bestGate + ") with MIN or MEAN "
                    + "(best = lowest EER, then lowest max APCER at 0.85, then fewest bona fide non-responses). The heuristic "
                    + "baseline estimates no pose, so its frames are treated as frontal.\n\n");

            b.append("## Candidate comparison\n\n");
            b.append("At tau = 0.85 (today's passiveThreshold), BPCER is the share of bona fide presentations that would NOT "
                    + "pass passively (they go to a challenge, or are rejected).\n\n");
            b.append("| # | candidate | max APCER @0.85 | BPCER @0.85 | ACER @0.85 (non-normative) | EER (tau) | BPCER @ APCER<=5% | "
                    + "BPCER @ APCER<=1% | APNRR | BPNRR | rec. passive | rec. reject |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (Candidate c : candidates) {
                List<PadMetrics.Presentation> ps = presentations(c);
                double[] eer = PadMetrics.eer(ps), b5 = PadMetrics.bpcerAtApcer(ps, 0.05), b1 = PadMetrics.bpcerAtApcer(ps, 0.01);
                PadMetrics.Recommendation r = PadMetrics.recommend(ps, PadMetrics.grid());
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n", c.id, c.name,
                        maxApcerCell(ps, PASSIVE), PadMetrics.bpcer(ps, PASSIVE).format(), pct(PadMetrics.acer(ps, PASSIVE)),
                        Double.isNaN(eer[0]) ? "n/a" : pct(eer[0]) + " (" + tau(eer[1]) + ")",
                        Double.isNaN(b5[0]) ? "n/a" : pct(b5[0]) + " (" + tau(b5[1]) + ")",
                        Double.isNaN(b1[0]) ? "n/a" : pct(b1[0]) + " (" + tau(b1[1]) + ")",
                        PadMetrics.nonResponse(ps, true).format(), PadMetrics.nonResponse(ps, false).format(),
                        num(r.passiveThreshold), num(r.padRejectThreshold)));
            }

            b.append("\n## Three zones with today's settings (passive 0.85, reject 0.30), candidate 1 (production, with the moire cap)\n\n");
            b.append(zoneTable(prod));
            Candidate before = uncapped();
            for (Candidate c : candidates) if (c.id.equals("1a")) before = c;
            b.append("\n### Before the moire cap (candidate 1a)\n\n").append(zoneTable(before));
            b.append("\n").append(capEffect(prod, before));
            b.append("\nPassive pass is a security failure for an attack; PAD reject is a usability failure for bona fide. "
                    + "\"PadMonitor\" counts takes whose 3-frame rolling median ever drops below 0.30, which would end a challenge "
                    + "with PAD_FAILURE.\n");

            b.append("\n## Per-species APCER over thresholds, candidate 1\n\n").append(sweepTable(prodPs));
            Candidate top = bestOverall();
            if (top != prod) {
                b.append("\n## Per-species APCER over thresholds, candidate ").append(top.id).append(" (").append(top.name)
                        .append(", lowest EER)\n\n").append(sweepTable(presentations(top)));
                b.append("\n### Three zones, candidate ").append(top.id).append(" (").append(top.name).append(")\n\n").append(zoneTable(top));
            }

            b.append("\n## Close-up analysis\n\n").append(closeUpTable());
            b.append("\n").append(closeUpCandidates());

            b.append("\n## Recommended thresholds (provisional, ").append(subjects == 1 ? "1 subject" : subjects + " subjects").append(")\n\n");
            b.append("passiveThreshold = smallest grid tau with APCER = 0 for every species, + 0.02, capped at 0.99. "
                    + "padRejectThreshold = largest grid tau with no bona fide score below it, - 0.05, and below passiveThreshold.\n\n");
            for (Candidate c : new Candidate[] {prod, top}) {
                PadMetrics.Recommendation r = PadMetrics.recommend(presentations(c), PadMetrics.grid());
                b.append(String.format(Locale.ROOT, "- Candidate %s (%s): passiveThreshold **%s**, padRejectThreshold **%s**%s%n",
                        c.id, c.name, num(r.passiveThreshold), num(r.padRejectThreshold),
                        r.notes.isEmpty() ? "" : ". Notes: " + join(r.notes)));
                if (top == prod) break;
            }
            return b.toString();
        }

        Candidate bestOverall() {
            Candidate best = candidates.get(0);
            double bestEer = nanHigh(PadMetrics.eer(presentations(best))[0]);
            for (Candidate c : candidates) {
                double e = nanHigh(PadMetrics.eer(presentations(c))[0]);
                if (e < bestEer - 1e-12) {
                    best = c;
                    bestEer = e;
                }
            }
            return best;
        }

        /** What the cap changed: takes moved out of the passive zone, per class, and any newly rejected takes. */
        String capEffect(Candidate after, Candidate before) {
            Map<String, int[]> moved = new LinkedHashMap<String, int[]>(); // {passive -> challenge, newly rejected, responding}
            for (int i = 0; i < runs.size(); i++) {
                String s = runs.get(i).ref.species;
                if (!moved.containsKey(s)) moved.put(s, new int[3]);
                PadMetrics.Zone z0 = PadMetrics.zone(outcomes.get(before).get(i).median, PASSIVE, REJECT);
                PadMetrics.Zone z1 = PadMetrics.zone(outcomes.get(after).get(i).median, PASSIVE, REJECT);
                if (z0 == PadMetrics.Zone.PASSIVE_PASS && z1 != PadMetrics.Zone.PASSIVE_PASS) moved.get(s)[0]++;
                if (z0 != PadMetrics.Zone.PAD_REJECT && z1 == PadMetrics.Zone.PAD_REJECT) moved.get(s)[1]++;
                if (z1 != PadMetrics.Zone.NO_RESPONSE) moved.get(s)[2]++;
            }
            StringBuilder b = new StringBuilder("Moire cap effect (candidate 1a -> 1):\n\n| class | responding takes | moved out of passive pass | newly PAD-rejected |\n|---|---|---|---|\n");
            for (Map.Entry<String, int[]> e : moved.entrySet()) {
                int[] m = e.getValue();
                b.append(String.format(Locale.ROOT, "| %s | %d | %s | %s |%n", e.getKey(), m[2], new PadMetrics.Rate(m[0], m[2]).format(),
                        new PadMetrics.Rate(m[1], m[2]).format()));
            }
            return b.toString();
        }

        private String maxApcerCell(List<PadMetrics.Presentation> ps, float tau) {
            String worst = null;
            PadMetrics.Rate worstRate = null;
            for (String s : PadMetrics.species(ps)) {
                PadMetrics.Rate r = PadMetrics.apcer(ps, s, tau);
                if (r.n > 0 && (worstRate == null || r.value() > worstRate.value())) {
                    worst = s;
                    worstRate = r;
                }
            }
            return worstRate == null ? "n/a" : worstRate.format() + " " + worst;
        }

        String zoneTable(Candidate c) {
            List<PadMetrics.Presentation> ps = presentations(c);
            Map<String, int[]> z = PadMetrics.zones(ps, PASSIVE, REJECT);
            StringBuilder b = new StringBuilder("| class | N | passive pass | challenge | PAD reject | non-response | PadMonitor < 0.30 |\n"
                    + "|---|---|---|---|---|---|---|\n");
            List<Outcome> os = outcomes.get(c);
            for (Map.Entry<String, int[]> e : z.entrySet()) {
                int[] n = e.getValue();
                int total = n[PadMetrics.Zone.values().length], flagged = 0;
                for (int i = 0; i < runs.size(); i++) {
                    float m = os.get(i).minRolling;
                    if (runs.get(i).ref.species.equals(e.getKey()) && !Float.isNaN(m) && m < REJECT) flagged++;
                }
                b.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s |%n", e.getKey(), total,
                        new PadMetrics.Rate(n[0], total).format(), new PadMetrics.Rate(n[1], total).format(),
                        new PadMetrics.Rate(n[2], total).format(), new PadMetrics.Rate(n[3], total).format(),
                        new PadMetrics.Rate(flagged, total).format()));
            }
            return b.toString();
        }

        String sweepTable(List<PadMetrics.Presentation> ps) {
            List<String> species = PadMetrics.species(ps);
            StringBuilder b = new StringBuilder("| tau |");
            for (String s : species) b.append(" APCER ").append(s).append(" |");
            b.append(" max APCER | BPCER | ACER (non-normative) |\n|---|");
            for (int i = 0; i < species.size() + 3; i++) b.append("---|");
            b.append('\n');
            for (float tau : PadMetrics.grid()) {
                b.append("| ").append(tau(tau)).append(" |");
                for (String s : species) b.append(' ').append(PadMetrics.apcer(ps, s, tau).format()).append(" |");
                b.append(' ').append(pct(PadMetrics.maxApcer(ps, tau))).append(" | ").append(PadMetrics.bpcer(ps, tau).format())
                        .append(" | ").append(pct(PadMetrics.acer(ps, tau))).append(" |\n");
            }
            return b.toString();
        }

        /** Frame-level face size and moire per class (screen_photo_close against bona fide "close"). */
        String closeUpTable() {
            Map<String, List<FrameRec>> groups = new LinkedHashMap<String, List<FrameRec>>();
            for (TakeData t : runs) {
                String key = t.ref.species + " (" + (t.ref.condition == null ? "-" : t.ref.condition) + ")";
                if (!groups.containsKey(key)) groups.put(key, new ArrayList<FrameRec>());
                for (FrameRec f : t.onnx) if (f.faces == 1) groups.get(key).add(f);
            }
            StringBuilder b = new StringBuilder("Frames with exactly one face. Values are median [10th - 90th percentile].\n\n"
                    + "| class | frames | face area fraction | moire | glare | MEAN score (no cap) | area > 0.20 / 0.25 / 0.35 / 0.60 | "
                    + "moire >= 1.3 / 1.4 / 1.5 / 1.6 / 1.8 / 2.0 / 2.2 |\n|---|---|---|---|---|---|---|---|\n");
            for (Map.Entry<String, List<FrameRec>> e : groups.entrySet()) {
                List<FrameRec> fs = e.getValue();
                StringBuilder areas = new StringBuilder(), moires = new StringBuilder();
                for (float a : AREA_GATES) areas.append(areas.length() > 0 ? " / " : "").append(pct(frac(fs, a, true)));
                for (float m : MOIRE_VETOES) moires.append(moires.length() > 0 ? " / " : "").append(pct(frac(fs, m, false)));
                b.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s |%n", e.getKey(), fs.size(),
                        spread(fs, 0), spread(fs, 1), spread(fs, 2), spread(fs, 3), areas, moires));
            }
            return b.toString();
        }

        /** For candidates 4 to 7: does screen_photo_close stay out of the passive zone at no bona fide cost? */
        String closeUpCandidates() {
            Candidate baseline = candidates.get(0);
            for (Candidate c : candidates) if (c.id.equals("1a")) baseline = c;
            Map<String, int[]> base = PadMetrics.zones(presentations(baseline), PASSIVE, REJECT);
            int[] bonaBase = base.get(EvalDataset.BONA_FIDE);
            StringBuilder b = new StringBuilder("Zones at 0.85 / 0.30. \"Zero bona fide cost\" = bona fide passive passes, "
                    + "challenges, rejects and non-responses all unchanged from candidate 1a (no cap). For candidate 1 a bona "
                    + "fide take moved to a challenge is a usability cost, never a rejection.\n\n"
                    + "| # | candidate | screen_photo_close passive pass | of which *_fill takes | screen_photo passive pass | bona fide pass / challenge / reject / no-response | zero bona fide cost |\n"
                    + "|---|---|---|---|---|---|---|\n");
            for (Candidate c : candidates) {
                if (!c.id.equals("1") && (c.id.compareTo("4") < 0 || c.id.compareTo("7") > 0)) continue;
                Map<String, int[]> z = PadMetrics.zones(presentations(c), PASSIVE, REJECT);
                int[] bona = z.get(EvalDataset.BONA_FIDE);
                boolean same = bonaBase != null && bona != null && Arrays.equals(bona, bonaBase);
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n", c.id, c.name, passCell(z, "screen_photo_close"),
                        fillPasses(c), passCell(z, "screen_photo"),
                        bona == null ? "n/a" : bona[0] + " / " + bona[1] + " / " + bona[2] + " / " + bona[3], same ? "yes" : "no"));
            }
            return b.toString();
        }

        /** Passive passes among screen_photo_close takes recorded with the face-size readout (conditions *_fill). */
        private String fillPasses(Candidate c) {
            int x = 0, n = 0;
            for (int i = 0; i < runs.size(); i++) {
                EvalDataset.TakeRef r = runs.get(i).ref;
                if (!r.species.equals("screen_photo_close") || r.condition == null || !r.condition.endsWith("_fill")) continue;
                n++;
                if (PadMetrics.zone(outcomes.get(c).get(i).median, PASSIVE, REJECT) == PadMetrics.Zone.PASSIVE_PASS) x++;
            }
            return new PadMetrics.Rate(x, n).format();
        }

        private static String passCell(Map<String, int[]> z, String species) {
            int[] n = z.get(species);
            return n == null ? "n/a" : new PadMetrics.Rate(n[0], n[PadMetrics.Zone.values().length]).format();
        }

        // ----- CSVs -----

        String scoresCsv() {
            StringBuilder b = new StringBuilder("subject,species,take,condition,frames,eligible");
            for (Candidate c : candidates) b.append(",\"").append(c.id).append(' ').append(c.name).append('"');
            b.append(",min_rolling_median,zone\n");
            Candidate prod = candidates.get(0);
            for (int i = 0; i < runs.size(); i++) {
                TakeData t = runs.get(i);
                Outcome p = outcomes.get(prod).get(i);
                b.append(t.ref.subject).append(',').append(t.ref.species).append(',').append(t.ref.take).append(',')
                        .append(t.ref.condition == null ? "" : t.ref.condition).append(',').append(t.onnx.size()).append(',').append(p.eligible);
                for (Candidate c : candidates) {
                    float m = outcomes.get(c).get(i).median;
                    b.append(',').append(Float.isNaN(m) ? "NR" : num4(m));
                }
                b.append(',').append(num4(p.minRolling)).append(',').append(PadMetrics.zone(p.median, PASSIVE, REJECT)).append('\n');
            }
            return b.toString();
        }

        String framesCsv() {
            StringBuilder b = new StringBuilder("subject,species,take,condition,frame,t_ms,faces,issues,yaw,pitch,roll,area,moire,"
                    + "glare,v2,v1se,mean,min,moire_med,moire_cap,heuristic_faces,heuristic_score\n");
            Candidate min = new Candidate("4", "MIN", Base.MIN, Float.NaN, Float.NaN);
            for (TakeData t : runs) {
                for (int i = 0; i < t.onnx.size(); i++) {
                    FrameRec f = t.onnx.get(i);
                    FrameRec h = t.heuristic == null || i >= t.heuristic.size() ? null : t.heuristic.get(i);
                    StringBuilder issues = new StringBuilder();
                    for (QualityIssue q : f.issues) issues.append(issues.length() > 0 ? "|" : "").append(q);
                    b.append(t.ref.subject).append(',').append(t.ref.species).append(',').append(t.ref.take).append(',')
                            .append(t.ref.condition == null ? "" : t.ref.condition).append(',').append(i).append(',')
                            .append(Math.round(i * 1000.0 / t.ref.fps)).append(',').append(f.faces).append(',').append(issues)
                            .append(',').append(num2(f.yaw)).append(',').append(num2(f.pitch)).append(',').append(num2(f.roll))
                            .append(',').append(num4(f.area)).append(',').append(num4(f.moire)).append(',').append(num4(f.glare))
                            .append(',').append(num4(f.v2)).append(',').append(num4(f.v1se)).append(',').append(num4(f.score))
                            .append(',').append(num4(min.score(f))).append(',').append(num4(f.moireMed)).append(',')
                            .append(f.capped ? "1" : "0").append(',').append(h == null ? "" : String.valueOf(h.faces))
                            .append(',').append(h == null ? "" : num4(h.score)).append('\n');
                }
            }
            return b.toString();
        }

        /** Per take: presentation score (candidate 1) against median face area, moire and glare. */
        String scatterCsv() {
            StringBuilder b = new StringBuilder("subject,species,take,condition,presentation_median,median_area,median_moire,"
                    + "p90_moire,median_glare\n");
            Candidate prod = candidates.get(0);
            for (int i = 0; i < runs.size(); i++) {
                TakeData t = runs.get(i);
                List<FrameRec> one = new ArrayList<FrameRec>();
                for (FrameRec f : t.onnx) if (f.faces == 1) one.add(f);
                float m = outcomes.get(prod).get(i).median;
                b.append(t.ref.subject).append(',').append(t.ref.species).append(',').append(t.ref.take).append(',')
                        .append(t.ref.condition == null ? "" : t.ref.condition).append(',').append(Float.isNaN(m) ? "NR" : num4(m))
                        .append(',').append(num4(quantile(one, 0, 0.5))).append(',').append(num4(quantile(one, 1, 0.5)))
                        .append(',').append(num4(quantile(one, 1, 0.9))).append(',').append(num4(quantile(one, 2, 0.5))).append('\n');
            }
            return b.toString();
        }

        private List<EvalDataset.TakeRef> refs() {
            List<EvalDataset.TakeRef> r = new ArrayList<EvalDataset.TakeRef>();
            for (TakeData t : runs) r.add(t.ref);
            return r;
        }

        private List<String> subjects() {
            List<String> s = new ArrayList<String>();
            for (TakeData t : runs) if (!s.contains(t.ref.subject)) s.add(t.ref.subject);
            return s;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // small helpers
    // ---------------------------------------------------------------------------------------------------------

    /** 0 = area, 1 = moire, 2 = glare, 3 = MEAN of the model outputs without the session cap. */
    private static float field(FrameRec f, int k) {
        switch (k) {
            case 0: return f.area;
            case 1: return f.moire;
            case 2: return f.glare;
            default: return Float.isNaN(f.v2) || Float.isNaN(f.v1se) ? Float.NaN : (f.v2 + f.v1se) / 2f;
        }
    }

    /** Nearest-rank quantile of a field over the frames that have it; NaN when none do. */
    static float quantile(List<FrameRec> fs, int k, double q) {
        List<Float> v = new ArrayList<Float>();
        for (FrameRec f : fs) if (!Float.isNaN(field(f, k))) v.add(field(f, k));
        if (v.isEmpty()) return Float.NaN;
        Collections.sort(v);
        int i = (int) Math.ceil(q * v.size()) - 1;
        return v.get(Math.max(0, Math.min(v.size() - 1, i)));
    }

    private static String spread(List<FrameRec> fs, int k) {
        float m = quantile(fs, k, 0.5);
        if (Float.isNaN(m)) return "n/a";
        return String.format(Locale.ROOT, "%.3f [%.3f - %.3f]", m, quantile(fs, k, 0.1), quantile(fs, k, 0.9));
    }

    /** Share of frames with area above (area = true) or moire at or above (false) the value. */
    private static double frac(List<FrameRec> fs, float v, boolean area) {
        int n = 0, x = 0;
        for (FrameRec f : fs) {
            float s = area ? f.area : f.moire;
            if (Float.isNaN(s)) continue;
            n++;
            if (area ? s > v : s >= v) x++;
        }
        return n == 0 ? Double.NaN : (double) x / n;
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100 * v);
    }

    private static String tau(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, v >= 0.99 ? "%.3f" : "%.2f", v);
    }

    private static String num(float v) {
        return Float.isNaN(v) ? "none" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String num2(float v) {
        return Float.isNaN(v) ? "" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String num4(float v) {
        return Float.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) b.append(b.length() > 0 ? "; " : "").append(s);
        return b.toString();
    }
}
