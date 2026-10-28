package io.mosip.registration.liveness.core.scenario;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only FrameSource the scenario driver pushes frames through. NOT Part 1's mock device: it has no camera,
 * video or image input; it only lets tests deliver frames and device errors on demand.
 */
public final class ScriptedFrameSource implements FrameSource {
    private static final byte[] PIXELS = new byte[4 * 4 * 3];

    public final AtomicInteger starts = new AtomicInteger();
    public final AtomicInteger stops = new AtomicInteger();
    /** Runs at the end of each stop(), for tests that need something to happen at exactly that point. */
    public volatile Runnable onStop;
    private volatile Listener listener;
    private volatile boolean running;

    @Override
    public DeviceInfo info() {
        return new DeviceInfo("mock:scripted", "test", "scripted", "0.9.5", "L0", 640, 480, 30);
    }

    @Override
    public void start(Listener l) {
        listener = l;
        running = true;
        starts.incrementAndGet();
    }

    @Override
    public void stop() {
        running = false;
        stops.incrementAndGet();
        Runnable hook = onStop;
        if (hook != null) hook.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Delivers a frame if the source is running; returns it (or null if stopped). */
    public Frame push(long timestampMs, long sequence) {
        if (!running || listener == null) return null;
        Frame f = new Frame(PIXELS, 4, 4, timestampMs, sequence, "mock:scripted");
        listener.onFrame(f);
        return f;
    }

    public void error(ErrorCode code, boolean recoverable) {
        if (listener != null) listener.onError(code, "scripted", recoverable);
    }
}
