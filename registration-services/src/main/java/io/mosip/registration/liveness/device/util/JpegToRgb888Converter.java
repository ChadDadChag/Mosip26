package io.mosip.registration.liveness.device.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Converts an encoded JPEG frame into the RGB888 representation required by Frame. */
public final class JpegToRgb888Converter {

    private JpegToRgb888Converter() {
    }

    public static DecodedImage convert(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length == 0) {
            throw new IOException("Empty JPEG frame");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (RuntimeException decodeFailure) {
            // Some corrupt JPEGs (e.g. malformed embedded color profiles) make the
            // JDK codec throw an unchecked exception instead of returning null.
            // Normalize to the documented IOException contract either way.
            throw new IOException("Corrupt JPEG frame", decodeFailure);
        }
        if (image == null) {
            throw new IOException("Unsupported or corrupt JPEG frame");
        }

        return new DecodedImage(toRgb888(image), image.getWidth(), image.getHeight());
    }

    /**
     * Walks a decoded image into packed RGB888 (row-major, no padding). Shared with
     * MockFrameSource so both device paths produce Frame buffers the same way.
     */
    public static byte[] toRgb888(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgb = new byte[width * height * 3];

        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                rgb[index++] = (byte) ((pixel >> 16) & 0xFF); // R
                rgb[index++] = (byte) ((pixel >> 8) & 0xFF);  // G
                rgb[index++] = (byte) (pixel & 0xFF);         // B
            }
        }
        return rgb;
    }

    public static final class DecodedImage {
        private final byte[] rgb;
        private final int width;
        private final int height;

        public DecodedImage(byte[] rgb, int width, int height) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
        }

        public byte[] getRgb() {
            return rgb;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }
}
