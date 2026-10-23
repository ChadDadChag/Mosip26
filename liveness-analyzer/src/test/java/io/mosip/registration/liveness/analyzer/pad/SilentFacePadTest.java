package io.mosip.registration.liveness.analyzer.pad;

import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SilentFacePadTest {

    /** Expected values printed by reference_pipeline.get_new_box (Silent-Face's _get_new_box, verbatim). */
    @Test
    void newBoxMatchesThePythonReference() {
        assertThat(SilentFacePad.newBox(640, 480, new int[] {200, 150, 100, 120}, 2.7)).containsExactly(115, 48, 385, 372);
        assertThat(SilentFacePad.newBox(640, 480, new int[] {5, 3, 100, 120}, 4.0)).containsExactly(0, 0, 399, 479);
        assertThat(SilentFacePad.newBox(640, 480, new int[] {600, 440, 60, 50}, 2.7)).containsExactly(477, 344, 639, 479);
        assertThat(SilentFacePad.newBox(640, 480, new int[] {0, 0, 640, 480}, 2.7)).containsExactly(0, 0, 639, 479);
        assertThat(SilentFacePad.newBox(554, 640, new int[] {150, 180, 250, 300}, 4.0)).containsExactly(8, 0, 541, 639);
        assertThat(SilentFacePad.newBox(320, 240, new int[] {-10, -5, 40, 50}, 2.7)).containsExactly(0, 0, 108, 135);
    }

    @Test
    void intBoxFloorsTheCornerAndRoundsTheSizeHalfUp() {
        FaceDetection f = new FaceDetection(10.5, -0.5, 10.5, 9.49999, 0.9, new double[10]);
        assertThat(SilentFacePad.intBox(f)).containsExactly(10, -1, 11, 9);
    }

    @Test
    void softmaxIsNormalisedAndStable() {
        float[] p = SilentFacePad.softmax(new float[] {1000f, 1001f, 999f});
        assertThat(p[0] + p[1] + p[2]).isCloseTo(1f, within(1e-6f));
        assertThat(p[1]).isGreaterThan(p[0]).isGreaterThan(p[2]);
        assertThat(SilentFacePad.softmax(new float[] {0f, 0f})).containsExactly(0.5f, 0.5f);
    }

    @Test
    void rejectsMissingSessions() {
        assertThatThrownBy(() -> new SilentFacePad(null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
