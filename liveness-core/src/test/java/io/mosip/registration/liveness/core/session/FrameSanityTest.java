package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrameSanityTest {

    @Test
    void rejectsRepeatedReorderedAndTooCloseFrames() {
        FrameSanity s = new FrameSanity(EngineTuning.defaults());
        assertThat(s.accept(Fa.at(100, 1).build().frame)).isTrue();
        assertThat(s.accept(Fa.at(100, 2).build().frame)).as("same timestamp").isFalse();
        assertThat(s.accept(Fa.at(200, 1).build().frame)).as("same sequence").isFalse();
        assertThat(s.accept(Fa.at(102, 3).build().frame)).as("2 ms apart").isFalse();
        assertThat(s.limitReached()).isTrue();
        assertThat(s.accept(Fa.at(140, 5).build().frame)).isTrue(); // gaps in sequence = dropped frames, fine
        s.resetAttempt();
        assertThat(s.violations()).isZero();
        assertThat(s.accept(Fa.at(50, 1).build().frame)).isFalse();
        s.resetStream();
        assertThat(s.accept(Fa.at(50, 1).build().frame)).as("source restarted").isTrue();
    }
}
