package io.mosip.registration.liveness.api;

/**
 * One liveness run for one person (includes its retries). Owner: Person 3 (orchestrator implementation).
 * Lifecycle: start() -> events... -> onComplete(). retry() is only valid after ATTEMPT_FAILED.
 */
public interface LivenessSession {
    void start(FrameSource source, LivenessListener listener);
    void retry();   // user pressed Retry; ignored if no retries left
    void cancel();  // user left the screen; emits onComplete(CANCELLED), stops the source
    String sessionId();
}
