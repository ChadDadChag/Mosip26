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

    private static EngineTuning tuning(boolean requireReturn) {
        EngineTuning t = EngineTuning.defaults();
        t.axisRequireReturn = requireReturn;
        return t;
    }

    private static DetectorHarness turn(ChallengeType type) {
        return turn(type, false);
    }

    private static DetectorHarness turn(ChallengeType type, boolean requireReturn) {
        return new DetectorHarness(new HeadTurnDetector(tuning(requireReturn), type), DetectorHarness.neutralBaseline());
    }

    private static DetectorHarness look(ChallengeType type, boolean requireReturn) {
        return new DetectorHarness(new GazePitchDetector(tuning(requireReturn), type), DetectorHarness.neutralBaseline());
    }

    // ---- default rule: detected when the hold completes, no return needed (Part 2 review)

    @Test
    void turnIsDetectedWhenTheHoldCompletesWithoutComingBack() {
        assertThat(EngineTuning.defaults().axisRequireReturn).isFalse();
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        h.frames(4, NEUTRAL);
        h.frames(2, yaw(12));
        h.frames(3, yaw(28));   // 3 frames but only 134 ms: not yet
        assertThat(h.detected()).isFalse();
        assertThat(h.frame(yaw(28))).as("4 frames / 201 ms held").isEqualTo(DetectorStatus.DETECTED);
        assertThat(h.d.progress()).isEqualTo(1f);
    }

    @Test
    void turnRightAndLookUpDownWithoutReturn() {
        DetectorHarness right = turn(ChallengeType.TURN_RIGHT);
        right.frames(4, NEUTRAL);
        assertThat(right.frames(4, yaw(-26))).isEqualTo(DetectorStatus.DETECTED);

        DetectorHarness up = look(ChallengeType.LOOK_UP, false);
        up.frames(4, NEUTRAL);
        assertThat(up.frames(4, pitch(16))).isEqualTo(DetectorStatus.DETECTED);

        DetectorHarness down = look(ChallengeType.LOOK_DOWN, false);
        down.frames(4, NEUTRAL);
        assertThat(down.frame(pitch(16))).isEqualTo(DetectorStatus.WRONG_ACTION);
    }

    @Test
    void aTurnHeldTooBrieflyIsNotDetected() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        h.frames(4, NEUTRAL);
        for (int i = 0; i < 5; i++) { h.frames(2, yaw(30)); h.frame(yaw(5)); } // never 3 frames / 200 ms in a row
        assertThat(h.detected()).isFalse();
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
    void alreadyTurnedAtThePromptMustComeBackToArmFirst() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT);
        assertThat(h.frames(10, yaw(30))).isEqualTo(DetectorStatus.ARMING);
        h.frame(NEUTRAL);
        assertThat(h.detected()).isFalse();
        assertThat(h.frames(4, yaw(30))).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void relativeToBaselineNotAbsolute() {
        // A user sitting slightly turned (yaw -8 at rest) turns left to +14: 22 deg of real motion.
        java.util.List<io.mosip.registration.liveness.api.FrameAnalysis> b = new java.util.ArrayList<io.mosip.registration.liveness.api.FrameAnalysis>();
        for (int i = 0; i < 5; i++) b.add(Fa.at(i, i).yaw(-8).build());
        DetectorHarness h = new DetectorHarness(new HeadTurnDetector(EngineTuning.defaults(), ChallengeType.TURN_LEFT), Baseline.of(b));
        h.frames(4, yaw(-8));
        assertThat(h.frames(4, yaw(14))).isEqualTo(DetectorStatus.DETECTED);
    }

    // ---- axisRequireReturn = true: the original move, hold, come back rule

    @Test
    void withRequireReturnTurnLeftIsHeldThenReturned() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT, true);
        h.frames(4, NEUTRAL);
        h.frames(2, yaw(12));
        h.frames(4, yaw(28));   // held 4 frames / 201 ms
        assertThat(h.detected()).isFalse();
        h.frame(yaw(15));
        assertThat(h.detected()).as("not back within 10 deg yet").isFalse();
        assertThat(h.frame(yaw(5))).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void withRequireReturnTurnRightAndLooksComeBack() {
        DetectorHarness h = turn(ChallengeType.TURN_RIGHT, true);
        h.frames(4, NEUTRAL);
        h.frames(4, yaw(-26));
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);

        DetectorHarness up = look(ChallengeType.LOOK_UP, true);
        up.frames(4, NEUTRAL);
        up.frames(4, pitch(16));
        assertThat(up.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void withRequireReturnNotReturningIsNotDetected() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT, true);
        h.frames(4, NEUTRAL);
        h.frames(60, yaw(30));
        assertThat(h.detected()).isFalse();
    }

    @Test
    void withRequireReturnAlreadyTurnedAtThePromptMustComeBackFirst() {
        DetectorHarness h = turn(ChallengeType.TURN_LEFT, true);
        assertThat(h.frames(10, yaw(30))).isEqualTo(DetectorStatus.ARMING);
        h.frame(NEUTRAL);
        assertThat(h.detected()).isFalse();
        h.frames(4, yaw(30));
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
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
