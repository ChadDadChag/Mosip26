package io.mosip.registration.liveness.analyzer.pad;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The session print guards; the sequence and expectations are reference_pipeline.py's GUARDS_SELF_TEST. */
class PadGuardsTest {

    static final float NONE = Float.NaN;
    /** (faceCount, modelScore, score after the moire cap), as in reference_pipeline.GUARDS_SELF_TEST. */
    static final float[][] SEQUENCE = sequence();
    static final float[] DEFAULT = expected(0.60f, 0.60f);
    static final float[] LATCH_OFF = expected(0.60f, 0.95f);
    static final float[] BOTH_OFF = expected(0.95f, 0.95f);
    static final int LATCHED_FROM = 23;

    private static float[][] sequence() {
        float[][] s = new float[33][];
        for (int i = 0; i < 10; i++) s[i] = new float[] {1, 0.95f, 0.95f};
        s[10] = new float[] {1, 0.50f, 0.50f};
        s[11] = new float[] {1, 0.60f, 0.60f};
        for (int i = 12; i < 22; i++) s[i] = new float[] {1, 0.95f, 0.95f};
        s[22] = new float[] {1, 0.20f, 0.20f};
        s[23] = new float[] {1, 0.10f, 0.10f};
        s[24] = new float[] {1, 0.25f, 0.25f};
        s[25] = new float[] {1, NONE, NONE};
        s[26] = new float[] {0, NONE, NONE};
        for (int i = 27; i < 33; i++) s[i] = new float[] {1, 0.95f, 0.95f};
        return s;
    }

    /** volatile = the score of frames 12-19 (both dips in the window); latched = the score of frames 27-32. */
    private static float[] expected(float volatileScore, float latchedScore) {
        float[] e = new float[33];
        Arrays.fill(e, 0.95f);
        e[10] = 0.50f;
        e[11] = 0.60f;
        for (int i = 12; i < 20; i++) e[i] = volatileScore;
        e[22] = 0.20f;
        e[23] = 0.10f;
        e[24] = 0.25f;
        e[25] = NONE;
        e[26] = NONE;
        for (int i = 27; i < 33; i++) e[i] = latchedScore;
        return e;
    }

    /** Replays the sequence as the analyzer does: any face count but 1 resets the volatility window only. */
    static float[] replay(PadLatch latch, VolatilityCap vol, boolean[] latched, boolean[] volatile_) {
        float[] out = new float[SEQUENCE.length];
        for (int i = 0; i < SEQUENCE.length; i++) {
            float[] f = SEQUENCE[i];
            if (f[0] != 1) {
                vol.reset();
                out[i] = f[2];
            } else {
                out[i] = vol.apply(latch.apply(f[2]), f[1]);
            }
            if (latched != null) latched[i] = latch.latched();
            if (volatile_ != null) volatile_[i] = f[0] == 1 && vol.active();
        }
        return out;
    }

    private static void assertScores(float[] got, float[] want) {
        for (int i = 0; i < want.length; i++) {
            if (Float.isNaN(want[i])) assertThat(got[i]).as("frame %d", i).isNaN();
            else assertThat(got[i]).as("frame %d", i).isCloseTo(want[i], within(1e-6f));
        }
    }

    @Test
    void theLatchHoldsForTheRestOfTheSessionAndTheVolatilityCapRollsOff() {
        PadLatch latch = new PadLatch(0.30, 0.60);
        boolean[] latched = new boolean[SEQUENCE.length], vol = new boolean[SEQUENCE.length];
        assertScores(replay(latch, new VolatilityCap(0.85, 0.60), latched, vol), DEFAULT);
        for (int i = 0; i < SEQUENCE.length; i++) assertThat(latched[i]).as("latched at frame %d", i).isEqualTo(i >= LATCHED_FROM);
        // Volatility: on from the second dip (frame 11) until the first dip leaves the window (frame 20), off after a
        // steady face; frames 23-24 are volatile again (two low values), the unscored frame and no-face frame are not.
        for (int i = 0; i < SEQUENCE.length; i++) {
            boolean want = (i >= 11 && i <= 19) || i == 23 || i == 24;
            assertThat(vol[i]).as("volatile at frame %d", i).isEqualTo(want);
        }
        assertThat(latch.latchedAt()).isEqualTo(24); // frame index 23 is the 24th scored frame (1-based count)
        assertThat(latch.latchMedian()).isEqualTo(0.20f);
    }

    @Test
    void eitherGuardCanBeSwitchedOff() {
        assertScores(replay(new PadLatch(Double.NaN, 0.60), new VolatilityCap(0.85, 0.60), null, null), LATCH_OFF);
        PadLatch off = new PadLatch(Double.NaN, 0.60);
        assertScores(replay(off, new VolatilityCap(Double.NaN, 0.60), null, null), BOTH_OFF);
        assertThat(off.latched()).isFalse();
        assertThat(off.latchedAt()).isZero();
        assertThat(off.latchMedian()).isNaN();
    }

    @Test
    void aNewLatchStartsUnlatched() {
        PadLatch first = new PadLatch(0.30, 0.60);
        replay(first, new VolatilityCap(0.85, 0.60), null, null);
        assertThat(first.latched()).isTrue();
        PadLatch next = new PadLatch(0.30, 0.60); // a new session has a new analyzer
        assertThat(next.latched()).isFalse();
        assertThat(next.apply(0.95f)).isEqualTo(0.95f);
    }

    @Test
    void theLatchNeedsThreeScoresAndSkipsUnscoredFrames() {
        PadLatch l = new PadLatch(0.30, 0.60);
        assertThat(l.apply(0.10f)).isEqualTo(0.10f);
        assertThat(l.apply(NONE)).isNaN(); // not observed
        assertThat(l.latched()).isFalse();
        assertThat(l.apply(0.20f)).isEqualTo(0.20f);
        assertThat(l.latched()).isFalse(); // two scores only
        assertThat(l.apply(0.90f)).isEqualTo(0.60f); // median of 0.10, 0.20, 0.90 is 0.20: latched, capped
        assertThat(l.latched()).isTrue();
        assertThat(l.latchedAt()).isEqualTo(3);
        assertThat(l.apply(0.29f)).isEqualTo(0.29f); // the cap only lowers
        // Exactly at the threshold does not latch (below, as PadMonitor).
        PadLatch edge = new PadLatch(0.30, 0.60);
        for (int i = 0; i < 5; i++) edge.apply(0.30f);
        assertThat(edge.latched()).isFalse();
    }

    @Test
    void volatilityNeedsFiveValuesAndUsesTheSecondLowest() {
        VolatilityCap v = new VolatilityCap(0.85, 0.60);
        float[] scores = {0.10f, 0.20f, 0.95f, 0.95f};
        for (float s : scores) {
            assertThat(v.apply(s, s)).isEqualTo(s); // fewer than 5 values
            assertThat(v.active()).isFalse();
        }
        assertThat(v.apply(0.95f, 0.95f)).isEqualTo(0.60f); // 5 values, second-lowest 0.20
        assertThat(v.active()).isTrue();
        assertThat(v.apply(NONE, NONE)).isNaN(); // unscored: not added, not active
        assertThat(v.active()).isFalse();
        // The capped score never enters the window: the model score does.
        VolatilityCap one = new VolatilityCap(0.85, 0.60);
        for (int i = 0; i < 9; i++) one.apply(0.95f, 0.95f);
        assertThat(one.apply(0.40f, 0.40f)).isEqualTo(0.40f); // one low value: not volatile
        assertThat(one.active()).isFalse();
        assertThat(one.apply(0.60f, 0.97f)).isEqualTo(0.60f); // a moire-capped frame with a high model score
        assertThat(one.active()).isFalse();
        v.reset();
        assertThat(v.apply(0.95f, 0.95f)).isEqualTo(0.95f);
    }

    @Test
    void capScoresMustBeBetweenZeroAndOne() {
        assertThatThrownBy(() -> new PadLatch(0.30, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VolatilityCap(0.85, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PadLatch.median3(0.9f, 0.1f, 0.5f)).isEqualTo(0.5f);
        assertThat(PadLatch.median3(0.1f, 0.1f, 0.9f)).isEqualTo(0.1f);
    }
}
