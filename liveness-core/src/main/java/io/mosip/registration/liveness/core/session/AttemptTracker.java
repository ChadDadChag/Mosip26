package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ErrorCode;

/**
 * Retry accounting for one session. Pure logic, not thread-safe (owned by the session worker).
 *
 * <p>Rules (see docs/person3-design.md, section 4):
 * <ul>
 *   <li>{@code attempts = 1 + maxRetries} for failures the user can influence (PAD, challenge, quality, ...).</li>
 *   <li>Device errors never consume a retry.</li>
 *   <li>ENGINE_ERROR does not consume a retry, but at most {@link #MAX_ENGINE_ERROR_ATTEMPTS} attempts may end in
 *       it; the next one ends the session with ENGINE_ERROR.</li>
 *   <li>MODEL_INTEGRITY_FAILED and CANCELLED end the session immediately.</li>
 *   <li>A consuming failure with no retries left ends the session with MAX_RETRIES_EXCEEDED; the underlying
 *       cause is kept in {@link #lastCause()} for audit.</li>
 * </ul>
 * The retry is consumed when {@link #beginNextAttempt()} is called (i.e. when the user presses Retry), so the
 * {@code retriesLeft} reported with ATTEMPT_FAILED is the number of Retry presses still allowed.
 */
public final class AttemptTracker {

    // TUNE: engine errors are not the user's fault, but an analyzer that keeps failing must not loop forever.
    public static final int MAX_ENGINE_ERROR_ATTEMPTS = 2;

    /** What happens after a failed attempt. */
    public static final class Decision {
        public final boolean isFinal;
        /** The code to report: the cause itself for a retryable failure, the session-ending code if final. */
        public final ErrorCode code;
        public final int retriesLeft;

        private Decision(boolean isFinal, ErrorCode code, int retriesLeft) {
            this.isFinal = isFinal; this.code = code; this.retriesLeft = retriesLeft;
        }
    }

    private final int maxRetries;
    private int attemptNumber = 1;
    private int retriesUsed;
    private int engineErrorAttempts;
    private boolean pendingConsume;
    private ErrorCode lastCause;

    public AttemptTracker(int maxRetries) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries < 0");
        this.maxRetries = maxRetries;
    }

    public int attemptNumber() { return attemptNumber; }

    public int retriesLeft() { return maxRetries - retriesUsed; }

    /** The most recent attempt-level failure cause (kept for audit when the final code is MAX_RETRIES_EXCEEDED). */
    public ErrorCode lastCause() { return lastCause; }

    public Decision onAttemptFailed(ErrorCode cause) {
        if (cause == null) throw new IllegalArgumentException("cause must not be null");
        lastCause = cause;
        if (cause == ErrorCode.MODEL_INTEGRITY_FAILED || cause == ErrorCode.CANCELLED) {
            return new Decision(true, cause, 0);
        }
        if (isDeviceError(cause)) {
            pendingConsume = false;
            return new Decision(false, cause, retriesLeft());
        }
        if (cause == ErrorCode.ENGINE_ERROR) {
            engineErrorAttempts++;
            if (engineErrorAttempts >= MAX_ENGINE_ERROR_ATTEMPTS) return new Decision(true, cause, 0);
            pendingConsume = false;
            return new Decision(false, cause, retriesLeft());
        }
        if (retriesLeft() <= 0) return new Decision(true, ErrorCode.MAX_RETRIES_EXCEEDED, 0);
        pendingConsume = true;
        return new Decision(false, cause, retriesLeft());
    }

    /** Called when the user presses Retry after a non-final failure. */
    public void beginNextAttempt() {
        if (pendingConsume) retriesUsed++;
        pendingConsume = false;
        attemptNumber++;
    }

    public static boolean isDeviceError(ErrorCode code) {
        return code == ErrorCode.DEVICE_UNAVAILABLE || code == ErrorCode.DEVICE_CONNECTION_FAILED
                || code == ErrorCode.DEVICE_DISCONNECTED || code == ErrorCode.CAMERA_UNAVAILABLE;
    }
}
