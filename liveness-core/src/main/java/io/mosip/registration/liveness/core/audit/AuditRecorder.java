package io.mosip.registration.liveness.core.audit;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds audit records for one session and writes them to the host {@link AuditSink}.
 *
 * <p>Privacy by construction: no method accepts a Frame, FrameAnalysis, FaceLandmarks, pixel buffer or
 * coordinate array (enforced by AuditRecorderTest). Records contain only codes, enums, scores, counts, timings,
 * challenge types, model version and device id/certification. Free-text from devices is truncated.
 *
 * <p>Audit is best effort: a failing sink never changes the liveness outcome. Thread-safe: the worker records
 * most events, but cancel() can record from any thread.
 */
public final class AuditRecorder {

    /** Detail keys. Stable names: Parts 4/5 map them into their audit tables. */
    public static final class Keys {
        public static final String ATTEMPT = "attempt";
        public static final String MODEL_VERSION = "modelVersion";
        public static final String DEVICE_ID = "deviceId";
        public static final String DEVICE_CERTIFICATION = "deviceCertification";
        public static final String ERROR_CODE = "errorCode";
        public static final String LAST_CAUSE = "lastCause";
        public static final String REASON = "reason";
        public static final String RETRIES_LEFT = "retriesLeft";
        public static final String RECOVERABLE = "recoverable";
        public static final String DEVICE_DETAIL = "deviceDetail";
        public static final String PASS_PATH = "passPath";
        public static final String PASSIVE_OUTCOME = "passiveOutcome";
        public static final String PASSIVE_SCORE = "passiveScore";
        public static final String PASSIVE_FRAMES = "passiveFrames";
        public static final String PASSIVE_MS = "passiveMs";
        public static final String CHALLENGES = "challenges";
        public static final String ATTEMPT_MS = "attemptMs";
        public static final String DURATION_MS = "durationMs";
        public static final String FRAMES_ANALYZED = "framesAnalyzed";
        public static final String FRAMES_DROPPED = "framesDropped";
        public static final String AVG_PROCESSING_MS = "avgProcessingMs";
        public static final String COOLDOWN_SEC = "cooldownSec";
        public static final String COOLDOWN_REMAINING_MS = "cooldownRemainingMs";
        public static final String CANCELLED = "cancelled";
        public static final String CONFIG_ERROR = "configError";
        public static final String POLICY = "policy";
        public static final String ENGINE = "engine";
        /** Prefix for PAD model outputs, e.g. "pad.minifas_v2". Audit/diagnostics only, never UI. */
        public static final String PAD_PREFIX = "pad.";

        private Keys() { }
    }

    /** Outcome of one challenge, as recorded in {@link Keys#CHALLENGES}. */
    public enum ChallengeOutcome { DETECTED, WRONG_ACTION, TIMEOUT, BASELINE_UNSTABLE, CONTINUITY_BREAK, FACE_LOST,
        MULTIPLE_FACES, PAD_FAILURE, INVALID_FRAME, ABORTED }

    static final int MAX_FREE_TEXT = 120;
    static final int MAX_PAD_KEYS = 8;

    private final AuditSink sink;
    private final WorkflowType workflow;
    private final String sessionId;
    private final TimeSource clock;
    private final String engine;
    private volatile String modelVersion;
    private String deviceId;
    private String deviceCertification;

    private final long sessionStartMs;
    private long attemptStartMs;
    private int attempt;
    private final List<String> challengeLog = new ArrayList<String>();
    private long framesAnalyzed;
    private long framesDropped;
    private long processingMsTotal;
    // Passive summary of the current attempt.
    private String passiveOutcome;
    private float passiveScore = Float.NaN;
    private int passiveFrames;
    private long passiveMs;

    /**
     * @param engine short identifier of the implementation, e.g. "core" or "fake"
     */
    public AuditRecorder(AuditSink sink, WorkflowType workflow, String sessionId, String modelVersion,
                         TimeSource clock, String engine) {
        this.sink = sink; this.workflow = workflow; this.sessionId = sessionId; this.modelVersion = modelVersion;
        this.clock = clock; this.engine = engine;
        this.sessionStartMs = clock.nowMs();
        this.attemptStartMs = sessionStartMs;
    }

    // ------------------------------------------------------------------ context

    public synchronized void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    /** Device identity from {@code FrameSource.info()}: id and certification level only. */
    public synchronized void setDevice(String deviceId, String certificationLevel) {
        this.deviceId = truncate(deviceId);
        this.deviceCertification = truncate(certificationLevel);
    }

    // ------------------------------------------------------------------ metrics (thread-safe)

    public synchronized void frameAnalyzed(long processingMs) {
        framesAnalyzed++;
        processingMsTotal += Math.max(0, processingMs);
    }

    public synchronized void frameDropped() {
        framesDropped++;
    }

    // ------------------------------------------------------------------ records

    public synchronized void attemptStarted(int attemptNumber) {
        attempt = attemptNumber;
        attemptStartMs = clock.nowMs();
        challengeLog.clear();
        passiveOutcome = null;
        passiveScore = Float.NaN;
        write(AuditSink.Event.LIVENESS_STARTED, base());
    }

    /**
     * Passive decision summary. Recorded inside the next attempt-level record rather than as its own event (the
     * contract has no passive event); call before {@link #attemptFailed}/{@link #passed}.
     */
    public synchronized void passiveDecided(String outcome, float medianScore, int windowFrames, long elapsedMs) {
        passiveOutcome = outcome;
        passiveScore = medianScore;
        passiveFrames = windowFrames;
        passiveMs = elapsedMs;
    }

    /** Issued but not yet finished challenges are logged as ABORTED when the attempt ends. */
    public synchronized void challengeIssued(ChallengeType type) {
        challengeLog.add(type.name() + ":" + ChallengeOutcome.ABORTED.name() + ":0");
    }

    /** Replaces the most recent ABORTED placeholder for this type with the real outcome. */
    public synchronized void challengeResolved(ChallengeType type, ChallengeOutcome outcome, long durationMs) {
        String placeholder = type.name() + ":" + ChallengeOutcome.ABORTED.name() + ":0";
        int i = challengeLog.lastIndexOf(placeholder);
        String entry = type.name() + ":" + outcome.name() + ":" + Math.max(0, durationMs);
        if (i >= 0) challengeLog.set(i, entry);
        else challengeLog.add(entry);
    }

    public synchronized void attemptFailed(ErrorCode cause, int retriesLeft, String reason) {
        Map<String, String> d = attemptDetails();
        d.put(Keys.ERROR_CODE, cause.name());
        d.put(Keys.RETRIES_LEFT, Integer.toString(retriesLeft));
        if (reason != null) d.put(Keys.REASON, truncate(reason));
        write(AuditSink.Event.LIVENESS_ATTEMPT_FAILED, d);
    }

    /**
     * PAD suspicion with aggregated scores. {@code padDetails} are the analyzer's aggregated model outputs (e.g.
     * window medians), audit-only; at most {@value #MAX_PAD_KEYS} entries, keys sanitised.
     */
    public synchronized void padSuspected(float aggregatedScore, Map<String, Float> padDetails, String reason) {
        Map<String, String> d = attemptDetails();
        d.put(Keys.ERROR_CODE, ErrorCode.PAD_FAILURE.name());
        d.put(Keys.PASSIVE_SCORE, score(aggregatedScore));
        if (reason != null) d.put(Keys.REASON, truncate(reason));
        if (padDetails != null) {
            int n = 0;
            for (Map.Entry<String, Float> e : padDetails.entrySet()) {
                if (n++ >= MAX_PAD_KEYS) break;
                if (e.getKey() == null || e.getValue() == null) continue;
                d.put(Keys.PAD_PREFIX + sanitiseKey(e.getKey()), score(e.getValue()));
            }
        }
        write(AuditSink.Event.PAD_ATTACK_SUSPECTED, d);
    }

    public synchronized void deviceError(ErrorCode code, boolean recoverable, String detailForAuditOnly) {
        Map<String, String> d = attemptDetails();
        d.put(Keys.ERROR_CODE, code.name());
        d.put(Keys.RECOVERABLE, Boolean.toString(recoverable));
        if (detailForAuditOnly != null) d.put(Keys.DEVICE_DETAIL, truncate(detailForAuditOnly));
        write(AuditSink.Event.DEVICE_ERROR, d);
    }

    public synchronized void passed(LivenessResult.PassPath path, float passiveMedian) {
        Map<String, String> d = finalDetails();
        d.put(Keys.PASS_PATH, path.name());
        d.put(Keys.PASSIVE_SCORE, score(passiveMedian));
        write(AuditSink.Event.LIVENESS_PASSED, d);
    }

    public synchronized void failedFinal(ErrorCode finalCode, ErrorCode lastCause, int cooldownSecStarted) {
        Map<String, String> d = finalDetails();
        d.put(Keys.ERROR_CODE, finalCode.name());
        if (lastCause != null) d.put(Keys.LAST_CAUSE, lastCause.name());
        if (cooldownSecStarted > 0) d.put(Keys.COOLDOWN_SEC, Integer.toString(cooldownSecStarted));
        write(AuditSink.Event.LIVENESS_FAILED_FINAL, d);
    }

    public synchronized void cancelled() {
        Map<String, String> d = finalDetails();
        d.put(Keys.ERROR_CODE, ErrorCode.CANCELLED.name());
        d.put(Keys.CANCELLED, "true");
        write(AuditSink.Event.LIVENESS_FAILED_FINAL, d);
    }

    public synchronized void refusedByCooldown(long remainingMs) {
        Map<String, String> d = base();
        d.put(Keys.ERROR_CODE, ErrorCode.MAX_RETRIES_EXCEEDED.name());
        d.put(Keys.REASON, "cooldown");
        d.put(Keys.COOLDOWN_REMAINING_MS, Long.toString(remainingMs));
        write(AuditSink.Event.LIVENESS_FAILED_FINAL, d);
    }

    public synchronized void modelLoaded() {
        write(AuditSink.Event.MODEL_LOADED, base());
    }

    public synchronized void modelIntegrityFailed(String reason) {
        Map<String, String> d = base();
        d.put(Keys.ERROR_CODE, ErrorCode.MODEL_INTEGRITY_FAILED.name());
        if (reason != null) d.put(Keys.REASON, truncate(reason));
        write(AuditSink.Event.MODEL_INTEGRITY_FAILED, d);
    }

    /** Policy snapshot at service level (no session). */
    public static void configLoaded(AuditSink sink, WorkflowType workflow, LivenessPolicy p, String configError,
                                    String engine) {
        if (sink == null) return;
        Map<String, String> d = new LinkedHashMap<String, String>();
        d.put(Keys.ENGINE, engine);
        d.put(Keys.POLICY, policySummary(p));
        if (configError != null) d.put(Keys.CONFIG_ERROR, truncate(configError));
        safeRecord(sink, AuditSink.Event.CONFIG_LOADED, workflow, null, d);
    }

    // ------------------------------------------------------------------ helpers

    static String policySummary(LivenessPolicy p) {
        return "enabled=" + p.enabled + ",passive=" + p.passiveThreshold + ",padReject=" + p.padRejectThreshold
                + ",window=" + p.passiveWindowFrames + ",passiveTimeoutMs=" + p.passiveTimeoutMs
                + ",active=" + p.activeEnabled + ",pool=" + p.challengePool + ",min=" + p.minChallenges
                + ",challengeTimeoutMs=" + p.challengeTimeoutMs + ",maxRetries=" + p.maxRetries
                + ",onRepeatedFailure=" + p.onRepeatedFailure + ",cooldownSec=" + p.cooldownSec
                + ",analyzeFps=" + p.analyzeFps + ",diagnostic=" + p.diagnosticMode;
    }

    private Map<String, String> base() {
        Map<String, String> d = new LinkedHashMap<String, String>();
        d.put(Keys.ENGINE, engine);
        if (attempt > 0) d.put(Keys.ATTEMPT, Integer.toString(attempt));
        if (modelVersion != null) d.put(Keys.MODEL_VERSION, truncate(modelVersion));
        if (deviceId != null) d.put(Keys.DEVICE_ID, deviceId);
        if (deviceCertification != null) d.put(Keys.DEVICE_CERTIFICATION, deviceCertification);
        return d;
    }

    private Map<String, String> attemptDetails() {
        Map<String, String> d = base();
        if (passiveOutcome != null) {
            d.put(Keys.PASSIVE_OUTCOME, passiveOutcome);
            d.put(Keys.PASSIVE_SCORE, score(passiveScore));
            d.put(Keys.PASSIVE_FRAMES, Integer.toString(passiveFrames));
            d.put(Keys.PASSIVE_MS, Long.toString(passiveMs));
        }
        if (!challengeLog.isEmpty()) d.put(Keys.CHALLENGES, join(challengeLog));
        d.put(Keys.ATTEMPT_MS, Long.toString(clock.nowMs() - attemptStartMs));
        return d;
    }

    private Map<String, String> finalDetails() {
        Map<String, String> d = attemptDetails();
        d.put(Keys.DURATION_MS, Long.toString(clock.nowMs() - sessionStartMs));
        synchronized (this) {
            d.put(Keys.FRAMES_ANALYZED, Long.toString(framesAnalyzed));
            d.put(Keys.FRAMES_DROPPED, Long.toString(framesDropped));
            if (framesAnalyzed > 0) d.put(Keys.AVG_PROCESSING_MS, Long.toString(processingMsTotal / framesAnalyzed));
        }
        return d;
    }

    private void write(AuditSink.Event event, Map<String, String> details) {
        safeRecord(sink, event, workflow, sessionId, details);
    }

    private static void safeRecord(AuditSink sink, AuditSink.Event event, WorkflowType wf, String sessionId,
                                   Map<String, String> details) {
        if (sink == null) return;
        try {
            sink.record(event, wf, sessionId, details);
        } catch (RuntimeException ignored) {
            // Audit is best effort: it must never change the liveness outcome.
        }
    }

    static String score(float f) {
        return Float.isNaN(f) ? "NaN" : String.format(Locale.ROOT, "%.3f", f);
    }

    static String truncate(String s) {
        if (s == null) return null;
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= MAX_FREE_TEXT ? oneLine : oneLine.substring(0, MAX_FREE_TEXT);
    }

    static String sanitiseKey(String k) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < k.length() && b.length() < 40; i++) {
            char c = k.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return b.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(',');
            b.append(p);
        }
        return b.toString();
    }
}
