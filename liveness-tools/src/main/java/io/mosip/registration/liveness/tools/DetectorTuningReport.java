package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sweeps the detectors' main TUNE thresholds over labelled traces and recommends values.
 *
 * <p>For each challenge type T: positives are traces labelled T; negatives are live traces with any other label
 * (neutral and the other actions: a smile must not count as a blink). Per candidate value it reports the detection
 * rate (TPR), false detection rate (FPR), wrong-action rate on positives and mean time to detect. The recommendation
 * maximises TPR - FPR, then prefers faster detection. Attack traces are excluded here: they are PAD's job.
 *
 * <p>A second table shows the passive threshold trade-off on the traces' window medians (ISO/IEC 30107-3 terms):
 * APCER = attack presentations accepted without a challenge (median ≥ passiveThreshold); challenge rate = bona fide
 * sessions sent to a challenge; BPCER = bona fide presentations rejected as attacks (median < padRejectThreshold).
 *
 * <pre>java -cp ... io.mosip.registration.liveness.tools.DetectorTuningReport path/to/traces</pre>
 * Defaults change only via a reviewed EngineTuning change (engine values) or a contract PR (policy values).
 */
public final class DetectorTuningReport {

    private DetectorTuningReport() { }

    /** One tunable threshold and the challenge types that use it. */
    interface Knob {
        String name();
        ChallengeType[] types();
        float current(EngineTuning t);
        void set(EngineTuning t, float v);
        float[] candidates();
    }

    static final Knob BLINK = new Knob() {
        public String name() { return "blinkClosedRatio"; }
        public ChallengeType[] types() { return new ChallengeType[] {ChallengeType.BLINK}; }
        public float current(EngineTuning t) { return t.blinkClosedRatio; }
        public void set(EngineTuning t, float v) { t.blinkClosedRatio = v; }
        public float[] candidates() { return new float[] {0.45f, 0.5f, 0.55f, 0.6f, 0.65f, 0.7f, 0.75f}; }
    };
    static final Knob SMILE = new Knob() {
        public String name() { return "smileWidthIncrease"; }
        public ChallengeType[] types() { return new ChallengeType[] {ChallengeType.SMILE}; }
        public float current(EngineTuning t) { return t.smileWidthIncrease; }
        public void set(EngineTuning t, float v) { t.smileWidthIncrease = v; }
        public float[] candidates() { return new float[] {0.10f, 0.12f, 0.15f, 0.18f, 0.21f, 0.24f, 0.28f}; }
    };
    static final Knob TURN = new Knob() {
        public String name() { return "turnMinDeg"; }
        public ChallengeType[] types() { return new ChallengeType[] {ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT}; }
        public float current(EngineTuning t) { return t.turnMinDeg; }
        public void set(EngineTuning t, float v) { t.turnMinDeg = v; }
        public float[] candidates() { return new float[] {12, 15, 18, 20, 22, 25, 30}; }
    };
    static final Knob LOOK = new Knob() {
        public String name() { return "lookMinDeg"; }
        public ChallengeType[] types() { return new ChallengeType[] {ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN}; }
        public float current(EngineTuning t) { return t.lookMinDeg; }
        public void set(EngineTuning t, float v) { t.lookMinDeg = v; }
        public float[] candidates() { return new float[] {8, 10, 12, 13, 15, 18, 21}; }
    };
    static final Knob[] KNOBS = {BLINK, SMILE, TURN, LOOK};

    /** One row of the sweep. */
    public static final class Row {
        public final float value;
        public final double tpr, fpr, wrongRate;
        public final long meanTtdMs;
        public final int positives, negatives;

        Row(float value, double tpr, double fpr, double wrongRate, long meanTtdMs, int positives, int negatives) {
            this.value = value; this.tpr = tpr; this.fpr = fpr; this.wrongRate = wrongRate; this.meanTtdMs = meanTtdMs;
            this.positives = positives; this.negatives = negatives;
        }
    }

    public static String label(ChallengeType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static ChallengeType labelled(Trace t, Knob k) {
        for (ChallengeType c : k.types()) if (label(c).equals(t.label)) return c;
        return null;
    }

    /**
     * Positives: live traces labelled with one of the knob's types, replayed through that type's detector.
     * Negatives: other live traces; a false detection is any of the knob's detectors firing.
     */
    public static List<Row> sweep(List<Trace> traces, Knob k) {
        List<Row> rows = new ArrayList<Row>();
        for (float v : k.candidates()) {
            EngineTuning t = EngineTuning.defaults();
            k.set(t, v);
            int pos = 0, tp = 0, wrong = 0, neg = 0, fp = 0;
            long ttd = 0;
            for (Trace tr : traces) {
                if (tr.isAttack()) continue;
                ChallengeType c = labelled(tr, k);
                if (c != null) {
                    pos++;
                    TraceReplayer.DetectorRun run = TraceReplayer.runDetector(tr, c, t);
                    if (run.detected) { tp++; ttd += run.timeToDetectMs; }
                    if (run.wrongAction) wrong++;
                } else {
                    neg++;
                    for (ChallengeType other : k.types()) {
                        if (TraceReplayer.runDetector(tr, other, t).detected) { fp++; break; }
                    }
                }
            }
            rows.add(new Row(v, pos == 0 ? Double.NaN : (double) tp / pos, neg == 0 ? Double.NaN : (double) fp / neg,
                    pos == 0 ? Double.NaN : (double) wrong / pos, tp == 0 ? -1 : ttd / tp, pos, neg));
        }
        return rows;
    }

    /**
     * Best row by TPR - FPR. Ties keep the current default (never loosen a threshold without evidence), otherwise
     * prefer faster detection. Null when there are no positives.
     */
    public static Row recommend(List<Row> rows, float current) {
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Row r : rows) if (!Double.isNaN(r.tpr)) bestScore = Math.max(bestScore, score(r));
        if (bestScore == Double.NEGATIVE_INFINITY) return null;
        Row best = null;
        for (Row r : rows) {
            if (Double.isNaN(r.tpr) || score(r) < bestScore - 1e-9) continue;
            if (Math.abs(r.value - current) < 1e-6) return r;
            if (best == null || (r.meanTtdMs >= 0 && (best.meanTtdMs < 0 || r.meanTtdMs < best.meanTtdMs))) best = r;
        }
        return best;
    }

    private static double score(Row r) {
        return r.tpr - (Double.isNaN(r.fpr) ? 0 : r.fpr);
    }

    public static String report(List<Trace> traces) {
        StringBuilder b = new StringBuilder();
        int synthetic = 0;
        for (Trace t : traces) if (Boolean.TRUE.equals(t.header.get("synthetic"))) synthetic++;
        b.append(String.format(Locale.ROOT, "Detector tuning report: %d traces (%d synthetic)%n", traces.size(), synthetic));
        if (synthetic > 0) {
            b.append("WARNING: synthetic traces only validate the pipeline; tune on real team recordings.\n");
        }
        StringBuilder recommended = new StringBuilder();
        EngineTuning defaults = EngineTuning.defaults();
        for (Knob k : KNOBS) {
            List<Row> rows = sweep(traces, k);
            if (rows.get(0).positives == 0) continue;
            b.append(String.format(Locale.ROOT, "%n%s  (%s, current %.2f; %d positive, %d negative traces)%n",
                    java.util.Arrays.toString(k.types()), k.name(), k.current(defaults), rows.get(0).positives,
                    rows.get(0).negatives));
            if (k == BLINK) {
                b.append("  note: live people blink spontaneously, so blink 'false detections' on other live traces are\n"
                        + "  real blinks and harmless; weigh TPR and time to detect. Photos/screens are PAD's job.\n");
            }
            b.append("   value    TPR    FPR  wrong  meanTTD\n");
            for (Row r : rows) {
                b.append(String.format(Locale.ROOT, "  %6.2f  %5.2f  %5.2f  %5.2f  %6s%n", r.value, r.tpr, r.fpr,
                        r.wrongRate, r.meanTtdMs < 0 ? "-" : r.meanTtdMs + "ms"));
            }
            Row best = recommend(rows, k.current(defaults));
            if (best != null) {
                b.append(String.format(Locale.ROOT, "  -> recommended %s = %.2f%n", k.name(), best.value));
                recommended.append(String.format(Locale.ROOT, "  %s = %.2f%s%n", k.name(), best.value,
                        Math.abs(best.value - k.current(defaults)) < 1e-6 ? " (unchanged)" : ""));
            }
        }
        b.append(passiveTable(traces));
        b.append("\nRecommended EngineTuning values:\n")
                .append(recommended.length() == 0 ? "  (no labelled actions)\n" : recommended);
        return b.toString();
    }

    static String passiveTable(List<Trace> traces) {
        LivenessPolicy p = new LivenessPolicy();
        List<Float> bona = new ArrayList<Float>(), attack = new ArrayList<Float>();
        for (Trace t : traces) {
            TraceReplayer.PassiveRun run = TraceReplayer.runPassive(t, p, EngineTuning.defaults());
            if (run.verdict == PassiveEvaluator.Verdict.PENDING || Float.isNaN(run.median)) continue;
            (t.isAttack() ? attack : bona).add(run.median);
        }
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%nPassive thresholds on window medians (%d bona fide, %d attack traces)%n",
                bona.size(), attack.size()));
        b.append("  APCER(t): attacks with median >= t (accepted without a challenge if t is passiveThreshold)\n");
        b.append("  bona<t:   bona fide with median < t (challenge rate if t is passiveThreshold; BPCER if padRejectThreshold)\n");
        b.append("  threshold  APCER  bona<t\n");
        for (int i = 4; i <= 19; i++) {
            float t = i * 0.05f;
            b.append(String.format(Locale.ROOT, "  %9.2f  %5.2f  %6.2f%n", t, frac(attack, t, true), frac(bona, t, false)));
        }
        return b.toString();
    }

    private static double frac(List<Float> v, float t, boolean atLeast) {
        if (v.isEmpty()) return Double.NaN;
        int n = 0;
        for (float f : v) if (atLeast ? f >= t : f < t) n++;
        return (double) n / v.size();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: DetectorTuningReport <trace directory>");
            System.exit(2);
        }
        System.out.print(report(Trace.readDir(new File(args[0]))));
    }
}
