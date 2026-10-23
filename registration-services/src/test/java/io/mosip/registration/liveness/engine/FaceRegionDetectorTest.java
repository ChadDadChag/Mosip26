package io.mosip.registration.liveness.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

import org.junit.Test;

public class FaceRegionDetectorTest {

    /** A representative mid-tone skin colour that falls inside the Chai & Ngan YCbCr skin range. */
    private static final Color SKIN_COLOR = new Color(200, 150, 120);

    @Test
    public void findsACentredSkinColoredBlock() {
        int width = 64, height = 64;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(SKIN_COLOR);
        g.fillRect(16, 16, 32, 32); // centred 32x32 "face" block
        g.dispose();

        FaceRegionDetector.Detection detection = FaceRegionDetector.detect(toRgb888(image), width, height);

        assertTrue("expected a face region to be found", detection.found);
        // The projection-profile approach should land close to the actual block,
        // though it is not pixel-exact by design (see class Javadoc).
        assertTrue("x should be roughly in the 8..24 range, was " + detection.x, detection.x >= 8 && detection.x <= 24);
        assertTrue("w should be roughly in the 24..48 range, was " + detection.w, detection.w >= 24 && detection.w <= 48);
    }

    @Test
    public void reportsNotFoundWhenThereIsNoSkinColor() {
        int width = 32, height = 32;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.BLUE); // clearly outside the skin YCbCr range
        g.fillRect(0, 0, width, height);
        g.dispose();

        FaceRegionDetector.Detection detection = FaceRegionDetector.detect(toRgb888(image), width, height);

        assertFalse("a uniformly blue frame should not be classified as a face", detection.found);
    }

    @Test
    public void reportsNotFoundWhenTheSkinColoredAreaIsTooSmall() {
        int width = 100, height = 100;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(SKIN_COLOR);
        g.fillRect(0, 0, 3, 3); // far below the minimum area fraction
        g.dispose();

        FaceRegionDetector.Detection detection = FaceRegionDetector.detect(toRgb888(image), width, height);

        assertFalse("a tiny skin-coloured speck should not be treated as a face", detection.found);
    }

    @Test
    public void locatesAnOffCenterSkinBlockAtItsActualPosition() {
        int width = 64, height = 64;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(SKIN_COLOR);
        g.fillRect(2, 2, 24, 24); // near the top-left corner, not centred
        g.dispose();

        FaceRegionDetector.Detection detection = FaceRegionDetector.detect(toRgb888(image), width, height);

        assertTrue(detection.found);
        assertTrue("detected box should be near the top-left, x=" + detection.x, detection.x < 16);
        assertTrue("detected box should be near the top-left, y=" + detection.y, detection.y < 16);
    }

    private static byte[] toRgb888(BufferedImage image) {
        return ImageQualityMetricsTest.toRgb888(image);
    }
}
