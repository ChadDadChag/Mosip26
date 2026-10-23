package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.DetectorHarness.Shape;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static io.mosip.registration.liveness.core.detect.DetectorHarness.NEUTRAL;
import static org.assertj.core.api.Assertions.assertThat;

class BlinkDetectorTest {

    static final Shape CLOSED = new Shape() { @Override public Fa apply(Fa f) { return f.eyesClosed(); } };
    static final Shape HALF = new Shape() { @Override public Fa apply(Fa f) { return f.ear(Fa.OPEN_EAR * 0.7f); } };

    private DetectorHarness h() {
        return new DetectorHarness(new BlinkDetector(EngineTuning.defaults()), DetectorHarness.neutralBaseline());
    }

    @Test
    void normalBlinkIsDetectedEvenWhenOnlyOneFrameIsClosed() {
        DetectorHarness h = h();
        h.frames(6, NEUTRAL);
        h.frame(CLOSED);
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
        assertThat(h.d.progress()).isEqualTo(1f);
    }

    @Test
    void blinkWithinTheReactionTimeDoesNotCount() {
        DetectorHarness h = h();
        h.frame(CLOSED);               // t = 67 ms: before the minimum reaction time
        h.frame(NEUTRAL);
        assertThat(h.detected()).isFalse();
        h.frames(2, NEUTRAL);
        h.frame(CLOSED);
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void eyesAlreadyClosedAtThePromptMustOpenFirst() {
        DetectorHarness h = h();
        assertThat(h.frames(10, CLOSED)).isEqualTo(DetectorStatus.ARMING);
        h.frame(NEUTRAL);
        assertThat(h.detected()).isFalse();
    }

    @Test
    void longClosureIsNotABlinkButALaterRealBlinkIs() {
        DetectorHarness h = h();
        h.frames(4, NEUTRAL);
        h.frames(30, CLOSED); // 2 s shut
        h.frame(NEUTRAL);
        assertThat(h.detected()).isFalse();
        h.frames(3, NEUTRAL);
        h.frames(2, CLOSED);
        assertThat(h.frame(NEUTRAL)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void closedEyePhotoNeverBlinks() {
        DetectorHarness h = h();
        h.frames(100, CLOSED);
        assertThat(h.detected()).isFalse();
    }

    @Test
    void partialSquintIsNotABlink() {
        DetectorHarness h = h();
        h.frames(4, NEUTRAL);
        for (int i = 0; i < 10; i++) { h.frame(HALF); h.frame(NEUTRAL); }
        assertThat(h.detected()).isFalse();
    }

    @Test
    void relativeToTheUsersOwnBaseline() {
        // Narrow eyes (EAR 0.2): an absolute "closed < 0.2" threshold would fire constantly; relative does not.
        java.util.List<io.mosip.registration.liveness.api.FrameAnalysis> b = new java.util.ArrayList<io.mosip.registration.liveness.api.FrameAnalysis>();
        for (int i = 0; i < 5; i++) b.add(Fa.at(i, i).ear(0.2f).build());
        DetectorHarness h = new DetectorHarness(new BlinkDetector(EngineTuning.defaults()), Baseline.of(b));
        Shape narrow = new Shape() { @Override public Fa apply(Fa f) { return f.ear(0.2f); } };
        Shape narrowClosed = new Shape() { @Override public Fa apply(Fa f) { return f.ear(0.1f); } };
        h.frames(20, narrow);
        assertThat(h.detected()).isFalse();
        h.frame(narrowClosed);
        assertThat(h.frame(narrow)).isEqualTo(DetectorStatus.DETECTED);
    }

    @Test
    void framesWithoutLandmarksAreIgnored() {
        DetectorHarness h = h();
        Shape none = new Shape() { @Override public Fa apply(Fa f) { return f.noLandmarks(); } };
        assertThat(h.frames(20, none)).isEqualTo(DetectorStatus.ARMING);
    }
}
