package io.mosip.registration.liveness.core.util;

/**
 * Monotonic millisecond clock. Injected everywhere a timeout or duration is computed so that tests can
 * drive time explicitly instead of sleeping.
 */
public interface TimeSource {
    /** Monotonic time in milliseconds. Only differences are meaningful. */
    long nowMs();
}
