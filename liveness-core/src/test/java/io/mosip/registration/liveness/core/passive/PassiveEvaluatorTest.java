package io.mosip.registration.liveness.core.passive;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator.Verdict;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PassiveEvaluatorTest {

    private final LivenessPolicy policy = new LivenessPolicy(); // window 10, 0.85 / 0.30, timeout 8000
    private final PassiveEvaluator ev = new PassiveEvaluator(policy, EngineTuning.defaults());

    private Verdict feed(float... scores) {
        Verdict v = Verdict.PENDING;
        for (int i = 0; i < scores.length; i++) v = ev.offer(Fa.at(i * 125, i).score(scores[i]).build(), i * 125);
        return v;
    }

    private static float[] repeat(float s, int n) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, s);
        return a;
    }

    @Test
    void decidesOnlyWhenTheWindowIsFull() {
        ev.reset(0);
        assertThat(feed(repeat(0.95f, 9))).isEqualTo(Verdict.PENDING);
        assertThat(ev.offer(Fa.at(2000, 99).score(0.95f).build(), 2000)).isEqualTo(Verdict.PASS);
        assertThat(ev.median()).isCloseTo(0.95f, within(1e-6f));
    }

    @Test
    void medianNotMaxDecides() {
        ev.reset(0);
        // Two perfect frames cannot lift an otherwise mediocre window.
        assertThat(feed(0.99f, 0.99f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f)).isEqualTo(Verdict.NEED_ACTIVE);
        ev.reset(0);
        // Two noisy low frames do not fail a live person.
        assertThat(feed(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f)).isEqualTo(Verdict.PASS);
    }

    @Test
    void attackBelowPadRejectThresholdNeverFallsBackToActive() {
        ev.reset(0);
        assertThat(feed(repeat(0.2f, 10))).isEqualTo(Verdict.ATTACK);
    }

    @Test
    void boundaryValues() {
        ev.reset(0);
        assertThat(feed(repeat(0.85f, 10))).isEqualTo(Verdict.PASS);
        ev.reset(0);
        assertThat(feed(repeat(0.30f, 10))).isEqualTo(Verdict.NEED_ACTIVE);
    }

    @Test
    void activeDisabledMeansBelowThreshold() {
        policy.activeEnabled = false;
        ev.reset(0);
        assertThat(feed(repeat(0.6f, 10))).isEqualTo(Verdict.BELOW_THRESHOLD);
    }

    @Test
    void faceLossOrSecondFaceRestartsTheWindow() {
        ev.reset(0);
        feed(repeat(0.95f, 9));
        ev.offer(Fa.at(5000, 50).faces(0).build(), 5000);
        assertThat(ev.windowSize()).isZero();
        ev.offer(Fa.at(5100, 51).build(), 5100);
        ev.offer(Fa.at(5200, 52).faces(2).build(), 5200);
        assertThat(ev.windowSize()).isZero();
    }

    @Test
    void ineligibleFramesAreSkippedNotCounted() {
        ev.reset(0);
        for (int i = 0; i < 20; i++) {
            ev.offer(Fa.at(i * 100, i).issue(QualityIssue.BLURRY).build(), i * 100);
            ev.offer(Fa.at(i * 100 + 1, 1000 + i).yaw(40).build(), i * 100 + 1);
            ev.offer(Fa.at(i * 100 + 2, 2000 + i).score(Float.NaN).build(), i * 100 + 2);
        }
        assertThat(ev.windowSize()).isZero();
        // Hint-only issues keep the frame eligible.
        ev.offer(Fa.at(3000, 3000).issue(QualityIssue.FACE_OFF_CENTER).build(), 3000);
        assertThat(ev.windowSize()).isEqualTo(1);
    }

    @Test
    void timeoutReportsTheDominantReason() {
        ev.reset(0);
        for (int i = 0; i < 5; i++) ev.offer(Fa.at(i, i).faces(0).build(), i);
        ev.offer(Fa.at(10, 10).faces(2).build(), 10);
        assertThat(ev.onTick(7999)).isEqualTo(Verdict.PENDING);
        assertThat(ev.onTick(8000)).isEqualTo(Verdict.TIMEOUT);
        assertThat(ev.timeoutCode()).isEqualTo(ErrorCode.FACE_NOT_DETECTED);

        ev.reset(0);
        for (int i = 0; i < 5; i++) ev.offer(Fa.at(i, i).faces(2).build(), i);
        assertThat(ev.timeoutCode()).isEqualTo(ErrorCode.MULTIPLE_FACES);

        ev.reset(0);
        for (int i = 0; i < 5; i++) ev.offer(Fa.at(i, i).issue(QualityIssue.TOO_DARK).build(), i);
        assertThat(ev.timeoutCode()).isEqualTo(ErrorCode.POOR_QUALITY);

        ev.reset(0);
        assertThat(ev.timeoutCode()).isEqualTo(ErrorCode.FACE_NOT_DETECTED);
    }

    @Test
    void padDetailMediansPerKey() {
        ev.reset(0);
        for (int i = 0; i < 10; i++) ev.offer(Fa.at(i * 100, i).score(0.2f).pad("moire", i < 5 ? 0.8f : 0.9f).build(), i * 100);
        assertThat(ev.padDetailMedians()).containsOnlyKeys("moire");
        assertThat(ev.padDetailMedians().get("moire")).isCloseTo(0.85f, within(1e-6f));
    }
}
