package io.mosip.registration.liveness.analyzer.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RelativeBlur replays reference_pipeline.BLUR_SELF_TEST (ratio 0.45). */
class RelativeBlurTest {

    // reference_pipeline.BLUR_SELF_TEST; NaN = a frame without exactly one face (skipped).
    static final double[] LAP_VARS = {100, 110, 90, 105, 95, 40, 50, 10, Double.NaN, 30, 30, 30, 30, 30, 30, 44, 44.5, 60};
    // reference_pipeline.BLUR_EXPECTED (null for the skipped frame).
    static final Boolean[] EXPECTED = {false, false, false, false, false, true, false, true, null,
            true, true, true, true, true, true, false, false, false};

    @Test
    void matchesTheReferenceSequence() {
        RelativeBlur blur = new RelativeBlur(0.45);
        for (int i = 0; i < LAP_VARS.length; i++) {
            if (Double.isNaN(LAP_VARS[i])) continue; // the analyzer only calls it for single-face frames
            boolean absolute = LAP_VARS[i] < 15;
            assertThat(blur.check(LAP_VARS[i], absolute)).as("frame %d", i).isEqualTo(EXPECTED[i]);
        }
        assertThat(blur.size()).isEqualTo(9); // the first 5, then 50, 44, 44.5 and 60
        assertThat(blur.median()).isEqualTo(90.0);
    }

    @Test
    void aBurstDoesNotDragTheMedianDown() {
        RelativeBlur blur = new RelativeBlur(0.45);
        for (int i = 0; i < 5; i++) blur.check(100, false);
        for (int i = 0; i < 50; i++) assertThat(blur.check(40, false)).isTrue();
        assertThat(blur.median()).isEqualTo(100.0);
        assertThat(blur.check(46, false)).isFalse();
    }

    @Test
    void theWindowKeepsTheLast15AndOffDisablesTheRule() {
        RelativeBlur blur = new RelativeBlur(0.45);
        assertThat(blur.median()).isNaN();
        for (int i = 0; i < 4; i++) blur.check(100, false);
        assertThat(blur.check(10.5, false)).as("only 4 values: the relative rule waits").isFalse();
        for (int i = 0; i < 20; i++) blur.check(200, false);
        assertThat(blur.size()).isEqualTo(RelativeBlur.WINDOW);
        assertThat(blur.median()).isEqualTo(200.0);

        RelativeBlur off = new RelativeBlur(Double.NaN);
        for (int i = 0; i < 10; i++) off.check(100, false);
        assertThat(off.check(20, false)).isFalse();
        assertThat(off.check(20, true)).as("the fixed threshold still counts").isTrue();
    }
}
