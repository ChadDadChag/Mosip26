package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.DetectorHarness.Shape;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static io.mosip.registration.liveness.core.detect.DetectorHarness.NEUTRAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AxisMotionDetectorTest {

    private static Shape yaw(final float d) { return new Shape() { @Override public Fa apply(Fa f) { return f.yaw(d); } }; }
    private static Shape pitch(final float d) { return new Shape() { @Override public Fa apply(Fa f) { return f.pitch(d); } }; }

    private static DetectorHarness turn(ChallengeType type) {
        return new DetectorHarness(new HeadTurnDetector(EngineTuning.defaults(), type), DetectorHarness.neutralBaseline());
    }

    private static DetectorHarness look(ChallengeType type) {
        return new DetectorHarness(new GazePitchDetector(EngineTuning.defaults(), type), DetectorHarness.neutralBaseline());
    }

    @Test
    void turnLeftMeansPositiveUserYawHeldThenReturned() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        h.frames(4, NEUTRAL);
        h.frames(2, yaw(12));
        h.frames(4, yaw(28));   // held 4 frames / 201 ms
        assertThat(h.detected()).isFalse();
        h.frame(yaw(15));
        assertThat(h.detected()).as("not back within 10 deg yet").isFalse();
        assertThat(h.frame(yaw(5))).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void turnRightMeansNegativeUserYaw() {
        DetectorHarness h = turn(ChallengeType.TURN_RIGHT);
        h.frames(4, NEUTRAL);
        h.frames(4, yaw(-26));
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void oppositeTurnIsWrongAction() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        h.frames(4, NEUTRAL);
        h.frame(yaw(-10));
        assertThat(h.frame(yaw(-22))).isEqualTo(DetectorStatus.WRONG_ACTION);
    }

    @Test
    void replayWiggleNeverHoldsAndSwingingToTheOtherSideIsWrongAction() {
        // Random head wiggling (e.g. a replayed video): quick swings never satisfy the hold rule...
        DetectorHarness w = turn(ChallengeType.TURN_LEFT);
        w.frames(4, NEUTRAL);
        for (int i = 0; i < 3; i++) { w.frame(yaw(25)); w.frame(yaw(0)); }
        // ...and the first swing the other way fails the challenge.
        assertThat(w.frame(yaw(-25))).isEqualTo(DetectorStatus.WRONG_ACTION);
        assertThat(w.detected()).isFalse();
    }

    @Test
    void notReturningIsNotDetected() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        h.frames(4, NEUTRAL);
        h.frames(60, yaw(30));
        assertThat(h.detected()).isFalse();
    }

    @Test
    void alreadyTurnedAtThePromptMustComeBackFirst() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        assertThat(h.frames(10, yaw(30))).isEqualTo(DetectorStatus.ARMING);
        h.frame(NEUTRAL);
        assertThat(h.detected()).isFalse();
        h.frames(4, yaw(30));
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void relativeToBaselineNotAbsolute() {
        // A user sitting slightly turned (yaw -8 at rest) turns left to +14: 22 deg of real motion.
        java.util.List<io.mosip.registration.liveness.api.FrameAnalysis> b = new java.util.ArrayList<io.mosip.registration.liveness.api.FrameAnalysis>();
        for (int i = 0; i < 5; i++) b.add(Fa.at(i, i).yaw(-8).build());
        DetectorHarness h = new DetectorHarness(new HeadTurnDetector(EngineTuning.defaults(), ChallengeType.TURN_LEFT), Baseline.of(b));
        h.frames(4, yaw(-8));
        h.frames(4, yaw(14));
        assertThat(h.frame(yaw(-6))).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void lookUpAndDownUsePitch() {
        DetectorHarness up = look(ChallengeType.LOOK_UP);
        up.frames(4, NEUTRAL);
        up.frames(4, pitch(16));
        assertThat(up.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);

        DetectorHarness down = look(ChallengeType.LOOK_DOWN);
        down.frames(4, NEUTRAL);
        assertThat(down.frame(pitch(16))).isEqualTo(DetectorStatus.WRONG_ACTION);
    }

    @Test
    void rejectsWrongTypes() {
        assertThatThrownBy(() -> new HeadTurnDetector(EngineTuning.defaults(), ChallengeType.BLINK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GazePitchDetector(EngineTuning.defaults(), ChallengeType.TURN_LEFT))
                .isInstanceOf(IllegalArgumentException.class);
        for (ChallengeType c : ChallengeType.values()) {
            assertThat(Detectors.create(c, EngineTuning.defaults()).type()).isEqualTo(c);
        }
    }
}
