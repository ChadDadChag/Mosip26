package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BestFrameSelectorTest {

    private final BestFrameSelector s = new BestFrameSelector(EngineTuning.defaults());

    @Test
    void rejectsTurnedClosedEyesQualityIssuesAndMultipleFaces() {
        assertThat(s.offer(Fa.at(0, 0).yaw(15).build())).isFalse();
        assertThat(s.offer(Fa.at(0, 1).pitch(-12).build())).isFalse();
        assertThat(s.offer(Fa.at(0, 2).eyesClosed().build())).isFalse();
        assertThat(s.offer(Fa.at(0, 3).issue(QualityIssue.BLURRY).build())).isFalse();
        assertThat(s.offer(Fa.at(0, 4).faces(2).build())).isFalse();
        assertThat(s.offer(Fa.at(0, 5).faces(0).build())).isFalse();
        assertThat(s.hasBest()).isFalse();
        assertThat(s.bestFrame()).isNull();
    }

    @Test
    void prefersMoreFrontalAndLargerFaces() {
        FrameAnalysis slightlyTurned = Fa.at(0, 0).yaw(8).build();
        FrameAnalysis frontal = Fa.at(1, 1).build();
        FrameAnalysis frontalBigger = Fa.at(2, 2).boxScale(1.3f).build();
        assertThat(s.offer(slightlyTurned)).isTrue();
        assertThat(s.offer(frontal)).isTrue();
        assertThat(s.offer(slightlyTurned)).isFalse();
        assertThat(s.offer(frontalBigger)).isTrue();
        assertThat(s.bestFrame()).isSameAs(frontalBigger.frame);
        s.reset();
        assertThat(s.hasBest()).isFalse();
    }
}
