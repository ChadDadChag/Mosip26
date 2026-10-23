package io.mosip.registration.liveness.analyzer.pad;

import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReplayCuesTest {

    @ParameterizedTest
    @ValueSource(ints = {8, 64})
    void fftMatchesANaiveDft(int n) {
        Random rnd = new Random(n);
        double[] re = new double[n * n], im = new double[n * n];
        for (int i = 0; i < re.length; i++) {
            re[i] = rnd.nextGaussian();
            im[i] = rnd.nextGaussian();
        }
        double[] wantRe = new double[n * n], wantIm = new double[n * n];
        for (int ku = 0; ku < n; ku++) {
            for (int kv = 0; kv < n; kv++) {
                double sr = 0, si = 0;
                for (int r = 0; r < n; r++) {
                    for (int c = 0; c < n; c++) {
                        double a = -2 * Math.PI * ((double) ku * r / n + (double) kv * c / n);
                        double cr = Math.cos(a), ci = Math.sin(a);
                        sr += re[r * n + c] * cr - im[r * n + c] * ci;
                        si += re[r * n + c] * ci + im[r * n + c] * cr;
                    }
                }
                wantRe[ku * n + kv] = sr;
                wantIm[ku * n + kv] = si;
            }
        }
        new Fft(n).transform2d(re, im);
        double maxErr = 0, maxMag = 0;
        for (int i = 0; i < re.length; i++) {
            maxErr = Math.max(maxErr, Math.hypot(re[i] - wantRe[i], im[i] - wantIm[i]));
            maxMag = Math.max(maxMag, Math.hypot(wantRe[i], wantIm[i]));
        }
        assertThat(maxErr / maxMag).isLessThan(1e-6);
    }

    @Test
    void fftRejectsNonPowersOfTwo() {
        assertThatThrownBy(() -> new Fft(48)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fft(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hannIsTheSymmetricNumpyWindow() {
        double[] w = ReplayCues.hann(64);
        assertThat(w[0]).isCloseTo(0, within(1e-15));
        assertThat(w[63]).isCloseTo(0, within(1e-15));
        for (int k = 0; k < 64; k++) assertThat(w[k]).isCloseTo(w[63 - k], within(1e-15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    void matchesTheReference(String name) throws Exception {
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(golden, "primary"));
        ReplayCues.Cues c = new ReplayCues().compute(img.rgb, img.width, img.height, primary);
        Map<String, Object> want = MiniJson.getObject(golden, "replay");
        double dMoire = Math.abs(c.moire - MiniJson.getDouble(want, "moire"));
        double dGlare = Math.abs(c.glare - MiniJson.getDouble(want, "glare"));
        assertThat(dMoire).isLessThan(1e-3);
        assertThat(dGlare).isLessThan(1e-3);
        System.out.printf("Replay parity %-26s moire %.4f (err %.2e)  glare %.4f (err %.2e)%n", name, c.moire, dMoire, c.glare, dGlare);
    }

    @Test
    void aHighFrequencyGratingRaisesMoire() throws Exception {
        GoldenFixtures.Image img = GoldenFixtures.image("portrait_frontal");
        FaceDetection face = GoldenFixtures.detection(MiniJson.getObject(GoldenFixtures.json("portrait_frontal"), "primary"));
        ReplayCues cues = new ReplayCues();
        double plain = cues.compute(img.rgb, img.width, img.height, face).moire;
        byte[] grating = img.rgb.clone();
        // An 8-px vertical grating over the face: about 20 cycles across the 64 x 64 crop, inside the 8..31 ring.
        for (int y = (int) face.y; y < face.y + face.h; y++) {
            for (int x = (int) face.x; x < face.x + face.w; x++) {
                double d = 40 * Math.sin(2 * Math.PI * x / 8.0);
                for (int ch = 0; ch < 3; ch++) {
                    int i = (y * img.width + x) * 3 + ch;
                    grating[i] = (byte) Math.max(0, Math.min(255, (int) Math.round((grating[i] & 0xFF) + d)));
                }
            }
        }
        double striped = cues.compute(grating, img.width, img.height, face).moire;
        assertThat(striped).isGreaterThan(plain + 0.5);
        System.out.printf("Replay grating: plain face moire %.3f, with an 8-px grating %.3f%n", plain, striped);
    }

    @Test
    void aSaturatedPatchGivesTheExpectedGlareFraction() {
        int w = 200, h = 200;
        byte[] rgb = new byte[w * h * 3];
        Arrays.fill(rgb, (byte) 100);
        for (int y = 50; y < 150; y++) {
            for (int x = 50; x < 100; x++) {
                for (int ch = 0; ch < 3; ch++) rgb[(y * w + x) * 3 + ch] = (byte) 255;
            }
        }
        FaceDetection face = new FaceDetection(50, 50, 100, 100, 0.9, new double[10]);
        ReplayCues.Cues c = new ReplayCues().compute(rgb, w, h, face);
        // The left half of the box is saturated. Crop pixels on the patch's edges blend with the grey around it and
        // fall below 250 (about 54 x 110 of the 112 x 112 crop pixels stay saturated: 0.47).
        assertThat((double) c.glare).isBetween(0.45, 0.5);
    }
}
