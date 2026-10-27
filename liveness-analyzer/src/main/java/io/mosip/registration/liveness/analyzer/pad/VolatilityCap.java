package io.mosip.registration.liveness.analyzer.pad;

/**
 * Session volatility cap ({@code reference_pipeline.session_guards}, guard C). A real face scores steadily high; the
 * printed cut-out of the F3 retest swung between 0.06 and 0.99 and passed on a window whose median was high although
 * two of its ten frames were not. The analyzer keeps the model score ((v2 live + v1se live) / 2, before any cap) of
 * the last {@value #WINDOW} scored single-face frames; any other face count clears it, as the moire buffer is
 * cleared. On a scored frame, once at least {@value #MIN_FRAMES} values are known and the second-lowest of them
 * (this frame included) is below {@code minScore}, the score becomes {@code min(score, capScore)}: the session goes
 * to a challenge instead of passing passively.
 *
 * <p>Only model scores enter the window, never capped ones, so the cap cannot feed itself: once the low frames have
 * left the window it lifts. Thresholds compare in float. No allocation per frame; one instance per analyzer, not
 * thread-safe.
 */
public final class VolatilityCap {

    /** Scored single-face frames in the window. */
    public static final int WINDOW = 10;
    /** Values needed before the rule applies. */
    public static final int MIN_FRAMES = 5;

    private final float minScore, capScore;
    private final float[] ring = new float[WINDOW];
    private int count, next;
    private boolean active;

    /**
     * @param minScore the second-lowest model score below which the score is capped; NaN = off
     * @param capScore the cap, in (0, 1)
     */
    public VolatilityCap(double minScore, double capScore) {
        if (!(capScore > 0 && capScore < 1)) throw new IllegalArgumentException("capScore must be in (0, 1)");
        this.minScore = (float) minScore;
        this.capScore = (float) capScore;
    }

    /** Forgets the window (face lost, several faces). */
    public void reset() {
        count = 0;
        next = 0;
        active = false;
    }

    /**
     * Adds one single-face frame's model score and returns its score after the cap.
     *
     * @param score      the score so far (after the moire cap); NaN when the frame has none (stays NaN)
     * @param modelScore the uncapped model score; NaN when the frame has none (not added)
     */
    public float apply(float score, float modelScore) {
        active = false;
        if (Float.isNaN(modelScore)) return score;
        ring[next] = modelScore;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        if (Float.isNaN(minScore) || count < MIN_FRAMES || !(secondLowest() < minScore)) return score;
        active = true;
        return Float.isNaN(score) ? score : Math.min(score, capScore);
    }

    /** True when the last {@link #apply} capped (the condition held). */
    public boolean active() {
        return active;
    }

    private float secondLowest() {
        float lo = Float.POSITIVE_INFINITY, lo2 = Float.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            float v = ring[i];
            if (v < lo) {
                lo2 = lo;
                lo = v;
            } else if (v < lo2) {
                lo2 = v;
            }
        }
        return lo2;
    }
}
