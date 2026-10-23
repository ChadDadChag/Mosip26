package io.mosip.registration.liveness.device.util;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PreviewFrameSourceTest {

    /** Minimal hand-driven device. */
    static final class Device implements FrameSource {
        Listener listener;
        boolean running;
        int stops;

        @Override public DeviceInfo info() { return new DeviceInfo("dev", "v", "m", "1", "L0", 4, 4, 30); }
        @Override public void start(Listener l) { listener = l; running = true; }
        @Override public void stop() { running = false; stops++; }
        @Override public boolean isRunning() { return running; }
    }

    @Test
    public void everyFrameReachesTheEngineAndEveryPreviewAndErrorsOnlyTheEngine() {
        Device device = new Device();
        PreviewFrameSource tee = new PreviewFrameSource(device);
        final List<Frame> preview1 = new ArrayList<>(), preview2 = new ArrayList<>(), engine = new ArrayList<>();
        final List<ErrorCode> errors = new ArrayList<>();
        tee.addPreviewListener(preview1::add);
        tee.addPreviewListener(f -> { throw new IllegalStateException("broken preview"); });
        tee.addPreviewListener(preview2::add);
        tee.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame f) { engine.add(f); }
            @Override public void onError(ErrorCode c, String d, boolean r) { errors.add(c); }
        });
        assertTrue(tee.isRunning());
        assertSame(device.info().deviceId, tee.info().deviceId);
        Frame f = new Frame(new byte[4 * 4 * 3], 4, 4, 1, 1, "dev");
        device.listener.onFrame(f);
        device.listener.onError(ErrorCode.DEVICE_DISCONNECTED, "usb", true);
        assertEquals(1, engine.size());
        assertEquals(1, preview1.size());
        assertEquals("a broken preview does not stop later listeners", 1, preview2.size());
        assertEquals(1, errors.size());
        tee.stop();
        assertFalse(tee.isRunning());
        assertEquals(1, device.stops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void needsADevice() {
        new PreviewFrameSource(null);
    }
}
