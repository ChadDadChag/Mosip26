package io.mosip.registration.liveness.device.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

import org.junit.Test;

public class JpegToRgb888ConverterTest {

    @Test
    public void convertsJpegToPackedRgb888() throws Exception {
        // Deliberately tiny (below one JPEG MCU block) - only shape/size is
        // asserted here. See colorValuesAndByteOrderAreCorrectAtRealisticSize()
        // below for pixel-accuracy, which needs a size JPEG can actually encode
        // without heavy quantization loss.
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        image.setRGB(1, 0, Color.BLUE.getRGB());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(true, ImageIO.write(image, "jpg", output));

        JpegToRgb888Converter.DecodedImage decoded =
                JpegToRgb888Converter.convert(output.toByteArray());

        assertNotNull(decoded);
        assertEquals(2, decoded.getWidth());
        assertEquals(1, decoded.getHeight());
        assertEquals(6, decoded.getRgb().length);
    }

    @Test
    public void colorValuesAndByteOrderAreCorrectAtRealisticSize() throws Exception {
        // 16x16 solid block - aligned to JPEG's minimum MCU size, so lossy
        // compression does not meaningfully distort the color. A real face
        // frame is always far larger than this, so this is the realistic case.
        byte[] jpeg = solidColorJpeg(16, 16, new Color(200, 30, 40));

        JpegToRgb888Converter.DecodedImage decoded = JpegToRgb888Converter.convert(jpeg);
        byte[] rgb = decoded.getRgb();

        int r = rgb[0] & 0xFF;
        int g = rgb[1] & 0xFF;
        int b = rgb[2] & 0xFF;

        assertTrue("R channel should decode close to 200, got " + r, Math.abs(r - 200) < 12);
        assertTrue("G channel should decode close to 30, got " + g, Math.abs(g - 30) < 12);
        assertTrue("B channel should decode close to 40, got " + b, Math.abs(b - 40) < 12);
    }

    @Test
    public void rowMajorOrderIsPreserved() throws Exception {
        // Left half red, right half green - a byte-order or row/column swap
        // would make the second pixel look wrong relative to the first.
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 8, 16);
        g.setColor(Color.GREEN);
        g.fillRect(8, 0, 8, 16);
        g.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        JpegToRgb888Converter.DecodedImage decoded = JpegToRgb888Converter.convert(output.toByteArray());
        byte[] rgb = decoded.getRgb();

        int firstPixelR = rgb[0] & 0xFF;
        int lastPixelOfFirstRowG = rgb[(15) * 3 + 1] & 0xFF; // pixel x=15,y=0 -> green
        assertTrue("first pixel (x=0) should be predominantly red", firstPixelR > 150);
        assertTrue("last pixel of first row (x=15) should be predominantly green", lastPixelOfFirstRowG > 150);
    }

    @Test
    public void grayscaleJpegDecodesWithEqualChannels() throws Exception {
        BufferedImage gray = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = gray.getGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, 16, 16);
        g.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(gray, "jpg", output);
        JpegToRgb888Converter.DecodedImage decoded = JpegToRgb888Converter.convert(output.toByteArray());

        byte[] rgb = decoded.getRgb();
        int r = rgb[0] & 0xFF, gr = rgb[1] & 0xFF, b = rgb[2] & 0xFF;
        assertTrue("grayscale R/G/B should be equal (within JPEG rounding)",
                Math.abs(r - gr) < 8 && Math.abs(gr - b) < 8);
    }

    @Test
    public void differentDimensionsProduceCorrectLength() throws Exception {
        BufferedImage image = new BufferedImage(37, 23, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        JpegToRgb888Converter.DecodedImage decoded = JpegToRgb888Converter.convert(output.toByteArray());

        assertEquals(37, decoded.getWidth());
        assertEquals(23, decoded.getHeight());
        assertEquals(37 * 23 * 3, decoded.getRgb().length);
    }

    @Test(expected = IOException.class)
    public void corruptJpegThrowsIOException() throws Exception {
        byte[] corrupt = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        JpegToRgb888Converter.convert(corrupt);
    }

    @Test(expected = IOException.class)
    public void emptyInputThrowsIOException() throws Exception {
        JpegToRgb888Converter.convert(new byte[0]);
    }

    @Test
    public void nullInputThrowsIOExceptionNotNullPointerException() {
        try {
            JpegToRgb888Converter.convert(null);
            fail("expected IOException for null input");
        } catch (IOException expected) {
            // correct - documented contract is IOException, not NullPointerException
        }
    }

    @Test
    public void truncatedJpegDoesNotThrowUncheckedException() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        byte[] full = output.toByteArray();
        byte[] truncated = java.util.Arrays.copyOf(full, full.length / 2);

        // ImageIO is lenient about some truncation points and may decode a
        // partial image rather than throw; either outcome is acceptable as
        // long as no unchecked exception escapes the documented IOException
        // contract (see JpegToRgb888Converter's RuntimeException-wrapping).
        try {
            JpegToRgb888Converter.convert(truncated);
        } catch (IOException expected) {
            // also acceptable
        }
    }

    private static byte[] solidColorJpeg(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
