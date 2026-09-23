package io.mosip.registration.liveness.core.detect;

/** Result of feeding one frame to an {@link ActionDetector}. */
public enum DetectorStatus {
    /** Waiting: before the reaction time, or not yet back at neutral since the prompt. */
    ARMING,
    /** Armed; the action has not (fully) happened yet. */
    IN_PROGRESS,
    /** The requested action was performed (and, for turns/looks, returned to neutral). */
    DETECTED,
    /** A clearly opposite movement: the runner fails the challenge. */
    WRONG_ACTION
}
