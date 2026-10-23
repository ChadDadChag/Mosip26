package io.mosip.registration.liveness.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class FrameTest {

    @Test
    public void legacyConstructorIsRgb888AndRgbReturnsTheSameBuffer() {
        byte[] rgb = new byte[2 * 1 * 3];
        Frame frame = new Frame(rgb, 2, 1, 5L, 7L, "dev");
        assertEquals(Frame.PixelFormat.RGB888, frame.format());
        assertSame(rgb, frame.pixels());
        assertSame(rgb, frame.rgb());
        assertEquals(7L, frame.sequence());
        assertEquals("dev", frame.deviceId());
    }

    @Test
    public void gray8IsExpandedToRgbOnceOnDemand() {
        byte[] gray = {10, (byte) 200};
        Frame frame = new Frame(gray, Frame.PixelFormat.GRAY8, 2, 1, 0L, 0L, "nir");
        assertArrayEquals(new byte[]{10, 10, 10, (byte) 200, (byte) 200, (byte) 200}, frame.rgb());
        assertSame("expanded view is cached", frame.rgb(), frame.rgb());
        assertSame(gray, frame.pixels());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBufferThatDoesNotMatchTheFormat() {
        new Frame(new byte[4 * 3], Frame.PixelFormat.GRAY8, 2, 2, 0L, 0L, "dev");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingFormat() {
        new Frame(new byte[3], null, 1, 1, 0L, 0L, "dev");
    }

    @Test
    public void deviceInfoDerivesMaximaFromCapabilities() {
        DeviceCapabilities caps = new DeviceCapabilities(
                java.util.Arrays.asList("MJPEG"),
                java.util.Arrays.asList(new DeviceCapabilities.Resolution(640, 480),
                        new DeviceCapabilities.Resolution(1280, 720)),
                java.util.Arrays.asList(15, 30), DeviceCapabilities.Origin.DECLARED);
        FrameSource.DeviceInfo info = new FrameSource.DeviceInfo("d", "v", "m", "1.0", "L1", 0, 0, 0)
                .withCapabilities(caps);
        assertEquals(1280, info.maxWidth);
        assertEquals(720, info.maxHeight);
        assertEquals(30, info.maxFps);
        assertEquals("L1", info.certificationLevel);
        assertSame(caps, info.capabilities);
        assertEquals(DeviceCapabilities.Origin.UNKNOWN,
                new FrameSource.DeviceInfo("d", "v", "m", "1.0", "L1", 0, 0, 0).capabilities.origin);
    }
}
