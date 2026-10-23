package io.mosip.registration.liveness.core.passive;

import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PadMonitorTest {

    @Test
    void singleNoisyFrameDoesNotFailButSustainedLowScoresDo() {
        PadMonitor m = new PadMonitor(0.30f, 3);
        assertThat(m.attack(Fa.at(0, 0).score(0.7f).build())).isFalse();
        assertThat(m.attack(Fa.at(1, 1).score(0.05f).build())).isFalse(); // window not full yet
        assertThat(m.attack(Fa.at(2, 2).score(0.7f).build())).isFalse();  // 0.7, 0.05, 0.7 -> 0.7
        assertThat(m.attack(Fa.at(3, 3).score(0.7f).build())).isFalse();  // one noisy frame is outvoted
        assertThat(m.attack(Fa.at(4, 4).score(0.1f).build())).isFalse();  // 0.7, 0.7, 0.1 -> 0.7
        assertThat(m.attack(Fa.at(5, 5).score(0.1f).build())).isTrue();   // 0.7, 0.1, 0.1 -> 0.1
    }

    @Test
    void framesWithoutScoreOrFaceAreIgnored() {
        PadMonitor m = new PadMonitor(0.30f, 3);
        for (int i = 0; i < 10; i++) {
            assertThat(m.attack(Fa.at(i, i).score(Float.NaN).build())).isFalse();
            assertThat(m.attack(Fa.at(i, i).faces(0).build())).isFalse();
        }
        m.reset();
        assertThat(m.median()).isNaN();
    }
}
