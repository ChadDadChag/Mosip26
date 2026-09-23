package io.mosip.registration.liveness.api;

/**
 * Entry point used by the apps. Desktop (Person 4) and Android (Person 5) only ever touch this,
 * FrameSource, LivenessListener and AuditSink. A FakeLivenessService (scripted events) exists for UI work.
 */
public interface LivenessService {
    /** Returns null when liveness is disabled for this workflow; the caller then uses the legacy flow. */
    LivenessSession newSession(WorkflowType workflow);
    LivenessPolicy policy(WorkflowType workflow);
    /** Re-read config (called after a config sync). Running sessions keep their old policy. */
    void reloadConfig(ConfigProvider config);
}
