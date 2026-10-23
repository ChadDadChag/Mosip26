package io.mosip.registration.liveness.fake;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.util.concurrent.atomic.AtomicInteger;

/** Minimal FrameSource the test drives by hand. */
class TestFrameSource implements FrameSource {
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();
    volatile Listener listener;
    volatile boolean running;

    @Override
    public DeviceInfo info() {
        return new DeviceInfo("mock:test", "test", "test", "0.9.5", "NONE", 640, 480, 30);
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
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    Frame push(long seq) {
        Frame f = new Frame(new byte[4 * 4 * 3], 4, 4, seq * 33, seq, "mock:test");
        listener.onFrame(f);
        return f;
    }

    void fail(ErrorCode code, boolean recoverable) {
        listener.onError(code, "test", recoverable);
    }
}
