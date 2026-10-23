package io.mosip.registration.liveness.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a face device can produce: stream formats, resolutions and frame rates.
 * Empty lists mean "not known", never "not supported".
 *
 * <p>{@link Origin} says where the values came from. SBI/MDS metadata does not
 * describe resolution or FPS, so for SBI devices these are OBSERVED from the live
 * stream (see {@code CapabilityProbe}); mock and webcam sources DECLARE them.</p>
 */
public final class DeviceCapabilities {

    public enum Origin { DECLARED, OBSERVED, UNKNOWN }

    private static final DeviceCapabilities UNKNOWN = new DeviceCapabilities(
            Collections.<String>emptyList(), Collections.<Resolution>emptyList(),
            Collections.<Integer>emptyList(), Origin.UNKNOWN);

    /** Device-side encodings, e.g. "MJPEG", "JPEG", "PNG", "YUV_420_888". */
    public final List<String> streamFormats;
    public final List<Resolution> resolutions;
    /** Whole frames per second. */
    public final List<Integer> frameRates;
    public final Origin origin;

    public DeviceCapabilities(List<String> streamFormats, List<Resolution> resolutions,
                              List<Integer> frameRates, Origin origin) {
        this.streamFormats = immutableCopy(streamFormats);
        this.resolutions = immutableCopy(resolutions);
        this.frameRates = immutableCopy(frameRates);
        this.origin = origin == null ? Origin.UNKNOWN : origin;
    }

    public static DeviceCapabilities unknown() {
        return UNKNOWN;
    }

    public Resolution largestResolution() {
        Resolution best = null;
        for (Resolution r : resolutions) {
            if (best == null || (long) r.width * r.height > (long) best.width * best.height) {
                best = r;
            }
        }
        return best;
    }

    public int maxFrameRate() {
        int max = 0;
        for (Integer fps : frameRates) {
            max = Math.max(max, fps);
        }
        return max;
    }

    @Override
    public String toString() {
        return "DeviceCapabilities{formats=" + streamFormats + ", resolutions=" + resolutions
                + ", fps=" + frameRates + ", origin=" + origin + '}';
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Resolution {
        public final int width;
        public final int height;

        public Resolution(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid resolution " + width + "x" + height);
            }
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Resolution && ((Resolution) o).width == width && ((Resolution) o).height == height;
        }

        @Override
        public int hashCode() {
            return 31 * width + height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }
}
