package io.mosip.registration.liveness.analyzer.heuristic;

/**
 * Pure, stateless image-quality and texture signals computed directly from a packed
 * RGB888 buffer (row-major, no padding - the same layout {@link io.mosip.registration.liveness.api.Frame}
 * uses). No image library dependency: everything here operates on the raw byte[].
 *
 * <p>Each method implements a single, well-established classical computer-vision
 * technique (cited in the Javadoc). None of these are machine-learning models; they
 * are the kind of cheap, explainable signals a heuristic {@code FaceAnalyzer} can
 * combine into a coarse liveness/PAD score, or that a real ML-based analyzer could
 * still use as auxiliary features alongside a trained model.</p>
 */
public final class ImageQualityMetrics {

    private ImageQualityMetrics() {
    }

    /**
     * Mean luma (perceived brightness) of the region, 0..255, using the standard
     * ITU-R BT.601 luma weights (0.299 R + 0.587 G + 0.114 B).
     */
    public static double meanLuma(byte[] rgb, int width, int height, int x, int y, int w, int h) {
        requireRegion(rgb, width, height, x, y, w, h);
        long sum = 0;
        int count = 0;
        for (int row = y; row < y + h; row++) {
            int rowBase = row * width * 3;
            for (int col = x; col < x + w; col++) {
                int idx = rowBase + col * 3;
                int r = rgb[idx] & 0xFF;
                int g = rgb[idx + 1] & 0xFF;
                int b = rgb[idx + 2] & 0xFF;
                sum += Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                count++;
            }
        }
        return count == 0 ? 0.0 : (double) sum / count;
    }

    /**
     * Sharpness/focus measure: variance of the Laplacian of the grayscale region.
     * A well-established, widely cited focus operator (Pech-Pacheco et al., 2000,
     * "Diatom autofocusing in brightfield microscopy: a comparative study").
     * Low variance indicates a blurry region; the exact scale depends on image size
     * and content, so callers compare against an empirically chosen threshold rather
     * than an absolute physical unit.
     */
    public static double laplacianVariance(byte[] rgb, int width, int height, int x, int y, int w, int h) {
        requireRegion(rgb, width, height, x, y, w, h);
        if (w < 3 || h < 3) {
            return 0.0; // too small for a 3x3 Laplacian kernel to mean anything
        }

        double[] gray = toGrayscale(rgb, width, x, y, w, h);
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;

        for (int row = 1; row < h - 1; row++) {
            for (int col = 1; col < w - 1; col++) {
                double center = gray[row * w + col];
                double up = gray[(row - 1) * w + col];
                double down = gray[(row + 1) * w + col];
                double left = gray[row * w + (col - 1)];
                double right = gray[row * w + (col + 1)];
                // Standard 4-neighbour discrete Laplacian kernel: [[0,1,0],[1,-4,1],[0,1,0]]
                double laplacian = up + down + left + right - 4 * center;
                sum += laplacian;
                sumSq += laplacian * laplacian;
                count++;
            }
        }

        if (count == 0) {
            return 0.0;
        }
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    /**
     * Coarse colour-diversity signal: the average of the three RGB channel standard
     * deviations. A flat, low-diversity image (e.g. a uniformly lit print, or a
     * screen with compressed dynamic range) tends to score lower than a natural face
     * region with skin-tone shading and texture. This is a rough heuristic, not a
     * validated presentation-attack feature on its own - it is one of several
     * signals combined by the caller.
     */
    public static double colorDiversity(byte[] rgb, int width, int height, int x, int y, int w, int h) {
        requireRegion(rgb, width, height, x, y, w, h);
        int count = w * h;
        if (count == 0) {
            return 0.0;
        }

        long sumR = 0, sumG = 0, sumB = 0;
        for (int row = y; row < y + h; row++) {
            int rowBase = row * width * 3;
            for (int col = x; col < x + w; col++) {
                int idx = rowBase + col * 3;
                sumR += rgb[idx] & 0xFF;
                sumG += rgb[idx + 1] & 0xFF;
                sumB += rgb[idx + 2] & 0xFF;
            }
        }
        double meanR = (double) sumR / count;
        double meanG = (double) sumG / count;
        double meanB = (double) sumB / count;

        double varR = 0, varG = 0, varB = 0;
        for (int row = y; row < y + h; row++) {
            int rowBase = row * width * 3;
            for (int col = x; col < x + w; col++) {
                int idx = rowBase + col * 3;
                double dr = (rgb[idx] & 0xFF) - meanR;
                double dg = (rgb[idx + 1] & 0xFF) - meanG;
                double db = (rgb[idx + 2] & 0xFF) - meanB;
                varR += dr * dr;
                varG += dg * dg;
                varB += db * db;
            }
        }
        double stdR = Math.sqrt(varR / count);
        double stdG = Math.sqrt(varG / count);
        double stdB = Math.sqrt(varB / count);
        return (stdR + stdG + stdB) / 3.0;
    }

    private static double[] toGrayscale(byte[] rgb, int width, int x, int y, int w, int h) {
        double[] gray = new double[w * h];
        for (int row = 0; row < h; row++) {
            int rowBase = (y + row) * width * 3;
            for (int col = 0; col < w; col++) {
                int idx = rowBase + (x + col) * 3;
                int r = rgb[idx] & 0xFF;
                int g = rgb[idx + 1] & 0xFF;
                int b = rgb[idx + 2] & 0xFF;
                gray[row * w + col] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        return gray;
    }

    private static void requireRegion(byte[] rgb, int width, int height, int x, int y, int w, int h) {
        if (rgb == null || width <= 0 || height <= 0 || rgb.length != width * height * 3) {
            throw new IllegalArgumentException("Invalid RGB888 buffer for its declared width/height");
        }
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > width || y + h > height) {
            throw new IllegalArgumentException("Region out of bounds for the given frame");
        }
    }
}
