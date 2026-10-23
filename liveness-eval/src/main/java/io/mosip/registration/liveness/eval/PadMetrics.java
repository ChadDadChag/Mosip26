package io.mosip.registration.liveness.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * ISO/IEC 30107-3:2023 presentation-level metrics over one score per presentation (pure logic, no I/O).
 *
 * <ul>
 *   <li>A presentation is classified bona fide when its score is at or above the threshold tau, as the engine does
 *       ({@code median >= passiveThreshold} passes).</li>
 *   <li>APCER(species) = attack presentations of that species classified bona fide / responding presentations of
 *       that species. The headline value is the maximum over the evaluated species.</li>
 *   <li>BPCER = bona fide presentations classified as attacks / responding bona fide presentations.</li>
 *   <li>Non-responses (no decision at all, e.g. no full passive window) are counted separately (APNRR, BPNRR) and
 *       left out of both denominators: a non-response is not an acceptance.</li>
 *   <li>ACER = (max APCER + BPCER) / 2 is reported for continuity only; it is non-normative in the 2023 edition.</li>
 * </ul>
 */
final class PadMetrics {

    private PadMetrics() { }

    /** One presentation (a take): species, and the score or NaN for a non-response. */
    static final class Presentation {
        final String species;
        final float score;

        Presentation(String species, float score) {
            this.species = species;
            this.score = score;
        }

        boolean attack() {
            return !species.equals(EvalDataset.BONA_FIDE);
        }

        boolean responded() {
            return !Float.isNaN(score);
        }
    }

    /** x of n. */
    static final class Rate {
        final int x, n;

        Rate(int x, int n) {
            this.x = x;
            this.n = n;
        }

        double value() {
            return n == 0 ? Double.NaN : (double) x / n;
        }

        /** "x/N (pct)", with the one-sided 95% Clopper-Pearson upper bound when x = 0. */
        String format() {
            if (n == 0) return "n/a";
            String s = String.format(Locale.ROOT, "%d/%d (%.1f%%)", x, n, 100 * value());
            return x == 0 ? s + String.format(Locale.ROOT, " [<=%.1f%%]", 100 * clopperPearsonUpper(0, n, 0.95)) : s;
        }
    }

    /** The default threshold grid for the report tables. */
    static List<Float> grid() {
        List<Float> g = new ArrayList<Float>();
        for (int i = 1; i <= 19; i++) g.add(Math.round(i * 5f) / 100f);
        g.add(0.99f);
        g.add(0.999f);
        return g;
    }

    /** Attack species present in the presentations, in protocol order (unknown species last). */
    static List<String> species(List<Presentation> ps) {
        List<String> out = new ArrayList<String>();
        for (String s : EvalDataset.attackSpecies()) for (Presentation p : ps) if (p.species.equals(s)) { out.add(s); break; }
        for (Presentation p : ps) if (p.attack() && !out.contains(p.species)) out.add(p.species);
        return out;
    }

    static Rate apcer(List<Presentation> ps, String species, float tau) {
        int x = 0, n = 0;
        for (Presentation p : ps) {
            if (!p.species.equals(species) || !p.responded()) continue;
            n++;
            if (p.score >= tau) x++;
        }
        return new Rate(x, n);
    }

    /** Maximum APCER over the attack species that have responding presentations; NaN when there are none. */
    static double maxApcer(List<Presentation> ps, float tau) {
        double max = Double.NaN;
        for (String s : species(ps)) {
            double v = apcer(ps, s, tau).value();
            if (!Double.isNaN(v) && (Double.isNaN(max) || v > max)) max = v;
        }
        return max;
    }

    static Rate bpcer(List<Presentation> ps, float tau) {
        int x = 0, n = 0;
        for (Presentation p : ps) {
            if (p.attack() || !p.responded()) continue;
            n++;
            if (p.score < tau) x++;
        }
        return new Rate(x, n);
    }

    static double acer(List<Presentation> ps, float tau) {
        return (maxApcer(ps, tau) + bpcer(ps, tau).value()) / 2;
    }

    /** Non-responses among attack (true) or bona fide (false) presentations: APNRR / BPNRR. */
    static Rate nonResponse(List<Presentation> ps, boolean attacks) {
        int x = 0, n = 0;
        for (Presentation p : ps) {
            if (p.attack() != attacks) continue;
            n++;
            if (!p.responded()) x++;
        }
        return new Rate(x, n);
    }

    /** Every threshold at which a decision can change: each distinct score, plus 0 and just above 1. */
    static List<Float> cutPoints(List<Presentation> ps) {
        TreeSet<Float> t = new TreeSet<Float>();
        t.add(0f);
        t.add(Math.nextUp(1f));
        for (Presentation p : ps) {
            if (!p.responded()) continue;
            t.add(p.score);
            t.add(Math.nextUp(p.score));
        }
        return new ArrayList<Float>(t);
    }

    /** {EER, tau}: at the cut point where max APCER and BPCER are closest, their mean (ties: the lower mean). */
    static double[] eer(List<Presentation> ps) {
        double best = Double.NaN, gap = Double.POSITIVE_INFINITY, tauBest = Double.NaN;
        for (float tau : cutPoints(ps)) {
            double a = maxApcer(ps, tau), b = bpcer(ps, tau).value();
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            double g = Math.abs(a - b), m = (a + b) / 2;
            if (g < gap - 1e-12 || (Math.abs(g - gap) <= 1e-12 && m < best)) {
                gap = g;
                best = m;
                tauBest = tau;
            }
        }
        return new double[] {best, tauBest};
    }

    /** {BPCER, tau}: the lowest BPCER at any threshold where max APCER is at most the target. */
    static double[] bpcerAtApcer(List<Presentation> ps, double maxApcer) {
        double best = Double.NaN, tauBest = Double.NaN;
        for (float tau : cutPoints(ps)) {
            double a = maxApcer(ps, tau), b = bpcer(ps, tau).value();
            if (Double.isNaN(a) || Double.isNaN(b) || a > maxApcer + 1e-12) continue;
            if (Double.isNaN(best) || b < best) {
                best = b;
                tauBest = tau;
            }
        }
        return new double[] {best, tauBest};
    }

    /** Where a presentation lands with the engine's two thresholds. */
    enum Zone { PASSIVE_PASS, CHALLENGE, PAD_REJECT, NO_RESPONSE }

    static Zone zone(float score, float passive, float reject) {
        if (Float.isNaN(score)) return Zone.NO_RESPONSE;
        if (score < reject) return Zone.PAD_REJECT;
        return score >= passive ? Zone.PASSIVE_PASS : Zone.CHALLENGE;
    }

    /** Zone counts per class (bona fide first, then each attack species), over all presentations of the class. */
    static Map<String, int[]> zones(List<Presentation> ps, float passive, float reject) {
        Map<String, int[]> z = new LinkedHashMap<String, int[]>();
        List<String> classes = new ArrayList<String>();
        classes.add(EvalDataset.BONA_FIDE);
        classes.addAll(species(ps));
        for (String c : classes) z.put(c, new int[Zone.values().length + 1]);
        for (Presentation p : ps) {
            int[] counts = z.get(p.species);
            if (counts == null) continue;
            counts[zone(p.score, passive, reject).ordinal()]++;
            counts[Zone.values().length]++; // total
        }
        return z;
    }

    /** Provisional thresholds from the grid, with the reasons; see {@link #recommend}. */
    static final class Recommendation {
        final float passiveThreshold, padRejectThreshold;
        final List<String> notes;

        Recommendation(float passiveThreshold, float padRejectThreshold, List<String> notes) {
            this.passiveThreshold = passiveThreshold;
            this.padRejectThreshold = padRejectThreshold;
            this.notes = notes;
        }
    }

    /**
     * passiveThreshold = smallest grid tau with APCER = 0 for every species, + 0.02, capped at 0.99;
     * padRejectThreshold = largest grid tau with no bona fide score below it, - 0.05, and below passiveThreshold.
     * NaN with a note when the data allows no such value.
     */
    static Recommendation recommend(List<Presentation> ps, List<Float> grid) {
        List<String> notes = new ArrayList<String>();
        float passive = Float.NaN;
        for (float tau : grid) {
            double a = maxApcer(ps, tau);
            if (Double.isNaN(a)) {
                notes.add("no responding attack presentations: passiveThreshold cannot be derived");
                break;
            }
            if (a == 0) {
                passive = Math.min(0.99f, round3(tau + 0.02f));
                if (tau + 0.02f > 0.99f) notes.add("passiveThreshold capped at 0.99 (zero APCER first reached at " + tau + ")");
                break;
            }
        }
        if (Float.isNaN(passive) && notes.isEmpty()) {
            notes.add("an attack reaches >= " + grid.get(grid.size() - 1) + ": no grid value keeps every species out of the passive zone");
        }
        float reject = Float.NaN;
        List<Float> desc = new ArrayList<Float>(grid);
        Collections.reverse(desc);
        for (float tau : desc) {
            Rate b = bpcer(ps, tau);
            if (b.n == 0) {
                notes.add("no responding bona fide presentations: padRejectThreshold cannot be derived");
                break;
            }
            if (b.x == 0) {
                reject = Math.max(0f, round3(tau - 0.05f));
                break;
            }
        }
        if (Float.isNaN(reject) && bpcer(ps, 0.5f).n > 0) notes.add("a bona fide score is below " + grid.get(0) + ": no safe padRejectThreshold");
        if (!Float.isNaN(reject) && !Float.isNaN(passive) && reject >= passive) {
            notes.add("padRejectThreshold " + reject + " would not be below passiveThreshold " + passive + "; lowered to "
                    + round3(passive - 0.05f));
            reject = round3(passive - 0.05f);
        }
        return new Recommendation(passive, reject, notes);
    }

    private static float round3(float v) {
        return Math.round(v * 1000f) / 1000f;
    }

    /**
     * One-sided upper confidence bound for a binomial proportion (Clopper-Pearson): the p at which observing x or
     * fewer successes in n trials has probability 1 - confidence. For x = 0 this is 1 - (1 - confidence)^(1/n),
     * about 3/n at 95% (the rule of three).
     */
    static double clopperPearsonUpper(int x, int n, double confidence) {
        if (n <= 0) return Double.NaN;
        if (x >= n) return 1;
        double alpha = 1 - confidence, lo = (double) x / n, hi = 1;
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            if (binomialCdf(x, n, mid) > alpha) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** P(X <= x) for X ~ Binomial(n, p), summed in log space. */
    static double binomialCdf(int x, int n, double p) {
        if (p <= 0) return 1;
        if (p >= 1) return x >= n ? 1 : 0;
        double sum = 0, logP = Math.log(p), logQ = Math.log1p(-p);
        for (int k = 0; k <= x; k++) sum += Math.exp(logChoose(n, k) + k * logP + (n - k) * logQ);
        return Math.min(1, sum);
    }

    private static double logChoose(int n, int k) {
        double s = 0;
        for (int i = 1; i <= k; i++) s += Math.log(n - k + i) - Math.log(i);
        return s;
    }
}
