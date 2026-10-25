package io.mosip.registration.liveness.analyzer.pad;

/**
 * Session moire cap ({@code reference_pipeline.session_scores}). A phone or monitor held close to the camera can
 * fool the MiniFASNet models, but its pixel grid keeps the face crop's moire high frame after frame. The analyzer is
 * one per session, so it can keep a rolling median of the moire of the last {@code window} single-face frames:
 * <ul>
 *   <li>every frame with exactly one face adds its moire, also when it has no score (pose-gated or no mesh): a
 *       screen is a screen at any angle. Any other face count clears the buffer (a new face may have arrived);</li>
 *   <li>once the buffer holds at least {@code minFrames} values and the median is at or above {@code vetoThreshold},
 *       the score becomes 0 (off by default: NaN);</li>
 *   <li>otherwise, at or above {@code capThreshold}, the score becomes {@code min(score, capScore)}. With capScore
 *       0.60 between padRejectThreshold (0.30) and passiveThreshold (0.85) the session cannot pass passively and
 *       goes to a challenge, while Part 3's PadMonitor never fails a person because of the cap.</li>
 * </ul>
 * The median of an even count is the mean of the two middle values. Thresholds compare in float, as the moire is a
 * float. No allocation per frame; one instance per analyzer, not thread-safe.
 */
public final class MoireCap {

    private final float capThreshold, capScore, vetoThreshold;
    private final int minFrames;
    private final float[] ring, scratch;
    private int count, next;
    private float median = Float.NaN;
    private boolean active;

    /**
     * @param capThreshold  rolling median at or above which the score is capped; NaN = no cap
     * @param capScore      the cap, in (0, 1)
     * @param window        values in the rolling median, at least minFrames
     * @param minFrames     values needed before the cap or veto can apply, at least 1
     * @param vetoThreshold rolling median at or above which the score becomes 0; NaN = no veto
     */
    public MoireCap(double capThreshold, double capScore, int window, int minFrames, double vetoThreshold) {
        if (!(capScore > 0 && capScore < 1)) throw new IllegalArgumentException("capScore must be in (0, 1)");
        if (minFrames < 1 || window < minFrames) throw new IllegalArgumentException("need window >= minFrames >= 1");
        this.capThreshold = (float) capThreshold;
        this.capScore = (float) capScore;
        this.vetoThreshold = (float) vetoThreshold;
        this.minFrames = minFrames;
        this.ring = new float[window];
        this.scratch = new float[window];
    }

    /** Forgets the buffer (face lost, several faces). */
    public void reset() {
        count = 0;
        next = 0;
        median = Float.NaN;
        active = false;
    }

    /**
     * Adds one single-face frame's moire (NaN is ignored) and returns its score after the cap or veto.
     *
     * @param score the frame's liveness score, NaN when it has none (stays NaN)
     */
    public float apply(float score, float moire) {
        if (!Float.isNaN(moire)) {
            ring[next] = moire;
            next = (next + 1) % ring.length;
            if (count < ring.length) count++;
            median = median();
        }
        active = false;
        if (count < minFrames) return score;
        if (!Float.isNaN(vetoThreshold) && median >= vetoThreshold) {
            active = true;
            return Float.isNaN(score) ? score : 0f;
        }
        if (!Float.isNaN(capThreshold) && median >= capThreshold) {
            active = true;
            return Float.isNaN(score) ? score : Math.min(score, capScore);
        }
        return score;
    }

    /** Rolling median after the last {@link #apply}; NaN while the buffer is empty. */
    public float rollingMedian() {
        return median;
    }

    /** True when the last {@link #apply} was capped or vetoed (the condition held, whatever the score). */
    public boolean active() {
        return active;
    }

    /** Values in the buffer. */
    public int size() {
        return count;
    }

    private float median() {
        System.arraycopy(ring, 0, scratch, 0, count);
        int k = count / 2;
        float upper = select(scratch, count, k);
        if (count % 2 == 1) return upper;
        float lower = scratch[0]; // after select, scratch[0..k-1] are <= upper: the lower middle is their maximum
        for (int i = 1; i < k; i++) if (scratch[i] > lower) lower = scratch[i];
        return (lower + upper) / 2f;
    }

    /** The k-th smallest of a[0..n-1] (0-based), partially reordering a (Hoare quickselect, middle pivot). */
    static float select(float[] a, int n, int k) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    float t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return a[k];
        }
        return a[k];
    }
}
