package io.mosip.registration.liveness.analyzer.pad;

/**
 * Session PAD latch ({@code reference_pipeline.session_guards}, guard A). A printed photo can score live on some
 * frames and as an attack on others, and every retry restarts Part 3's passive window: in the F3 retest a cut-out
 * failed two attempts with PAD_FAILURE and then passed passively on the fourth. The analyzer is one per session,
 * retries included, so it can remember the attack evidence:
 * <ul>
 *   <li>it keeps the last {@value #FRAMES} scores of scored single-face frames, the scores Part 3's PadMonitor sees
 *       (after the moire cap). Frames without a score are skipped and nothing clears the history, as in
 *       PadMonitor;</li>
 *   <li>once their median is below {@code threshold} (padRejectThreshold, 0.30) the session is latched until the
 *       analyzer is closed: every later score, the latching frame's included, becomes {@code min(score, capScore)}.
 *       With capScore 0.60 a retry can no longer pass passively and must pass a challenge; nothing is rejected
 *       because of the latch.</li>
 * </ul>
 * Thresholds compare in float. No allocation per frame; one instance per analyzer, not thread-safe.
 */
public final class PadLatch {

    /** The rolling median's length (Part 3's padMonitorFrames). */
    public static final int FRAMES = 3;

    private final float threshold, capScore;
    private final float[] last = new float[FRAMES];
    private int count, next, scored, latchedAt;
    private boolean latched;
    private float latchMedian = Float.NaN;

    /**
     * @param threshold the 3-frame median below which the session latches; NaN = off
     * @param capScore  the cap while latched, in (0, 1)
     */
    public PadLatch(double threshold, double capScore) {
        if (!(capScore > 0 && capScore < 1)) throw new IllegalArgumentException("capScore must be in (0, 1)");
        this.threshold = (float) threshold;
        this.capScore = (float) capScore;
    }

    /**
     * Observes one single-face frame's score after the moire cap and returns it, capped while latched.
     *
     * @param score NaN when the frame has no score (stays NaN, not observed)
     */
    public float apply(float score) {
        if (Float.isNaN(score)) return score;
        scored++;
        last[next] = score;
        next = (next + 1) % FRAMES;
        if (count < FRAMES) count++;
        if (!latched && !Float.isNaN(threshold) && count == FRAMES) {
            float m = median3(last[0], last[1], last[2]);
            if (m < threshold) {
                latched = true;
                latchedAt = scored;
                latchMedian = m;
            }
        }
        return latched ? Math.min(score, capScore) : score;
    }

    /** True from the frame that latched until the analyzer is closed. */
    public boolean latched() {
        return latched;
    }

    /** The scored frame (1-based, counted by this latch) that latched; 0 while not latched. */
    public int latchedAt() {
        return latchedAt;
    }

    /** The 3-frame median that latched; NaN while not latched. */
    public float latchMedian() {
        return latchMedian;
    }

    static float median3(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }
}
