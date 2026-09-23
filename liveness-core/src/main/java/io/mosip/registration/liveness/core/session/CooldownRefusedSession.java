package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Returned by {@code newSession} while a workflow is in cooldown. The contract has no cooldown error code
 * (proposal P2), so {@link #start} immediately ends the session with FAILED_FINAL(MAX_RETRIES_EXCEEDED,
 * recoverable=false), which the UI already renders as "could not be completed + recovery guidance".
 * It never touches the FrameSource, so the camera is not opened.
 */
public final class CooldownRefusedSession implements LivenessSession {

    private final String sessionId = UUID.randomUUID().toString();
    private final WorkflowType workflow;
    private final long remainingMs;
    private final AuditSink audit;
    private final String modelVersion;
    private EventDispatcher dispatcher;

    public CooldownRefusedSession(WorkflowType workflow, long remainingMs, AuditSink audit, String modelVersion) {
        this.workflow = workflow; this.remainingMs = remainingMs; this.audit = audit; this.modelVersion = modelVersion;
    }

    @Override
    public synchronized void start(FrameSource source, LivenessListener listener) {
        if (dispatcher != null) throw new IllegalStateException("Session already started");
        dispatcher = new EventDispatcher(listener, "liveness-events-" + sessionId.substring(0, 8));
        if (audit != null) {
            Map<String, String> d = new HashMap<String, String>();
            d.put("errorCode", ErrorCode.MAX_RETRIES_EXCEEDED.name());
            d.put("reason", "cooldown");
            d.put("cooldownRemainingMs", Long.toString(remainingMs));
            try {
                audit.record(AuditSink.Event.LIVENESS_FAILED_FINAL, workflow, sessionId, d);
            } catch (RuntimeException ignored) {
                // Audit failures must not change the liveness outcome.
            }
        }
        dispatcher.emit(LivenessEvent.failure(LivenessEvent.Type.FAILED_FINAL, ErrorCode.MAX_RETRIES_EXCEEDED, 0, 0, false));
        dispatcher.complete(new LivenessResult(LivenessResult.Decision.FAILED, LivenessResult.PassPath.NONE,
                ErrorCode.MAX_RETRIES_EXCEEDED, null, Float.NaN, Collections.<ChallengeType>emptyList(), 0, 0,
                sessionId, modelVersion));
    }

    @Override
    public void retry() {
        // Never retryable.
    }

    @Override
    public void cancel() {
        // Already final at start(); nothing to cancel.
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    public long cooldownRemainingMs() {
        return remainingMs;
    }
}
