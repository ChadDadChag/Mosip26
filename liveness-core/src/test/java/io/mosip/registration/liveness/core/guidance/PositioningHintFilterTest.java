package io.mosip.registration.liveness.core.guidance;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.guidance.PositioningHintFilter.Output;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PositioningHintFilterTest {

    private final PositioningHintFilter f = new PositioningHintFilter(EngineTuning.defaults());

    private static FrameAnalysis dark(long t) { return Fa.at(t, t).issue(QualityIssue.TOO_DARK).build(); }
    private static FrameAnalysis small(long t) { return Fa.at(t, t).issue(QualityIssue.FACE_TOO_SMALL).build(); }
    private static FrameAnalysis ok(long t) { return Fa.at(t, t).build(); }

    @Test
    void hintAppearsOnlyAfterPersistingAndClearsWhenResolved() {
        assertThat(f.update(dark(0), 0, false)).isSameAs(Output.NONE);
        assertThat(f.update(dark(200), 200, false)).isSameAs(Output.NONE);
        assertThat(f.update(dark(300), 300, false).show).isEqualTo(Guidance.IMPROVE_LIGHTING);
        assertThat(f.update(dark(400), 400, false)).isSameAs(Output.NONE); // not repeated
        assertThat(f.update(ok(500), 500, false)).isSameAs(Output.NONE);
        assertThat(f.update(ok(800), 800, false).clear).isTrue();
        assertThat(f.shown()).isNull();
    }

    @Test
    void flickeringProblemNeverShows() {
        for (int t = 0; t < 3000; t += 100) {
            Output o = f.update(t % 200 == 0 ? dark(t) : ok(t), t, false);
            assertThat(o).isSameAs(Output.NONE);
        }
    }

    @Test
    void shownHintChangesAtMostEvery500msExceptUrgentOnes() {
        f.update(dark(0), 0, false);
        assertThat(f.update(dark(300), 300, false).show).isEqualTo(Guidance.IMPROVE_LIGHTING);
        f.update(small(310), 310, false);
        assertThat(f.update(small(610), 610, false)).isSameAs(Output.NONE); // 310 ms since shown
        assertThat(f.update(small(800), 800, false).show).isEqualTo(Guidance.MOVE_CLOSER);
        f.update(Fa.at(810, 810).faces(0).build(), 810, false);
        assertThat(f.update(Fa.at(1110, 1110).faces(0).build(), 1110, false).show).isEqualTo(Guidance.NO_FACE);
    }

    @Test
    void priorityAndChallengeMode() {
        FrameAnalysis many = Fa.at(0, 0).faces(2).issue(QualityIssue.TOO_DARK).build();
        assertThat(f.classify(many, false)).isEqualTo(Guidance.MULTIPLE_FACES);
        FrameAnalysis turned = Fa.at(0, 0).yaw(30).issue(QualityIssue.FACE_OFF_CENTER).build();
        assertThat(f.classify(turned, false)).isEqualTo(Guidance.CENTER_FACE);
        assertThat(f.classify(Fa.at(0, 0).yaw(30).build(), false)).isEqualTo(Guidance.LOOK_AT_CAMERA);
        // During a challenge the user is asked to turn: pose/centring hints would contradict the prompt.
        assertThat(f.classify(turned, true)).isNull();
        assertThat(f.classify(dark(0), true)).isEqualTo(Guidance.IMPROVE_LIGHTING);
        assertThat(f.classify(Fa.at(0, 0).issue(QualityIssue.BLURRY).build(), false)).isEqualTo(Guidance.HOLD_STEADY);
        assertThat(f.classify(Fa.at(0, 0).issue(QualityIssue.FACE_TOO_LARGE).build(), false)).isEqualTo(Guidance.MOVE_BACK);
    }
}
