package io.mosip.registration.liveness.analyzer.quality;

import java.util.Arrays;

/**
 * Relative motion blur for one session (F6; {@code reference_pipeline.session_blur}). A fixed lapVar threshold
 * misses a moving face whose sharpness drops well below its own level but stays above 15. Per single-face frame:
 * <pre>
 *   BLURRY  when  lapVar &lt; blurLapVar (QualityAssessor, unchanged)
 *           or    (window holds &gt;= 5 values and lapVar &lt; ratio x median(window))
 * </pre>
 * The window holds the lapVar of the last 15 single-face frames that were NOT flagged. The current frame is compared
 * before it is added, and a flagged frame is never added, so a burst of blur cannot drag the median down. The window
 * is per session (per analyzer) and is never cleared within it; frames with no face or several faces do not touch
 * it. A NaN ratio switches the relative rule off. Not thread-safe.
 */
public final class RelativeBlur {

    public static final int WINDOW = 15;
    public static final int MIN_VALUES = 5;

    private final double ratio;
    private final double[] values = new double[WINDOW];
    private final double[] scratch = new double[WINDOW];
    private int count;
    private int next;

    /** @param ratio fraction of the session median below which a frame is blurry; NaN = off */
    public RelativeBlur(double ratio) {
        this.ratio = ratio;
    }

    /**
     * Decides one single-face frame and updates the window.
     *
     * @param absoluteBlurry whether the fixed threshold already flagged the frame
     * @return true when the frame is blurry by either rule
     */
    public boolean check(double lapVar, boolean absoluteBlurry) {
        boolean relative = !Double.isNaN(ratio) && count >= MIN_VALUES && lapVar < ratio * median();
        boolean blurry = absoluteBlurry || relative;
        if (!blurry && !Double.isNaN(lapVar)) {
            values[next] = lapVar;
            next = (next + 1) % WINDOW;
            if (count < WINDOW) count++;
        }
        return blurry;
    }

    /** The median of the window (mean of the two middle values for an even count); NaN when empty. */
    public double median() {
        if (count == 0) return Double.NaN;
        System.arraycopy(values, 0, scratch, 0, count);
        Arrays.sort(scratch, 0, count);
        int mid = count / 2;
        return count % 2 == 1 ? scratch[mid] : (scratch[mid - 1] + scratch[mid]) / 2;
    }

    /** Values in the window. */
    public int size() {
        return count;
    }
}
