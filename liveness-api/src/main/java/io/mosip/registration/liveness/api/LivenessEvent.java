package io.mosip.registration.liveness.api;

/**
 * Everything the UI needs to render, emitted by the orchestrator (Person 3). UIs (Persons 4, 5) are dumb:
 * they only map events to screens/messages. Delivered on the engine thread: marshal to UI thread yourself
 * (Platform.runLater on JavaFX, EventChannel -> main isolate on Flutter).
 */
public final class LivenessEvent {

    public enum Type {
        STARTED,            // session begun, attemptNumber set
        CHECKING_PASSIVE,   // show "Checking face liveness..."
        GUIDANCE,           // guidance set: positioning/quality hint, repeat-safe (UI should debounce ~500 ms)
        CHALLENGE_PROMPT,   // challenge, challengeIndex, challengeCount, timeoutMs set
        CHALLENGE_PROGRESS, // progress 0..1 for current challenge; show "Please continue"
        ACTION_DETECTED,    // current challenge satisfied
        HOLD_STILL,         // capturing best frame
        ATTEMPT_FAILED,     // error + retriesLeft set; UI shows message and a Retry button
        PASSED,             // "Good, face captured successfully"
        FAILED_FINAL,       // no retries left or non-recoverable; UI shows recovery guidance
        DEVICE_ERROR        // error set; recoverable tells UI whether to show Retry
    }

    public enum Guidance { NO_FACE, MULTIPLE_FACES, MOVE_CLOSER, MOVE_BACK, CENTER_FACE, IMPROVE_LIGHTING,
                           HOLD_STEADY, LOOK_AT_CAMERA }

    public final Type type;
    public final Guidance guidance;
    public final ChallengeType challenge;
    public final int challengeIndex, challengeCount;
    public final long timeoutMs;
    public final float progress;
    public final ErrorCode error;
    public final int attemptNumber, retriesLeft;
    public final boolean recoverable;

    private LivenessEvent(Type type, Guidance guidance, ChallengeType challenge, int challengeIndex,
                          int challengeCount, long timeoutMs, float progress, ErrorCode error,
                          int attemptNumber, int retriesLeft, boolean recoverable) {
        this.type = type; this.guidance = guidance; this.challenge = challenge;
        this.challengeIndex = challengeIndex; this.challengeCount = challengeCount; this.timeoutMs = timeoutMs;
        this.progress = progress; this.error = error; this.attemptNumber = attemptNumber;
        this.retriesLeft = retriesLeft; this.recoverable = recoverable;
    }

    public static LivenessEvent of(Type t, int attempt) {
        return new LivenessEvent(t, null, null, 0, 0, 0, 0f, null, attempt, 0, true);
    }
    public static LivenessEvent guidance(Guidance g, int attempt) {
        return new LivenessEvent(Type.GUIDANCE, g, null, 0, 0, 0, 0f, null, attempt, 0, true);
    }
    public static LivenessEvent prompt(ChallengeType c, int index, int count, long timeoutMs, int attempt) {
        return new LivenessEvent(Type.CHALLENGE_PROMPT, null, c, index, count, timeoutMs, 0f, null, attempt, 0, true);
    }
    public static LivenessEvent progress(ChallengeType c, int index, int count, float p, int attempt) {
        return new LivenessEvent(Type.CHALLENGE_PROGRESS, null, c, index, count, 0, p, null, attempt, 0, true);
    }
    public static LivenessEvent failure(Type t, ErrorCode e, int attempt, int retriesLeft, boolean recoverable) {
        return new LivenessEvent(t, null, null, 0, 0, 0, 0f, e, attempt, retriesLeft, recoverable);
    }
}
