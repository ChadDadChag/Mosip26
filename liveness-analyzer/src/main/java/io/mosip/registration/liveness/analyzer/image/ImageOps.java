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

    /**
     * OpenCV's {@code warpAffine(src, dst, M, Size(outW, outH), INTER_LINEAR)} on an 8-bit image (default
     * BORDER_CONSTANT 0) into an NCHW R, G, B tensor of raw 0..255 values, as FaceRecognizerSF feeds SFace. Java
     * mirror of {@code reference_pipeline.warp_affine_cv} + {@code sface_input}. Unlike {@link #sample}, {@code m}
     * maps SOURCE pixel indices to DESTINATION pixel indices and pixel centres are at integer coordinates: destination
     * pixel (i, j) samples the source at (X, Y) = M<sup>-1</sup> (i, j) (OpenCV's invertAffineTransform), bilinear over
     * the 4 neighbours with a neighbour outside the image counting as 0, rounded half up to 0..255. OpenCV quantises
     * the sub-pixel position to 1/32 pixel; this does not (at most 1 grey level apart, see make_golden.py).
     *
     * @param m 2 x 3, row major: {a, b, c, d, e, f}
     */
    public static void warpAffineOpenCv(byte[] rgb, int srcW, int srcH, double[] m, int outW, int outH, float[] dst) {
        if (rgb == null || srcW <= 0 || srcH <= 0 || rgb.length < srcW * srcH * 3) {
            throw new IllegalArgumentException("invalid RGB888 source");
        }
        if (m == null || m.length != 6 || outW <= 0 || outH <= 0) throw new IllegalArgumentException("invalid warp");
        if (dst == null || dst.length < 3 * outW * outH) throw new IllegalArgumentException("destination buffer too small");
        double det = m[0] * m[4] - m[1] * m[3];
        det = det != 0 ? 1.0 / det : 0.0;
        double i11 = m[4] * det, i12 = -m[1] * det, i21 = -m[3] * det, i22 = m[0] * det;
        double b1 = -i11 * m[2] - i12 * m[5], b2 = -i21 * m[2] - i22 * m[5];
        int plane = outW * outH;
        for (int j = 0; j < outH; j++) {
            for (int i = 0; i < outW; i++) {
                double x = i11 * i + i12 * j + b1, y = i21 * i + i22 * j + b2;
                int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
                double fx = x - x0, fy = y - y0;
                int o = j * outW + i;
                for (int ch = 0; ch < 3; ch++) {
                    double top = (1 - fx) * at(rgb, srcW, srcH, x0, y0, ch) + fx * at(rgb, srcW, srcH, x0 + 1, y0, ch);
                    double bottom = (1 - fx) * at(rgb, srcW, srcH, x0, y0 + 1, ch)
                            + fx * at(rgb, srcW, srcH, x0 + 1, y0 + 1, ch);
                    double v = Math.floor((1 - fy) * top + fy * bottom + 0.5);
                    dst[ch * plane + o] = (float) (v < 0 ? 0 : v > 255 ? 255 : v);
                }
            }
        }
    }

    /** Channel {@code ch} (0 = R) of pixel (x, y), or 0 outside the image (BORDER_CONSTANT). */
    private static int at(byte[] rgb, int w, int h, int x, int y, int ch) {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        return rgb[(y * w + x) * 3 + ch] & 0xFF;
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
