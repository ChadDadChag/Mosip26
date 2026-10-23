package io.mosip.registration.liveness.engine;

/**
 * Coarse, classical (non-ML) face-region locator, used only as a working baseline
 * inside {@link HeuristicFaceAnalyzer} until a real detector model is wired into the
 * {@code FaceAnalyzer} implementation. It works in two well-established steps:
 *
 * <ol>
 *   <li>Classify each pixel as "skin-like" using the YCbCr skin-colour rule from
 *       Chai &amp; Ngan, "Face Segmentation Using Skin-Color Map in Videophone
 *       Applications" (IEEE Trans. Circuits Syst. Video Technol., 1999):
 *       {@code 77 <= Cb <= 127} and {@code 133 <= Cr <= 173}.</li>
 *   <li>Find the bounding box of the densest contiguous block of skin pixels using
 *       row/column projection profiling (summing skin pixels per row and per
 *       column, then taking the contiguous span above a density threshold in each
 *       axis). This is a standard, cheap alternative to full connected-component
 *       labelling when only one dominant region is expected.</li>
 * </ol>
 *
 * <p><b>Known limitations (documented deliberately, not silently, and confirmed by
 * direct testing rather than assumed):</b> this detector has no notion of
 * "face-shaped" beyond skin colour plus a plausible aspect ratio and size. Verified
 * false-positive example: a flat, wood-brown surface (RGB 150,100,60) filling an
 * entire synthetic frame is classified as a single "face" covering the whole frame.
 * A hand or other skin-toned object is misclassified the same way. This is a
 * well-known limitation of pure chrominance-based skin classifiers in general
 * (brown/tan wood and leather commonly overlap real skin tones in YCbCr space);
 * narrowing the Cb/Cr range to exclude this one synthetic case would risk rejecting
 * real, diverse skin tones and was deliberately not done - see
 * {@code RealismProbe} findings referenced in the Part 2 delivery notes. In
 * practice this class is never used on its own: {@link HeuristicFaceAnalyzer}'s
 * downstream quality checks (a flat wood surface has zero Laplacian variance, so it
 * is always also flagged {@code BLURRY} with {@code livenessScore} 0) keep a
 * false-positive "face" like this from ever being treated as usable or live.</p>
 *
 * <p>This detector also cannot reliably distinguish two separate faces from one
 * large connected skin region, so {@code faceCount} here never reports more than 1
 * - "multiple faces" detection is out of scope for this heuristic and needs a real
 * face detector to implement correctly. It is provided so the {@code FaceAnalyzer}
 * contract is fully exercisable end-to-end without a bundled ML model; production
 * accuracy requires replacing it with a trained detector.</p>
 */
final class FaceRegionDetector {

    /** Minimum fraction of frame area a candidate face box must cover to be accepted. */
    private static final double MIN_AREA_FRACTION = 0.02;
    /** Minimum fraction of pixels in a row/column that must be skin-like to count that row/column "in". */
    private static final double PROJECTION_DENSITY_THRESHOLD = 0.15;

    private FaceRegionDetector() {
    }

    /** Result of a detection attempt. {@code found == false} means no usable region was located. */
    static final class Detection {
        final boolean found;
        final int x, y, w, h;

        private Detection(boolean found, int x, int y, int w, int h) {
            this.found = found;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        static Detection notFound() {
            return new Detection(false, 0, 0, 0, 0);
        }

        static Detection of(int x, int y, int w, int h) {
            return new Detection(true, x, y, w, h);
        }
    }

    static Detection detect(byte[] rgb, int width, int height) {
        boolean[] skin = classifySkin(rgb, width, height);

        int[] rowCounts = new int[height];
        int[] colCounts = new int[width];
        for (int y = 0; y < height; y++) {
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                if (skin[rowBase + x]) {
                    rowCounts[y]++;
                    colCounts[x]++;
                }
            }
        }

        int[] rowSpan = densestSpan(rowCounts, width, PROJECTION_DENSITY_THRESHOLD);
        int[] colSpan = densestSpan(colCounts, height, PROJECTION_DENSITY_THRESHOLD);

        if (rowSpan == null || colSpan == null) {
            return Detection.notFound();
        }

        int y = rowSpan[0];
        int h = rowSpan[1] - rowSpan[0] + 1;
        int x = colSpan[0];
        int w = colSpan[1] - colSpan[0] + 1;

        double areaFraction = (double) (w * h) / (double) (width * height);
        if (areaFraction < MIN_AREA_FRACTION) {
            return Detection.notFound();
        }

        return Detection.of(x, y, w, h);
    }

    /** YCbCr skin-colour classification per Chai & Ngan (1999). */
    private static boolean[] classifySkin(byte[] rgb, int width, int height) {
        boolean[] skin = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int rowBase = y * width * 3;
            for (int x = 0; x < width; x++) {
                int idx = rowBase + x * 3;
                int r = rgb[idx] & 0xFF;
                int g = rgb[idx + 1] & 0xFF;
                int b = rgb[idx + 2] & 0xFF;

                // BT.601 full-range RGB -> YCbCr
                double cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                double cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;

                boolean isSkin = cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173;
                skin[y * width + x] = isSkin;
            }
        }
        return skin;
    }

    /**
     * Finds the widest contiguous run of indices whose count exceeds
     * {@code threshold * lineLength}, i.e. where that row/column is "dense enough"
     * with skin pixels. Returns {@code [firstIndex, lastIndex]} inclusive, or
     * {@code null} if no index clears the density threshold.
     */
    private static int[] densestSpan(int[] counts, int lineLength, double threshold) {
        int minCount = (int) Math.ceil(threshold * lineLength);
        int bestStart = -1, bestEnd = -1, bestLength = 0;
        int currentStart = -1;

        for (int i = 0; i < counts.length; i++) {
            boolean dense = counts[i] >= minCount;
            if (dense && currentStart == -1) {
                currentStart = i;
            } else if (!dense && currentStart != -1) {
                int length = i - currentStart;
                if (length > bestLength) {
                    bestLength = length;
                    bestStart = currentStart;
                    bestEnd = i - 1;
                }
                currentStart = -1;
            }
        }
        if (currentStart != -1) {
            int length = counts.length - currentStart;
            if (length > bestLength) {
                bestLength = length;
                bestStart = currentStart;
                bestEnd = counts.length - 1;
            }
        }

        return bestStart == -1 ? null : new int[]{bestStart, bestEnd};
    }
}
