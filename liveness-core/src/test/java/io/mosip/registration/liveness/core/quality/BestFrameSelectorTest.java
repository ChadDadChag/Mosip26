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
        assertThat(s.bestCheck().status).isEqualTo(Iso19794Check.Status.COMPLIANT);
        s.reset();
        assertThat(s.hasBest()).isFalse();
        assertThat(s.bestCheck()).isNull();
    }

    @Test
    void anIsoCompliantFrameAlwaysBeatsAHigherScoringNonCompliantOne() {
        // Tilted 9° sideways (ROLL) but perfectly frontal: the higher score of the two.
        FrameAnalysis tiltedFrontal = Fa.at(0, 0).roll(9).build();
        // Compliant, but turned 4°: a lower frontality score.
        FrameAnalysis compliant = Fa.at(1, 1).yaw(4).build();
        assertThat(s.offer(tiltedFrontal)).isTrue();
        assertThat(s.bestCheck().issues).containsExactly(Iso19794Check.Issue.ROLL);
        assertThat(s.offer(compliant)).as("the compliant tier wins over score").isTrue();
        assertThat(s.offer(tiltedFrontal)).as("and keeps winning").isFalse();
        assertThat(s.bestFrame()).isSameAs(compliant.frame);
        assertThat(s.bestCheck().status).isEqualTo(Iso19794Check.Status.COMPLIANT);
    }

    @Test
    void withinATierTheScoreDecidesAndUnknownRanksBetween() {
        FrameAnalysis farAway = Fa.at(0, 0).boxScale(0.8f).build();          // 80 px between the eyes
        FrameAnalysis noRoll = Fa.at(1, 1).yaw(3).roll(Float.NaN).build();   // nothing broken, roll unknown
        FrameAnalysis compliant = Fa.at(2, 2).yaw(4).build();
        FrameAnalysis compliantMoreFrontal = Fa.at(3, 3).yaw(1).build();
        assertThat(s.offer(farAway)).isTrue();
        assertThat(s.bestCheck().issues).containsExactly(Iso19794Check.Issue.EYE_DISTANCE);
        assertThat(s.offer(noRoll)).as("UNKNOWN ranks above NOT_COMPLIANT").isTrue();
        assertThat(s.bestCheck().status).isEqualTo(Iso19794Check.Status.UNKNOWN);
        assertThat(s.offer(compliant)).isTrue();
        assertThat(s.offer(farAway)).isFalse();
        assertThat(s.offer(noRoll)).isFalse();
        assertThat(s.offer(compliantMoreFrontal)).as("same tier: higher score wins").isTrue();
        assertThat(s.offer(compliant)).isFalse();
        assertThat(s.bestFrame()).isSameAs(compliantMoreFrontal.frame);
    }

    @Test
    void theExistingQualificationRulesAreUnchanged() {
        // 7° is outside the ISO limit but inside the best-frame limit (10°): still a candidate, just ranked lower.
        assertThat(s.offer(Fa.at(0, 0).yaw(7).build())).isTrue();
        assertThat(s.bestCheck().issues).containsExactly(Iso19794Check.Issue.POSE);
        assertThat(s.offer(Fa.at(1, 1).roll(30).build())).as("roll alone never disqualifies").isTrue();
    }
}
