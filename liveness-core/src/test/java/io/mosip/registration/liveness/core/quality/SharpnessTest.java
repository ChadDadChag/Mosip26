package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharpnessTest {

    static final int W = Fa.W, H = Fa.H;

    /** Fine checkerboard (sharp detail) or a smooth gradient (blurred) inside the whole frame. */
    static Frame frame(boolean sharp, long seq) {
        byte[] rgb = new byte[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = sharp ? (((x / 3) + (y / 3)) % 2 == 0 ? 40 : 210) : 60 + (x + y) * 100 / (W + H);
                int i = (y * W + x) * 3;
                rgb[i] = rgb[i + 1] = rgb[i + 2] = (byte) v;
            }
        }
        return new Frame(rgb, W, H, seq, seq, "test");
    }

    static FrameAnalysis withFrame(FrameAnalysis a, Frame f) {
        return new FrameAnalysis(f, a.faceCount, a.faceBox, a.landmarks, a.yawDeg, a.pitchDeg, a.rollDeg,
                a.qualityIssues, a.livenessScore, a.padDetails, a.processingMs);
    }

    @Test
    void sharpDetailScoresFarHigherThanASmoothImage() {
        float[] box = Fa.at(0, 0).build().faceBox;
        float sharp = Sharpness.laplacianVariance(frame(true, 1), box);
        float blurred = Sharpness.laplacianVariance(frame(false, 2), box);
        assertThat(sharp).isGreaterThan(1000f);
        assertThat(blurred).isLessThan(5f);
    }

    @Test
    void degenerateInputsAreZero() {
        Frame f = frame(true, 1);
        assertThat(Sharpness.laplacianVariance(null, new float[] {0, 0, 10, 10})).isZero();
        assertThat(Sharpness.laplacianVariance(f, null)).isZero();
        assertThat(Sharpness.laplacianVariance(f, new float[] {10, 10, 1, 1})).isZero();
        // A box partly outside the frame is clamped, not an error.
        assertThat(Sharpness.laplacianVariance(f, new float[] {-50, -50, 200, 200})).isPositive();
    }

    @Test
    void bestFramePrefersTheSharperOfTwoEquallyFrontalFrames() {
        BestFrameSelector s = new BestFrameSelector(EngineTuning.defaults());
        FrameAnalysis base = Fa.at(0, 0).build();
        FrameAnalysis blurred = withFrame(base, frame(false, 1));
        FrameAnalysis sharp = withFrame(base, frame(true, 2));
        s.offer(blurred);
        s.offer(sharp);
        assertThat(s.bestFrame()).isSameAs(sharp.frame);
        s.reset();
        s.offer(sharp);
        s.offer(blurred);
        assertThat(s.bestFrame()).as("order does not matter").isSameAs(sharp.frame);
    }

    @Test
    void sharpnessDoesNotOverrideTheQualificationRules() {
        BestFrameSelector s = new BestFrameSelector(EngineTuning.defaults());
        FrameAnalysis turnedButSharp = withFrame(Fa.at(0, 0).yaw(14).build(), frame(true, 1));
        assertThat(s.offer(turnedButSharp)).isFalse();
    }
}
