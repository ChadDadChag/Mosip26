package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FaceGeometryTest {

    @Test
    void earMatchesConstructedEyes() {
        assertThat(FaceGeometry.meanEar(Fa.at(0, 0).build().landmarks)).isCloseTo(Fa.OPEN_EAR, within(1e-4f));
        assertThat(FaceGeometry.meanEar(Fa.at(0, 0).eyesClosed().build().landmarks)).isCloseTo(0.08f, within(1e-4f));
    }

    @Test
    void ratiosAreIndependentOfDistanceToCamera() {
        FrameAnalysis near = Fa.at(0, 0).boxScale(1.5f).build();
        FrameAnalysis far = Fa.at(0, 0).boxScale(0.6f).build();
        assertThat(FaceGeometry.interOcular(near.landmarks)).isCloseTo(150f, within(0.01f));
        assertThat(FaceGeometry.mouthWidthRatio(near.landmarks))
                .isCloseTo(FaceGeometry.mouthWidthRatio(far.landmarks), within(1e-4f));
        assertThat(FaceGeometry.meanEar(near.landmarks)).isCloseTo(FaceGeometry.meanEar(far.landmarks), within(1e-4f));
    }

    @Test
    void smileWidensMouthAndLiftsCorners() {
        FrameAnalysis neutral = Fa.at(0, 0).build(), smile = Fa.at(0, 0).smile().build();
        assertThat(FaceGeometry.mouthWidthRatio(smile.landmarks) / FaceGeometry.mouthWidthRatio(neutral.landmarks))
                .isCloseTo(1.3f, within(1e-3f));
        assertThat(FaceGeometry.cornerLift(neutral.landmarks)).isCloseTo(0f, within(1e-4f));
        assertThat(FaceGeometry.cornerLift(smile.landmarks)).isGreaterThan(0.05f);
    }

    @Test
    void directionConventionIsTheContractsAndLivesHere() {
        // Contract: +yaw = subject turned to THEIR left. No mirroring correction.
        assertThat(FaceGeometry.userYaw(Fa.at(0, 0).yaw(25).build())).isEqualTo(25f);
        assertThat(FaceGeometry.userYaw(Fa.at(0, 0).yaw(-25).build())).isEqualTo(-25f);
        // Assumed (proposal P2): +pitch = looking up.
        assertThat(FaceGeometry.PITCH_SIGN).isEqualTo(1f);
        assertThat(FaceGeometry.userPitch(Fa.at(0, 0).pitch(15).build())).isEqualTo(15f);
    }

    @Test
    void iouAndMissingInputs() {
        assertThat(FaceGeometry.iou(new float[] {0, 0, 10, 10}, new float[] {0, 0, 10, 10})).isEqualTo(1f);
        assertThat(FaceGeometry.iou(new float[] {0, 0, 10, 10}, new float[] {5, 0, 10, 10})).isCloseTo(1f / 3f, within(1e-5f));
        assertThat(FaceGeometry.iou(new float[] {0, 0, 10, 10}, new float[] {50, 50, 10, 10})).isEqualTo(0f);
        assertThat(FaceGeometry.iou(null, new float[] {0, 0, 1, 1})).isNaN();
        assertThat(FaceGeometry.meanEar(null)).isNaN();
        assertThat(FaceGeometry.mouthWidthRatio(Fa.at(0, 0).noLandmarks().build().landmarks)).isNaN();
    }
}
