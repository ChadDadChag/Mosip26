package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.DetectorHarness.Shape;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static io.mosip.registration.liveness.core.detect.DetectorHarness.NEUTRAL;
import static org.assertj.core.api.Assertions.assertThat;

class SmileDetectorTest {

    static final Shape SMILE = new Shape() { @Override public Fa apply(Fa f) { return f.smile(); } };

    private DetectorHarness h() {
        return new DetectorHarness(new SmileDetector(EngineTuning.defaults()), DetectorHarness.neutralBaseline());
    }

    private static Shape width(final float factor, final boolean lift) {
        return new Shape() {
            @Override public Fa apply(Fa f) {
                Fa g = f.mouth(Fa.NEUTRAL_MOUTH * factor);
                return lift ? g.smile().mouth(Fa.NEUTRAL_MOUTH * factor) : g;
            }
        };
    }

    @Test
    void heldSmileIsDetected() {
        DetectorHarness h = h();
        h.frames(4, NEUTRAL);
        h.frames(3, SMILE);
        assertThat(h.detected()).isFalse(); // 3 frames = 134 ms < 200 ms hold
        assertThat(h.frame(SMILE)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void singleFrameSpikeIsNotASmile() {
        DetectorHarness h = h();
        h.frames(4, NEUTRAL);
        for (int i = 0; i < 20; i++) { h.frame(SMILE); h.frame(NEUTRAL); }
        assertThat(h.detected()).isFalse();
    }

    @Test
    void smileAlreadyOnAtThePromptDoesNotCountUntilNeutralFirst() {
        DetectorHarness h = h();
        assertThat(h.frames(20, SMILE)).isEqualTo(DetectorStatus.ARMING);
        h.frames(2, NEUTRAL);
        h.frames(5, SMILE);
        assertThat(h.detected()).isTrue();
    }

    @Test
    void smallerWidenOnlyCountsWithCornerLift() {
        DetectorHarness plain = h();
        plain.frames(4, NEUTRAL);
        plain.frames(10, width(1.12f, false));
        assertThat(plain.detected()).isFalse();
        DetectorHarness lifted = h();
        lifted.frames(4, NEUTRAL);
        lifted.frames(10, width(1.12f, true));
        assertThat(lifted.detected()).isTrue();
    }

    private static Shape smileAtYaw(final float yaw) {
        return new Shape() { @Override public Fa apply(Fa f) { return f.smile().yaw(yaw); } };
    }

    @Test
    void aSmileWith20DegreesOfYawDriftIsNotASmile() {
        // A photo turned sideways: perspective changes the mouth / inter-ocular ratio.
        DetectorHarness h = h();
        h.frames(4, NEUTRAL);
        h.frames(20, smileAtYaw(20));
        assertThat(h.detected()).isFalse();
        h.frames(2, smileAtYaw(5)); // within the limit: the hold starts over from here
        assertThat(h.detected()).isFalse();
        assertThat(h.frames(2, smileAtYaw(5))).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void theTiltGuardCanBeDisabledForSmiles() {
        EngineTuning off = EngineTuning.defaults();
        off.actionMaxPoseDriftDeg = Float.NaN;
        DetectorHarness h = new DetectorHarness(new SmileDetector(off), DetectorHarness.neutralBaseline());
        h.frames(4, NEUTRAL);
        assertThat(h.frames(4, smileAtYaw(20))).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void movingCloserToTheCameraIsNotASmile() {
        DetectorHarness h = h();
        Shape closer = new Shape() { @Override public Fa apply(Fa f) { return f.boxScale(1.6f); } };
        h.frames(4, NEUTRAL);
        h.frames(20, closer);
        assertThat(h.detected()).isFalse();
    }
}
