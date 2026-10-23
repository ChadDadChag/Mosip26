package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.Frame;

/**
 * Focus measure for best-frame selection: variance of the 4-neighbour Laplacian of the luma inside the face box,
 * sampled on a grid of at most {@link #MAX_SAMPLES_PER_SIDE} points per side so the cost stays small whatever the
 * resolution. Higher = sharper. Pixels are read only here and nothing derived from them leaves this method.
 */
public final class Sharpness {

    /** TUNE: grid resolution of the measurement. */
    static final int MAX_SAMPLES_PER_SIDE = 96;

    private Sharpness() { }

    /** @return the Laplacian variance inside {@code box} ([x, y, w, h] in pixels), or 0 if it cannot be measured. */
    public static float laplacianVariance(Frame frame, float[] box) {
        if (frame == null || box == null || box.length < 4) return 0f;
        int w = frame.width(), h = frame.height();
        int x0 = clamp((int) box[0], 0, w - 1), y0 = clamp((int) box[1], 0, h - 1);
        int x1 = clamp((int) (box[0] + box[2]), 0, w - 1), y1 = clamp((int) (box[1] + box[3]), 0, h - 1);
        int step = Math.max(1, Math.max(x1 - x0, y1 - y0) / MAX_SAMPLES_PER_SIDE);
        if (x1 - x0 < 3 * step || y1 - y0 < 3 * step) return 0f;
        byte[] rgb = frame.rgb();
        double sum = 0, sumSq = 0;
        int n = 0;
        for (int y = y0 + step; y < y1 - step; y += step) {
            for (int x = x0 + step; x < x1 - step; x += step) {
                float lap = luma(rgb, w, x - step, y) + luma(rgb, w, x + step, y) + luma(rgb, w, x, y - step)
                        + luma(rgb, w, x, y + step) - 4f * luma(rgb, w, x, y);
                sum += lap;
                sumSq += (double) lap * lap;
                n++;
            }
        }
        if (n == 0) return 0f;
        double mean = sum / n;
        return (float) Math.max(0, sumSq / n - mean * mean);
    }

    private static float luma(byte[] rgb, int width, int x, int y) {
        int i = (y * width + x) * 3;
        return 0.299f * (rgb[i] & 0xFF) + 0.587f * (rgb[i + 1] & 0xFF) + 0.114f * (rgb[i + 2] & 0xFF);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
