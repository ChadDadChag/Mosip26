package io.mosip.registration.liveness.api;

import java.util.Map;

/**
 * Where the engine writes audit/diagnostic records. Desktop: wraps the Registration Client AuditManager.
 * Android: wraps the Android client's audit table. NEVER receives images or landmarks: only codes,
 * scores, model version, timings, challenge types, device id.
 */
public interface AuditSink {
    enum Event { LIVENESS_STARTED, LIVENESS_PASSED, LIVENESS_ATTEMPT_FAILED, LIVENESS_FAILED_FINAL,
                 PAD_ATTACK_SUSPECTED, DEVICE_ERROR, MODEL_LOADED, MODEL_INTEGRITY_FAILED, CONFIG_LOADED }

    void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details);
}
