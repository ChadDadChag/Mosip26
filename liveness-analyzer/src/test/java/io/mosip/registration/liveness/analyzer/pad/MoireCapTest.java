package io.mosip.registration.liveness.analyzer.pad;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The session moire cap; the sequence and expectations are reference_pipeline.py's SESSION_SELF_TEST. */
class MoireCapTest {

    static final float NONE = Float.NaN;
    /** (faceCount, livenessScore, moire), as in reference_pipeline.SESSION_SELF_TEST. */
    static final float[][] SEQUENCE = {
            {1, 0.95f, 1.20f}, {1, 0.95f, 1.50f}, {1, 0.95f, 1.50f}, {1, 0.40f, 1.50f}, {1, NONE, 1.60f},
            {0, NONE, NONE}, {1, 0.95f, 1.50f}, {1, 0.95f, 1.50f},
            {2, NONE, NONE}, {1, 0.95f, 1.39f}, {1, 0.95f, 1.39f}, {1, 0.95f, 1.39f},
            {1, 0.95f, 1.40f}, {1, 0.95f, 1.41f}, {1, 0.95f, 1.41f}, {1, 0.95f, 1.41f},
    };
    static final float[] DEFAULT = {0.95f, 0.95f, 0.60f, 0.40f, NONE, NONE, 0.95f, 0.95f, NONE, 0.95f, 0.95f, 0.95f,
            0.95f, 0.95f, 0.95f, 0.60f};
    static final float[] VETO_145 = {0.95f, 0.95f, 0f, 0f, NONE, NONE, 0.95f, 0.95f, NONE, 0.95f, 0.95f, 0.95f,
            0.95f, 0.95f, 0.95f, 0.60f};
    static final float[] OFF = {0.95f, 0.95f, 0.95f, 0.40f, NONE, NONE, 0.95f, 0.95f, NONE, 0.95f, 0.95f, 0.95f,
            0.95f, 0.95f, 0.95f, 0.95f};
    static final boolean[] DEFAULT_ACTIVE = {false, false, true, true, true, false, false, false, false, false, false,
            false, false, false, false, true};

    /** Replays the sequence as the analyzer does: any face count but 1 resets the cap. */
    static float[] replay(MoireCap cap, boolean[] active, float[] medians) {
        float[] out = new float[SEQUENCE.length];
        for (int i = 0; i < SEQUENCE.length; i++) {
            float[] f = SEQUENCE[i];
            if (f[0] != 1) {
                cap.reset();
                out[i] = f[1];
            } else {
                out[i] = cap.apply(f[1], f[2]);
            }
            if (active != null) active[i] = f[0] == 1 && cap.active();
            if (medians != null) medians[i] = cap.rollingMedian();
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
    void capAppliesFromTheThirdFrameAtOrAbove140AndResetsOnFaceChanges() {
        boolean[] active = new boolean[SEQUENCE.length];
        float[] medians = new float[SEQUENCE.length];
        assertScores(replay(new MoireCap(1.40, 0.60, 10, 3, Double.NaN), active, medians), DEFAULT);
        assertThat(active).containsExactly(DEFAULT_ACTIVE);
        assertThat(medians[1]).isCloseTo(1.35f, within(1e-6f));  // even count: mean of the two middle values
        assertThat(medians[5]).isNaN();                             // face lost: reset
        assertThat(medians[12]).isCloseTo(1.39f, within(1e-6f));  // 1.39 stays below 1.40: no cap
        assertThat(medians[14]).isCloseTo(1.395f, within(1e-6f));
        assertThat(medians[15]).isEqualTo(1.40f);                   // exactly at the threshold: capped
    }

    @Test
    void vetoWhenEnabledAndNothingWhenDisabled() {
        assertScores(replay(new MoireCap(1.40, 0.60, 10, 3, 1.45), null, null), VETO_145);
        assertScores(replay(new MoireCap(Double.NaN, 0.60, 10, 3, Double.NaN), null, null), OFF);
        MoireCap off = new MoireCap(Double.NaN, 0.60, 10, 3, Double.NaN);
        for (int i = 0; i < 12; i++) assertThat(off.apply(0.99f, 3f)).isEqualTo(0.99f);
        assertThat(off.active()).isFalse();
        assertThat(off.rollingMedian()).isEqualTo(3f);
    }

    @Test
    void theWindowSlidesAndNaNMoireIsIgnored() {
        MoireCap cap = new MoireCap(1.40, 0.60, 4, 3, Double.NaN);
        assertThat(cap.rollingMedian()).isNaN();
        for (int i = 0; i < 4; i++) cap.apply(0.9f, 1.6f);
        assertThat(cap.active()).isTrue();
        for (int i = 0; i < 3; i++) cap.apply(0.9f, 1.0f); // window of 4: 1.6, 1.0, 1.0, 1.0
        assertThat(cap.size()).isEqualTo(4);
        assertThat(cap.rollingMedian()).isEqualTo(1.0f);
        assertThat(cap.apply(0.9f, Float.NaN)).isEqualTo(0.9f); // no moire: nothing added
        assertThat(cap.size()).isEqualTo(4);
        assertThat(cap.apply(Float.NaN, 1.0f)).isNaN();
        cap.reset();
        assertThat(cap.size()).isZero();
        assertThat(cap.rollingMedian()).isNaN();
        assertThat(cap.active()).isFalse();
    }

    @Test
    void selectMedianMatchesSorting() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + rnd.nextInt(10);
            MoireCap cap = new MoireCap(1.40, 0.60, n, 1, Double.NaN);
            float[] v = new float[n];
            for (int i = 0; i < n; i++) {
                v[i] = rnd.nextInt(4) == 0 ? 1.5f : 1 + rnd.nextInt(100) / 100f; // with ties
                cap.apply(0.9f, v[i]);
            }
            Arrays.sort(v);
            float want = n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2f;
            assertThat(cap.rollingMedian()).as("trial %d %s", trial, Arrays.toString(v)).isEqualTo(want);
        }
    }

    @Test
    void constructorChecks() {
        assertThatThrownBy(() -> new MoireCap(1.4, 0, 10, 3, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoireCap(1.4, 1, 10, 3, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoireCap(1.4, 0.6, 2, 3, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoireCap(1.4, 0.6, 10, 0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
