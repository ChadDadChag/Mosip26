package io.mosip.registration.liveness.analyzer.pad;

/**
 * In-place iterative radix-2 complex FFT for n x n images (row FFTs, then column FFTs), with the bit-reversal
 * permutation and twiddles precomputed once. Forward transform, no scaling (numpy.fft.fft2 convention).
 */
final class Fft {

    private final int n;
    private final int[] reversed;
    private final double[] cos, sin;
    private final double[] colRe, colIm;

    Fft(int n) {
        if (n < 2 || Integer.bitCount(n) != 1) throw new IllegalArgumentException("n must be a power of two >= 2");
        this.n = n;
        int bits = Integer.numberOfTrailingZeros(n);
        reversed = new int[n];
        for (int i = 0; i < n; i++) reversed[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int k = 0; k < n / 2; k++) {
            double a = -2 * Math.PI * k / n;
            cos[k] = Math.cos(a);
            sin[k] = Math.sin(a);
        }
        colRe = new double[n];
        colIm = new double[n];
    }

    /** 2-D forward FFT of the row-major n x n complex array (re, im), in place. */
    void transform2d(double[] re, double[] im) {
        for (int r = 0; r < n; r++) transform(re, im, r * n, 1);
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n; r++) {
                colRe[r] = re[r * n + c];
                colIm[r] = im[r * n + c];
            }
            transform(colRe, colIm, 0, 1);
            for (int r = 0; r < n; r++) {
                re[r * n + c] = colRe[r];
                im[r * n + c] = colIm[r];
            }
        }
    }

    /** 1-D forward FFT of n elements starting at offset (stride 1), in place. */
    void transform(double[] re, double[] im, int offset, int stride) {
        for (int i = 0; i < n; i++) {
            int j = reversed[i];
            if (j > i) {
                int a = offset + i * stride, b = offset + j * stride;
                double t = re[a]; re[a] = re[b]; re[b] = t;
                t = im[a]; im[a] = im[b]; im[b] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int start = 0; start < n; start += size) {
                for (int k = 0; k < half; k++) {
                    double wr = cos[k * step], wi = sin[k * step];
                    int a = offset + (start + k) * stride, b = offset + (start + k + half) * stride;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}
