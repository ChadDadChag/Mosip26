package io.mosip.registration.liveness.api;

public interface LivenessListener {
    void onEvent(LivenessEvent event);
    /** Called exactly once per session. */
    void onComplete(LivenessResult result);
}
