package io.mosip.registration.liveness.analyzer.pad;

import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.image.ImageOps;
import io.mosip.registration.liveness.analyzer.quality.QualityAssessor;

import java.util.Arrays;

/**
 * Replay/print cues for the audit trail ({@code reference_pipeline.replay_cues}); not fused into the score.
 * <ul>
 *   <li>moire: 64 x 64 luma crop of the face box, minus its mean, times a 2-D symmetric Hann window
 *       (w[k] = 0.5 - 0.5 cos(2 pi k / 63)); DFT magnitude over frequencies with 8 &lt;= sqrt(u^2 + v^2) &lt;= 31
 *       (DC at the centre after fftshift); moire = log10((max + 1e-6) / (median + 1e-6)). A screen's pixel grid
 *       or a print's halftone shows up as isolated peaks.</li>
 *   <li>glare: fraction of the 112 x 112 RGB crop's pixels with R, G and B all &gt;= 250.</li>
 * </ul>
 * One instance per analyzer: buffers are reused; not thread-safe.
 */
public final class ReplayCues {

    static final int N = 64;
    static final int GLARE_SIZE = 112;
    static final int R_MIN = 8, R_MAX = 31;
    static final double EPS = 1e-6;
    static final float GLARE_LEVEL = 250f;

    /** Moire and glare of one face. */
    public static final class Cues {
        public final float moire, glare;

        Cues(float moire, float glare) {
            this.moire = moire;
            this.glare = glare;
        }
    }

    private final Fft fft = new Fft(N);
    private final double[] window = new double[N * N];
    private final int[] selected; // row-major indices of the unshifted spectrum inside the ring
    private final float[] gray = new float[N * N];
    private final double[] re = new double[N * N], im = new double[N * N];
    private final double[] mags;
    private final float[] rgb = new float[3 * GLARE_SIZE * GLARE_SIZE];

    public ReplayCues() {
        double[] hann = hann(N);
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) window[r * N + c] = hann[r] * hann[c];
        int count = 0;
        int[] tmp = new int[N * N];
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                int v = r < N / 2 ? r : r - N, u = c < N / 2 ? c : c - N; // frequency after fftshift
                double radius = Math.sqrt((double) u * u + (double) v * v);
                if (radius >= R_MIN && radius <= R_MAX) tmp[count++] = r * N + c;
            }
        }
        selected = Arrays.copyOf(tmp, count);
        mags = new double[count];
    }

    /** Symmetric Hann window of length n (numpy.hanning). */
    static double[] hann(int n) {
        double[] w = new double[n];
        for (int k = 0; k < n; k++) w[k] = 0.5 - 0.5 * Math.cos(2 * Math.PI * k / (n - 1));
        return w;
    }

    public Cues compute(byte[] frameRgb, int width, int height, FaceDetection face) {
        return new Cues((float) moire(frameRgb, width, height, face), (float) glare(frameRgb, width, height, face));
    }

    double moire(byte[] frameRgb, int width, int height, FaceDetection face) {
        ImageOps.sample(frameRgb, width, height, QualityAssessor.boxAffine(face, N), N, N, ImageOps.Layout.GRAY_0_255, gray);
        double sum = 0;
        for (float g : gray) sum += g;
        double mean = sum / gray.length;
        for (int i = 0; i < gray.length; i++) {
            re[i] = (gray[i] - mean) * window[i];
            im[i] = 0;
        }
        fft.transform2d(re, im);
        double max = 0;
        for (int i = 0; i < selected.length; i++) {
            int k = selected[i];
            double m = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            mags[i] = m;
            if (m > max) max = m;
        }
        Arrays.sort(mags);
        int mid = mags.length / 2;
        double median = mags.length % 2 == 0 ? (mags[mid - 1] + mags[mid]) / 2 : mags[mid];
        return Math.log10((max + EPS) / (median + EPS));
    }

    double glare(byte[] frameRgb, int width, int height, FaceDetection face) {
        ImageOps.sample(frameRgb, width, height, QualityAssessor.boxAffine(face, GLARE_SIZE), GLARE_SIZE, GLARE_SIZE,
                ImageOps.Layout.NCHW_BGR_0_255, rgb);
        int plane = GLARE_SIZE * GLARE_SIZE, bright = 0;
        for (int i = 0; i < plane; i++) {
            if (rgb[i] >= GLARE_LEVEL && rgb[plane + i] >= GLARE_LEVEL && rgb[2 * plane + i] >= GLARE_LEVEL) bright++;
        }
        return (double) bright / plane;
    }
}
