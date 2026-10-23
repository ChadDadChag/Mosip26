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
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.util.SystemTimeSource;

import java.util.Collections;
import java.util.UUID;

/**
 * Returned by {@code newSession} while a workflow is in cooldown. The contract has no cooldown error code
 * (proposal P1), so {@link #start} immediately ends the session with FAILED_FINAL(MAX_RETRIES_EXCEEDED,
 * recoverable=false), which the UI already renders as "could not be completed + recovery guidance".
 * It never touches the FrameSource, so the camera is not opened.
 */
public final class CooldownRefusedSession implements LivenessSession {

    /** Reported as {@code modelVersion} when no analyzer was loaded (the camera and model are never opened). */
    public static final String MODEL_UNAVAILABLE = "unavailable";

    private final String sessionId = UUID.randomUUID().toString();
    private final WorkflowType workflow;
    private final long remainingMs;
    private final AuditSink audit;
    private final String modelVersion;
    private final String engine;
    private EventDispatcher dispatcher;

    public CooldownRefusedSession(WorkflowType workflow, long remainingMs, AuditSink audit, String modelVersion,
                                  String engine) {
        this.workflow = workflow; this.remainingMs = remainingMs; this.audit = audit;
        this.modelVersion = modelVersion != null ? modelVersion : MODEL_UNAVAILABLE;
        this.engine = engine;
    }

    @Override
    public synchronized void start(FrameSource source, LivenessListener listener) {
        if (dispatcher != null) throw new IllegalStateException("Session already started");
        dispatcher = new EventDispatcher(listener, "liveness-events-" + sessionId.substring(0, 8));
        new AuditRecorder(audit, workflow, sessionId, modelVersion, SystemTimeSource.INSTANCE, engine)
                .refusedByCooldown(remainingMs);
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
