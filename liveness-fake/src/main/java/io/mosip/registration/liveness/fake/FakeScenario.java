package io.mosip.registration.liveness.fake;

/**
 * Scripted outcomes for UI development. Select with the builder, the system property
 * {@code liveness.fake.scenario}, or the config key {@code mosip.registration.liveness.fake.scenario}.
 *
 * <p>"then pass on retry" scenarios pass on attempt 2 using the policy's challenge count, so the UI's Retry
 * button path is exercised. Scenarios that keep failing end in FAILED_FINAL once the policy's retries run out.
 */
public enum FakeScenario {
    /** Passive check passes, no challenge. */
    PASS_PASSIVE,
    /** Passive is inconclusive; issues policy.minChallenges challenges (2 resident, 1 operator/supervisor). Default. */
    PASS_ACTIVE,
    /** Exactly one challenge regardless of policy. */
    PASS_AFTER_1_CHALLENGE,
    /** Exactly two challenges regardless of policy (capped at the pool size). */
    PASS_AFTER_2_CHALLENGES,
    /** Attempt 1: wrong action (CHALLENGE_FAILED). Attempt 2: passes with a different sequence. */
    FAIL_CHALLENGE_THEN_PASS_ON_RETRY,
    /** Every attempt: PAD_FAILURE (UI must show only the generic message). Ends in MAX_RETRIES_EXCEEDED. */
    PAD_ATTACK,
    /** Attempt 1: the challenge times out after policy.challengeTimeoutMs. Attempt 2 passes. */
    CHALLENGE_TIMEOUT,
    /** NO_FACE guidance for a few seconds, then the face appears and passive passes. */
    NO_FACE_THEN_PASS,
    /** Attempt 1: MULTIPLE_FACES guidance, then fails with MULTIPLE_FACES. Attempt 2 passes. */
    MULTIPLE_FACES,
    /** IMPROVE_LIGHTING then MOVE_CLOSER guidance, cleared, then active pass. */
    POOR_LIGHTING_HINTS,
    /** Device disconnects mid-challenge (DEVICE_ERROR, recoverable; neither a retry nor an attempt is used). Pass after Retry. */
    DEVICE_DISCONNECT,
    /**
     * The device keeps failing: DEVICE_ERROR(recoverable=true) with Retry each time, until the per-session device
     * error limit (3) is exceeded, then DEVICE_ERROR(recoverable=false) and FAILED_FINAL with the device code.
     */
    DEVICE_FAILURE,
    /** Every attempt fails (rotating causes). Ends in MAX_RETRIES_EXCEEDED; triggers operator cooldown. */
    MAX_RETRIES_EXCEEDED,
    /** Analyzer failure on every attempt: ATTEMPT_FAILED(ENGINE_ERROR) without consuming a retry, then FAILED_FINAL. */
    ENGINE_ERROR,
    /** Picks one of the scenarios above at random for each session. */
    RANDOM;

    public static final String SYSTEM_PROPERTY = "liveness.fake.scenario";
    public static final String CONFIG_KEY = "mosip.registration.liveness.fake.scenario";

    /** Lenient parse: null/blank/unknown returns null. */
    public static FakeScenario parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
