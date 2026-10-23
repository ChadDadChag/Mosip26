package io.mosip.registration.liveness.analyzer.image;

import io.mosip.registration.liveness.analyzer.image.ImageOps.Layout;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ImageOpsTest {

    /** RGB888 image where every channel of pixel (x, y) is given by the function below. */
    private static byte[] image(int w, int h, int seed) {
        byte[] rgb = new byte[w * h * 3];
        new Random(seed).nextBytes(rgb);
        return rgb;
    }

    private static int px(byte[] rgb, int w, int x, int y, int c) {
        return rgb[(y * w + x) * 3 + c] & 0xFF;
    }

    @Test
    void identityAffineIsExact() {
        int w = 7, h = 5;
        byte[] rgb = image(w, h, 1);
        float[] out = new float[3 * w * h];
        ImageOps.sample(rgb, w, h, Affine.scale(w, h, w, h), w, h, Layout.NCHW_BGR_0_255, out);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = y * w + x;
                assertThat(out[o]).isEqualTo(px(rgb, w, x, y, 2));          // B plane
                assertThat(out[w * h + o]).isEqualTo(px(rgb, w, x, y, 1));  // G plane
                assertThat(out[2 * w * h + o]).isEqualTo(px(rgb, w, x, y, 0)); // R plane
            }
        }
    }

    @Test
    void twoTimesDownscaleOfAGradientMatchesTheAnalyticValue() {
        // value(x) = 10 x; dst i samples src x = (i + 0.5) * 2 - 0.5 = 2i + 0.5, so the value is 20 i + 5.
        int w = 8, h = 4;
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) rgb[(y * w + x) * 3 + c] = (byte) (10 * x);
        float[] out = new float[4 * 2 * 3];
        ImageOps.sample(rgb, w, h, Affine.scale(w, h, 4, 2), 4, 2, Layout.NHWC_RGB_0_1, out);
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 4; i++) {
                for (int c = 0; c < 3; c++) assertThat(out[(j * 4 + i) * 3 + c]).isCloseTo((20 * i + 5) / 255f, within(1e-6f));
            }
        }
    }

    @Test
    void outOfFrameSamplesReplicateTheBorder() {
        int w = 4, h = 3;
        byte[] rgb = image(w, h, 2);
        float[] out = new float[3 * 3 * 3];
        // A 3x3 crop whose top-left is 5 px left of and above the frame: every sample clamps to pixel (0, 0)...
        ImageOps.sample(rgb, w, h, Affine.crop(-5, -5, 3, 3, 3, 3), 3, 3, Layout.NCHW_BGR_0_255, out);
        for (int k = 0; k < 9; k++) assertThat(out[2 * 9 + k]).isEqualTo(px(rgb, w, 0, 0, 0));
        // ...and a crop far right/below clamps to the bottom-right pixel.
        ImageOps.sample(rgb, w, h, Affine.crop(10, 10, 3, 3, 3, 3), 3, 3, Layout.NCHW_BGR_0_255, out);
        for (int k = 0; k < 9; k++) assertThat(out[2 * 9 + k]).isEqualTo(px(rgb, w, w - 1, h - 1, 0));
    }

    @Test
    void ninetyDegreeRotationIsAnExactPixelPermutation() {
        int n = 6;
        byte[] rgb = image(n, n, 3);
        float[] out = new float[3 * n * n];
        ImageOps.sample(rgb, n, n, Affine.rotatedSquare(n / 2.0, n / 2.0, n, Math.PI / 2, n), n, n, Layout.NCHW_BGR_0_255, out);
        // X = cx - dy, Y = cy + dx  =>  dst(i, j) = src(x = n - 1 - j, y = i)
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                assertThat(out[2 * n * n + j * n + i]).isEqualTo(px(rgb, n, n - 1 - j, i, 0));
            }
        }
    }

    @Test
    void layoutsHaveTheRightChannelOrderAndScale() {
        byte[] rgb = {(byte) 200, 100, 50};
        float[] nchw = new float[3], nhwc = new float[3], gray = new float[1];
        Affine one = Affine.scale(1, 1, 1, 1);
        ImageOps.sample(rgb, 1, 1, one, 1, 1, Layout.NCHW_BGR_0_255, nchw);
        ImageOps.sample(rgb, 1, 1, one, 1, 1, Layout.NHWC_RGB_0_1, nhwc);
        ImageOps.sample(rgb, 1, 1, one, 1, 1, Layout.GRAY_0_255, gray);
        assertThat(nchw).containsExactly(50f, 100f, 200f);
        assertThat(nhwc).containsExactly(200f / 255f, 100f / 255f, 50f / 255f);
        assertThat(gray[0]).isCloseTo((float) (0.299 * 200 + 0.587 * 100 + 0.114 * 50), within(1e-4f));
        assertThat(ImageOps.size(Layout.GRAY_0_255, 4, 3)).isEqualTo(12);
        assertThat(ImageOps.size(Layout.NHWC_RGB_0_1, 4, 3)).isEqualTo(36);
    }

    @Test
    void zeroPaddingClearsAReusedBuffer() {
        int w = 3, h = 2, padW = 5, padH = 4;
        byte[] rgb = new byte[w * h * 3];
        Arrays.fill(rgb, (byte) 7);
        for (Layout layout : Layout.values()) {
            float[] out = new float[ImageOps.size(layout, padW, padH)];
            Arrays.fill(out, -1f); // garbage from a previous frame
            ImageOps.sample(rgb, w, h, Affine.scale(w, h, w, h), w, h, layout, out, padW, padH);
            int channels = layout == Layout.GRAY_0_255 ? 1 : 3;
            float inside = layout == Layout.NHWC_RGB_0_1 ? 7f / 255f : 7f;
            for (int c = 0; c < channels; c++) {
                for (int y = 0; y < padH; y++) {
                    for (int x = 0; x < padW; x++) {
                        int idx = layout == Layout.NHWC_RGB_0_1 ? (y * padW + x) * 3 + c : c * padW * padH + y * padW + x;
                        float expected = x < w && y < h ? inside : 0f;
                        assertThat(out[idx]).as("%s c=%d x=%d y=%d", layout, c, x, y).isCloseTo(expected, within(1e-5f));
                    }
                }
            }
        }
    }

    @Test
    void rejectsInvalidArguments() {
        byte[] rgb = new byte[12];
        Affine m = Affine.scale(2, 2, 2, 2);
        assertThatThrownBy(() -> ImageOps.sample(null, 2, 2, m, 2, 2, Layout.GRAY_0_255, new float[4]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageOps.sample(rgb, 3, 3, m, 2, 2, Layout.GRAY_0_255, new float[4]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageOps.sample(rgb, 2, 2, m, 2, 2, Layout.GRAY_0_255, new float[3]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageOps.sample(rgb, 2, 2, m, 2, 2, Layout.GRAY_0_255, new float[4], 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void affineMapsPoints() {
        Affine r = Affine.rotatedSquare(10, 20, 8, 0, 4);
        // theta = 0: u in [0, 4] maps to X in [cx - S/2, cx + S/2]
        assertThat(r.mapX(0, 0)).isCloseTo(6.0, within(1e-12));
        assertThat(r.mapY(4, 4)).isCloseTo(24.0, within(1e-12));
    }
}
