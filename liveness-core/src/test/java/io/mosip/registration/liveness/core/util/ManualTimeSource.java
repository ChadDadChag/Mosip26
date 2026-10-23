package io.mosip.registration.liveness.core.util;

/** Test clock: time only moves when the test says so. */
public final class ManualTimeSource implements TimeSource {
    private long now;

    public ManualTimeSource(long start) {
        this.now = start;
    }

    @Override
    public synchronized long nowMs() {
        return now;
    }

    public synchronized void advance(long ms) {
        now += ms;
    }
}
