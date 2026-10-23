package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.quality.Iso19794Check.Issue;
import io.mosip.registration.liveness.core.quality.Iso19794Check.Status;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Iso19794CheckTest {

    private final EngineTuning t = EngineTuning.defaults();

    private Iso19794Check check(Fa f) {
        return Iso19794Check.of(f.build(), t, true);
    }

    @Test
    void aFrontalOpenEyedLargeEnoughFaceIsCompliant() {
        Iso19794Check c = check(Fa.at(0, 0).yaw(5).pitch(-5).roll(8));
        assertThat(c.status).isEqualTo(Status.COMPLIANT);
        assertThat(c.issues).isEmpty();
        assertThat(c.tier()).isEqualTo(2);
        assertThat(c.eyeDistancePx).isEqualTo(Fa.IOD, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(c.yawDeg).isEqualTo(5f);
        assertThat(c.rollDeg).isEqualTo(8f);
    }

    @Test
    void eachBrokenLimitIsNamed() {
        assertThat(check(Fa.at(0, 0).yaw(-6)).issues).containsExactly(Issue.POSE);
        assertThat(check(Fa.at(0, 0).pitch(6)).issues).containsExactly(Issue.POSE);
        assertThat(check(Fa.at(0, 0).roll(-9)).issues).containsExactly(Issue.ROLL);
        assertThat(check(Fa.at(0, 0).boxScale(0.85f)).issues).containsExactly(Issue.EYE_DISTANCE); // 85 px
        assertThat(check(Fa.at(0, 0).eyesClosed()).issues).containsExactly(Issue.EYES_CLOSED);
        assertThat(check(Fa.at(0, 0).issue(QualityIssue.TOO_DARK)).issues).containsExactly(Issue.QUALITY);
        assertThat(check(Fa.at(0, 0).faces(2)).issues).containsExactly(Issue.QUALITY);
        assertThat(Iso19794Check.of(Fa.at(0, 0).build(), t, false).issues).containsExactly(Issue.EXPRESSION);
        Iso19794Check several = check(Fa.at(0, 0).yaw(9).roll(12).boxScale(0.5f));
        assertThat(several.issues).containsExactly(Issue.POSE, Issue.ROLL, Issue.EYE_DISTANCE);
        assertThat(several.status).isEqualTo(Status.NOT_COMPLIANT);
        assertThat(several.tier()).isZero();
    }

    @Test
    void whatCannotBeMeasuredIsUnknownNotAFailure() {
        assertThat(check(Fa.at(0, 0).yaw(Float.NaN).pitch(Float.NaN)).status).isEqualTo(Status.UNKNOWN);
        assertThat(check(Fa.at(0, 0).roll(Float.NaN)).status).isEqualTo(Status.UNKNOWN);
        Iso19794Check noLandmarks = check(Fa.at(0, 0).noLandmarks());
        assertThat(noLandmarks.status).isEqualTo(Status.UNKNOWN);
        assertThat(noLandmarks.issues).isEmpty();
        assertThat(noLandmarks.eyeDistancePx).isNaN();
        assertThat(noLandmarks.tier()).isEqualTo(1);
        // Unknown never hides a limit that IS measured and broken.
        assertThat(check(Fa.at(0, 0).noLandmarks().yaw(8)).status).isEqualTo(Status.NOT_COMPLIANT);
    }

    @Test
    void limitsAreTunable() {
        t.isoMaxYawDeg = 10f;
        t.isoMinEyeDistancePx = 120f;
        assertThat(check(Fa.at(0, 0).yaw(8)).issues).containsExactly(Issue.EYE_DISTANCE);
    }
}
