package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FaceContinuityGuardTest {

    @Test
    void smoothMotionPassesJumpsFail() {
        FaceContinuityGuard g = new FaceContinuityGuard(EngineTuning.defaults());
        for (int i = 0; i < 20; i++) assertThat(g.check(Fa.at(i * 67, i).shift(i * 6, 0).build(), i * 67)).isTrue();
        assertThat(g.check(Fa.at(20 * 67, 20).shift(300, 0).build(), 20 * 67)).as("centre jump").isFalse();
    }

    @Test
    void abruptSizeChangeFails() {
        FaceContinuityGuard g = new FaceContinuityGuard(EngineTuning.defaults());
        g.check(Fa.at(0, 0).build(), 0);
        assertThat(g.check(Fa.at(67, 1).boxScale(1.4f).build(), 67)).as("area x1.96").isFalse();
    }

    @Test
    void acrossAGapOnlyLooseOverlapIsRequired() {
        FaceContinuityGuard g = new FaceContinuityGuard(EngineTuning.defaults());
        g.check(Fa.at(0, 0).build(), 0);
        g.check(Fa.at(67, 1).faces(0).build(), 67); // ignored
        assertThat(g.check(Fa.at(600, 2).shift(60, 0).build(), 600)).isTrue();
        assertThat(g.check(Fa.at(1300, 3).shift(400, 0).build(), 1300)).as("different place after gap").isFalse();
        g.reset();
        assertThat(g.check(Fa.at(2000, 4).shift(-200, 0).build(), 2000)).isTrue();
    }
}
