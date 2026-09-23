package io.mosip.registration.liveness.core.statusaudit;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.util.concurrent.atomic.AtomicInteger;

/** Test-only stand-in for Part 1: pushes frames on demand and can raise onError(code, detail, recoverable). */
public final class FakeFrameSource implements FrameSource {
    private static final byte[] PIXELS = new byte[4 * 4 * 3];
    public final AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger(), pushed = new AtomicInteger();
    private volatile Listener listener;
    private volatile boolean running;

    @Override public DeviceInfo info() { return new DeviceInfo("fake-dev", "test", "fake", "0.9.5", "L1", 640, 480, 30); }
    @Override public void start(Listener l) { listener = l; running = true; starts.incrementAndGet(); }
    @Override public void stop() { running = false; stops.incrementAndGet(); }
    @Override public boolean isRunning() { return running; }

    public Frame push(long timestampMs, long sequence) {
        Listener l = listener;
        if (!running || l == null) return null;
        Frame f = new Frame(PIXELS, 4, 4, timestampMs, sequence, "fake-dev");
        pushed.incrementAndGet();
        l.onFrame(f);
        return f;
    }

    public void error(ErrorCode code, String detail, boolean recoverable) {
        Listener l = listener;
        if (l != null) l.onError(code, detail, recoverable);
    }
}
