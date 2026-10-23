package io.mosip.registration.liveness.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.Test;

public class ImageQualityMetricsTest {

    @Test
    public void meanLumaIsLowForBlackAndHighForWhite() {
        byte[] black = solidColor(16, 16, Color.BLACK);
        byte[] white = solidColor(16, 16, Color.WHITE);

        double blackLuma = ImageQualityMetrics.meanLuma(black, 16, 16, 0, 0, 16, 16);
        double whiteLuma = ImageQualityMetrics.meanLuma(white, 16, 16, 0, 0, 16, 16);

        assertTrue("black should have near-zero luma, got " + blackLuma, blackLuma < 5);
        assertTrue("white should have near-255 luma, got " + whiteLuma, whiteLuma > 250);
    }

    @Test
    public void laplacianVarianceIsHigherForSharpCheckerboardThanForFlatColor() {
        byte[] flat = solidColor(32, 32, new Color(128, 128, 128));
        byte[] checkerboard = checkerboard(32, 32, 4);

        double flatVariance = ImageQualityMetrics.laplacianVariance(flat, 32, 32, 0, 0, 32, 32);
        double checkerVariance = ImageQualityMetrics.laplacianVariance(checkerboard, 32, 32, 0, 0, 32, 32);

        assertEquals("a perfectly flat region has zero Laplacian variance", 0.0, flatVariance, 0.0001);
        assertTrue("a high-contrast checkerboard should have far higher variance than flat, got flat="
                        + flatVariance + " checker=" + checkerVariance,
                checkerVariance > flatVariance + 100);
    }

    @Test
    public void laplacianVarianceIsHigherForSharpEdgeThanForBlurredEdge() {
        byte[] sharp = verticalEdge(32, 32, false);
        byte[] blurred = verticalEdge(32, 32, true);

        double sharpVariance = ImageQualityMetrics.laplacianVariance(sharp, 32, 32, 0, 0, 32, 32);
        double blurredVariance = ImageQualityMetrics.laplacianVariance(blurred, 32, 32, 0, 0, 32, 32);

        assertTrue("a hard edge should be measurably sharper than a softened one, sharp="
                        + sharpVariance + " blurred=" + blurredVariance,
                sharpVariance > blurredVariance);
    }

    @Test
    public void colorDiversityIsZeroForUniformColorAndPositiveForRandomNoise() {
        byte[] uniform = solidColor(16, 16, new Color(100, 150, 200));
        byte[] noisy = randomNoise(16, 16, new Random(42));

        double uniformDiversity = ImageQualityMetrics.colorDiversity(uniform, 16, 16, 0, 0, 16, 16);
        double noisyDiversity = ImageQualityMetrics.colorDiversity(noisy, 16, 16, 0, 0, 16, 16);

        assertEquals(0.0, uniformDiversity, 0.0001);
        assertTrue("random noise should show real colour diversity, got " + noisyDiversity, noisyDiversity > 20);
    }

    @Test
    public void supportsASubRegionSmallerThanTheFullFrame() {
        // Left half red, right half blue - the metric on just the left half must
        // reflect only red, not be averaged with the right half.
        BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 8, 8);
        g.setColor(Color.BLUE);
        g.fillRect(8, 0, 8, 8);
        g.dispose();
        byte[] rgb = toRgb888(image);

        double leftLuma = ImageQualityMetrics.meanLuma(rgb, 16, 8, 0, 0, 8, 8);
        double rightLuma = ImageQualityMetrics.meanLuma(rgb, 16, 8, 8, 0, 8, 8);

        // Red and blue have different BT.601 luma weights, so the two halves must differ.
        assertTrue(Math.abs(leftLuma - rightLuma) > 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutOfBoundsRegion() {
        byte[] rgb = solidColor(8, 8, Color.GRAY);
        ImageQualityMetrics.meanLuma(rgb, 8, 8, 4, 4, 8, 8); // extends past the 8x8 frame
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedBufferLength() {
        byte[] tooShort = new byte[10];
        ImageQualityMetrics.meanLuma(tooShort, 8, 8, 0, 0, 8, 8);
    }

    private static byte[] solidColor(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return toRgb888(image);
    }

    private static byte[] checkerboard(int width, int height, int cellSize) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean white = ((x / cellSize) + (y / cellSize)) % 2 == 0;
                image.setRGB(x, y, white ? Color.WHITE.getRGB() : Color.BLACK.getRGB());
            }
        }
        return toRgb888(image);
    }

    private static byte[] verticalEdge(int width, int height, boolean blurred) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int mid = width / 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray;
                if (!blurred) {
                    gray = x < mid ? 0 : 255;
                } else {
                    // Linear ramp across a wide transition band instead of a hard step.
                    int band = width / 2;
                    int distanceIntoBand = x - (mid - band / 2);
                    if (distanceIntoBand < 0) {
                        gray = 0;
                    } else if (distanceIntoBand > band) {
                        gray = 255;
                    } else {
                        gray = (int) (255.0 * distanceIntoBand / band);
                    }
                }
                image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
            }
        }
        return toRgb888(image);
    }

    private static byte[] randomNoise(int width, int height, Random random) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).getRGB());
            }
        }
        return toRgb888(image);
    }

    static byte[] toRgb888(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgb = new byte[width * height * 3];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                rgb[index++] = (byte) ((pixel >> 16) & 0xFF);
                rgb[index++] = (byte) ((pixel >> 8) & 0xFF);
                rgb[index++] = (byte) (pixel & 0xFF);
            }
        }
        return rgb;
    }
}
