package io.mosip.registration.liveness.api;

/**
 * One decoded video frame. Owner: Person 1 (device layer).
 * Pixels are packed, row-major, no padding (width*height*format.bytesPerPixel bytes).
 * Decoding (JPEG/MJPEG/YUV) happens inside the FrameSource adapter, never in the engine,
 * so the engine stays pure Java and runs unchanged on Desktop and Android.
 * Pixels are in the sensor's view: not mirrored, rotated upright where the source knows the rotation.
 */
public final class Frame {

    /** Layout of {@link #pixels()}. All current adapters produce RGB888; GRAY8 is for mono/NIR face cameras. */
    public enum PixelFormat {
        RGB888(3), GRAY8(1);

        public final int bytesPerPixel;

        PixelFormat(int bytesPerPixel) {
            this.bytesPerPixel = bytesPerPixel;
        }
    }

    private final byte[] pixels;
    private final PixelFormat format;
    private final int width;
    private final int height;
    private final long timestampMs;   // monotonic capture time from the source
    private final long sequence;      // strictly increasing per session; gaps = dropped/skipped frames
    private final String deviceId;    // SBI deviceId / serialNo, or "mock:*", "android-camera:*"
    private volatile byte[] rgbView;  // lazily expanded RGB888 copy for non-RGB formats

    /** RGB888 frame (the format every current adapter produces). */
    public Frame(byte[] rgb, int width, int height, long timestampMs, long sequence, String deviceId) {
        this(rgb, PixelFormat.RGB888, width, height, timestampMs, sequence, deviceId);
    }

    public Frame(byte[] pixels, PixelFormat format, int width, int height,
                 long timestampMs, long sequence, String deviceId) {
        if (pixels == null || format == null || width <= 0 || height <= 0
                || (long) width * height * format.bytesPerPixel != pixels.length) {
            throw new IllegalArgumentException("Invalid frame buffer");
        }
        this.pixels = pixels; this.format = format; this.width = width; this.height = height;
        this.timestampMs = timestampMs; this.sequence = sequence; this.deviceId = deviceId;
    }

    /** Raw buffer in {@link #format()}. */
    public byte[] pixels() { return pixels; }
    public PixelFormat format() { return format; }

    /** Always RGB888: the buffer itself for RGB888 frames, an expanded copy (computed once) otherwise. */
    public byte[] rgb() {
        if (format == PixelFormat.RGB888) {
            return pixels;
        }
        byte[] view = rgbView;
        if (view == null) {
            view = new byte[pixels.length * 3];
            for (int i = 0, o = 0; i < pixels.length; i++) {
                view[o++] = pixels[i]; view[o++] = pixels[i]; view[o++] = pixels[i];
            }
            rgbView = view;
        }
        return view;
    }

    public int width() { return width; }
    public int height() { return height; }
    public long timestampMs() { return timestampMs; }
    public long sequence() { return sequence; }
    public String deviceId() { return deviceId; }
}
