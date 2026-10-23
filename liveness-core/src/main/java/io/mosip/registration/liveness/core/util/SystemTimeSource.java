package io.mosip.registration.liveness.core.util;

/** Production clock backed by {@link System#nanoTime()} (monotonic, unaffected by wall-clock changes). */
public final class SystemTimeSource implements TimeSource {
    public static final SystemTimeSource INSTANCE = new SystemTimeSource();

    private SystemTimeSource() { }

    @Override
    public long nowMs() {
        return System.nanoTime() / 1000000L;
    }
}
