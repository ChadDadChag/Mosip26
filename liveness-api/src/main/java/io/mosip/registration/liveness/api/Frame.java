package io.mosip.registration.liveness.api;

/**
 * One decoded video frame. Owner: Person 1 (device layer).
 * Pixels are always packed RGB888, row-major, no padding (width*height*3 bytes).
 * Decoding (JPEG/MJPEG/YUV) happens inside the FrameSource adapter, never in the engine,
 * so the engine stays pure Java and runs unchanged on Desktop and Android.
 */
public final class Frame {
    private final byte[] rgb;
    private final int width;
    private final int height;
    private final long timestampMs;   // monotonic capture time from the source
    private final long sequence;      // strictly increasing per session; gaps = dropped frames
    private final String deviceId;    // SBI deviceId / serialNo, or "mock:*", "camerax:*"

    public Frame(byte[] rgb, int width, int height, long timestampMs, long sequence, String deviceId) {
        if (rgb == null || width <= 0 || height <= 0 || rgb.length != width * height * 3) {
            throw new IllegalArgumentException("Invalid frame buffer");
        }
        this.rgb = rgb; this.width = width; this.height = height;
        this.timestampMs = timestampMs; this.sequence = sequence; this.deviceId = deviceId;
    }
    public byte[] rgb() { return rgb; }
    public int width() { return width; }
    public int height() { return height; }
    public long timestampMs() { return timestampMs; }
    public long sequence() { return sequence; }
    public String deviceId() { return deviceId; }
}
