package io.mosip.registration.liveness.analyzer.image;

/**
 * Bilinear resampling of packed RGB888 frames straight into model input tensors. Java mirror of
 * {@code reference_pipeline.sample_affine}; plain Java with no image library, so it also runs on Android.
 *
 * <p>For each destination pixel (i, j): p = (i + 0.5, j + 0.5); q = M p (continuous source coordinates);
 * x = clamp(q.x - 0.5, 0, W-1), y = clamp(q.y - 0.5, 0, H-1) (replicated border); x0 = floor(x),
 * x1 = min(x0+1, W-1), fx = x - x0 (same for y); value =
 * (1-fy)((1-fx)I[y0,x0] + fx I[y0,x1]) + fy((1-fx)I[y1,x0] + fx I[y1,x1]), computed in double and stored as
 * float. The inner loop advances the source position by the affine's first column, so there is no per-pixel
 * matrix product and no allocation.
 */
public final class ImageOps {

    /** Destination tensor layouts. */
    public enum Layout {
        /** [3][H][W], planes B, G, R, raw 0..255 (YuNet, MiniFASNet). */
        NCHW_BGR_0_255,
        /** [H][W][3], channels R, G, B, scaled to 0..1 (face mesh). */
        NHWC_RGB_0_1,
        /** [H][W], BT.601 luma 0.299 R + 0.587 G + 0.114 B, 0..255. */
        GRAY_0_255
    }

    private ImageOps() { }

    /** Number of floats a destination of this layout and (padded) size needs. */
    public static int size(Layout layout, int width, int height) {
        return layout == Layout.GRAY_0_255 ? width * height : 3 * width * height;
    }

    /** {@link #sample(byte[], int, int, Affine, int, int, Layout, float[], int, int)} without padding. */
    public static void sample(byte[] rgb, int srcW, int srcH, Affine m, int outW, int outH, Layout layout, float[] dst) {
        sample(rgb, srcW, srcH, m, outW, outH, layout, dst, outW, outH);
    }

    /**
     * Samples an outW x outH image into a padW x padH destination ({@code padW >= outW, padH >= outH}). The area
     * right of and below the image is zeroed, so a reused buffer is safe.
     */
    public static void sample(byte[] rgb, int srcW, int srcH, Affine m, int outW, int outH, Layout layout,
                              float[] dst, int padW, int padH) {
        if (rgb == null || srcW <= 0 || srcH <= 0 || rgb.length < srcW * srcH * 3) {
            throw new IllegalArgumentException("invalid RGB888 source");
        }
        if (outW <= 0 || outH <= 0 || padW < outW || padH < outH) {
            throw new IllegalArgumentException("invalid destination size");
        }
        if (dst == null || dst.length < size(layout, padW, padH)) {
            throw new IllegalArgumentException("destination buffer too small");
        }
        final int plane = padW * padH;
        final double maxX = srcW - 1, maxY = srcH - 1;
        for (int j = 0; j < outH; j++) {
            double py = j + 0.5;
            double qx = m.a * 0.5 + m.b * py + m.c;
            double qy = m.d * 0.5 + m.e * py + m.f;
            for (int i = 0; i < outW; i++, qx += m.a, qy += m.d) {
                double x = qx - 0.5, y = qy - 0.5;
                if (x < 0) x = 0; else if (x > maxX) x = maxX;
                if (y < 0) y = 0; else if (y > maxY) y = maxY;
                int x0 = (int) x, y0 = (int) y; // non-negative, so the cast is floor
                int x1 = x0 + 1 < srcW ? x0 + 1 : x0;
                int y1 = y0 + 1 < srcH ? y0 + 1 : y0;
                double fx = x - x0, fy = y - y0;
                int p00 = (y0 * srcW + x0) * 3, p01 = (y0 * srcW + x1) * 3;
                int p10 = (y1 * srcW + x0) * 3, p11 = (y1 * srcW + x1) * 3;
                float r = lerp(rgb, p00, p01, p10, p11, fx, fy);
                float g = lerp(rgb, p00 + 1, p01 + 1, p10 + 1, p11 + 1, fx, fy);
                float b = lerp(rgb, p00 + 2, p01 + 2, p10 + 2, p11 + 2, fx, fy);
                int o = j * padW + i;
                switch (layout) {
                    case NCHW_BGR_0_255:
                        dst[o] = b;
                        dst[plane + o] = g;
                        dst[2 * plane + o] = r;
                        break;
                    case NHWC_RGB_0_1:
                        dst[3 * o] = r / 255f;
                        dst[3 * o + 1] = g / 255f;
                        dst[3 * o + 2] = b / 255f;
                        break;
                    default:
                        dst[o] = (float) (0.299 * r + 0.587 * g + 0.114 * b);
                        break;
                }
            }
        }
        if (padW > outW || padH > outH) zeroPadding(layout, dst, outW, outH, padW, padH);
    }

    private static float lerp(byte[] rgb, int p00, int p01, int p10, int p11, double fx, double fy) {
        double top = (1 - fx) * (rgb[p00] & 0xFF) + fx * (rgb[p01] & 0xFF);
        double bottom = (1 - fx) * (rgb[p10] & 0xFF) + fx * (rgb[p11] & 0xFF);
        return (float) ((1 - fy) * top + fy * bottom);
    }

    private static void zeroPadding(Layout layout, float[] dst, int outW, int outH, int padW, int padH) {
        int channels = layout == Layout.GRAY_0_255 ? 1 : 3;
        boolean planar = layout != Layout.NHWC_RGB_0_1;
        int planeCount = planar ? channels : 1;
        int perPixel = planar ? 1 : channels;
        for (int p = 0; p < planeCount; p++) {
            int base = p * padW * padH;
            for (int j = 0; j < padH; j++) {
                int from = j < outH ? outW : 0;
                int rowStart = base + (j * padW + from) * perPixel;
                int rowEnd = base + (j * padW + padW) * perPixel;
                for (int k = rowStart; k < rowEnd; k++) dst[k] = 0f;
            }
        }
    }
}
