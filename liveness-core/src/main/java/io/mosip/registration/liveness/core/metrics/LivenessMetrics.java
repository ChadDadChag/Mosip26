package io.mosip.registration.liveness.core.metrics;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessResult;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Anonymised operational metrics for one service instance (problem statement, good-to-have): counts and averages
 * only, no session ids, scores of individuals, images or device identity. Thread-safe. In memory: apps that want
 * them over time log {@link Snapshot#toMap()} periodically (Part 4/5 counters, Part 5 benchmark report).
 */
public final class LivenessMetrics {

    private long sessions, passedPassive, passedActive, failedFinal, cancelled, cooldownRefusals;
    private long retries, attemptFailures, deviceErrors;
    private long framesAnalyzed, processingMsTotal;
    private long challengesDetected, challengeMsTotal, challengesFailed;
    private final Map<ErrorCode, Long> finalFailures = new EnumMap<ErrorCode, Long>(ErrorCode.class);
    private final Map<ErrorCode, Long> attemptFailuresByCode = new EnumMap<ErrorCode, Long>(ErrorCode.class);

    public synchronized void sessionStarted() { sessions++; }
    public synchronized void retryStarted() { retries++; }
    public synchronized void frameAnalyzed(long processingMs) { framesAnalyzed++; processingMsTotal += Math.max(0, processingMs); }
    public synchronized void challengeDetected(long durationMs) { challengesDetected++; challengeMsTotal += Math.max(0, durationMs); }
    public synchronized void challengeFailed() { challengesFailed++; }
    public synchronized void deviceError() { deviceErrors++; }
    public synchronized void cancelled() { cancelled++; }
    public synchronized void cooldownRefused() { cooldownRefusals++; }

    public synchronized void attemptFailed(ErrorCode code) {
        attemptFailures++;
        increment(attemptFailuresByCode, code);
    }

    public synchronized void passed(LivenessResult.PassPath path) {
        if (path == LivenessResult.PassPath.PASSIVE_ONLY) passedPassive++; else passedActive++;
    }

    public synchronized void failedFinal(ErrorCode code) {
        failedFinal++;
        increment(finalFailures, code);
    }

    private static void increment(Map<ErrorCode, Long> m, ErrorCode code) {
        if (code == null) return;
        Long v = m.get(code);
        m.put(code, v == null ? 1L : v + 1);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sessions, passedPassive, passedActive, failedFinal, cancelled, cooldownRefusals, retries,
                attemptFailures, deviceErrors, framesAnalyzed, processingMsTotal, challengesDetected, challengeMsTotal,
                challengesFailed, new EnumMap<ErrorCode, Long>(finalFailures),
                new EnumMap<ErrorCode, Long>(attemptFailuresByCode));
    }

    /** Immutable view with the derived rates the problem statement asks for. */
    public static final class Snapshot {
        public final long sessions, passedPassive, passedActive, failedFinal, cancelled, cooldownRefusals;
        public final long retries, attemptFailures, deviceErrors;
        public final long framesAnalyzed, processingMsTotal, challengesDetected, challengeMsTotal, challengesFailed;
        public final Map<ErrorCode, Long> finalFailuresByCode, attemptFailuresByCode;

        Snapshot(long sessions, long passedPassive, long passedActive, long failedFinal, long cancelled,
                 long cooldownRefusals, long retries, long attemptFailures, long deviceErrors, long framesAnalyzed,
                 long processingMsTotal, long challengesDetected, long challengeMsTotal, long challengesFailed,
                 Map<ErrorCode, Long> finalFailuresByCode, Map<ErrorCode, Long> attemptFailuresByCode) {
            this.sessions = sessions; this.passedPassive = passedPassive; this.passedActive = passedActive;
            this.failedFinal = failedFinal; this.cancelled = cancelled; this.cooldownRefusals = cooldownRefusals;
            this.retries = retries; this.attemptFailures = attemptFailures; this.deviceErrors = deviceErrors;
            this.framesAnalyzed = framesAnalyzed; this.processingMsTotal = processingMsTotal;
            this.challengesDetected = challengesDetected; this.challengeMsTotal = challengeMsTotal;
            this.challengesFailed = challengesFailed; this.finalFailuresByCode = finalFailuresByCode;
            this.attemptFailuresByCode = attemptFailuresByCode;
        }

        public long passed() { return passedPassive + passedActive; }

        /** Average analyzer time per analysed frame, ms (NaN if none). */
        public double avgProcessingMs() { return ratio(processingMsTotal, framesAnalyzed); }

        /** Average prompt-to-detection time of successful challenges, ms (NaN if none). */
        public double avgChallengeCompletionMs() { return ratio(challengeMsTotal, challengesDetected); }

        /** Retries pressed per session. */
        public double retryRate() { return ratio(retries, sessions); }

        /** Sessions that ended FAILED (not cancelled) per session. */
        public double failureRate() { return ratio(failedFinal, sessions); }

        /** Share of passes that needed no challenge. */
        public double passiveShare() { return ratio(passedPassive, passed()); }

        private static double ratio(long a, long b) { return b == 0 ? Double.NaN : (double) a / b; }

        /** Flat, log-friendly form. */
        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("sessions", Long.toString(sessions));
            m.put("passed", Long.toString(passed()));
            m.put("passedPassive", Long.toString(passedPassive));
            m.put("passedActive", Long.toString(passedActive));
            m.put("failedFinal", Long.toString(failedFinal));
            m.put("cancelled", Long.toString(cancelled));
            m.put("cooldownRefusals", Long.toString(cooldownRefusals));
            m.put("retries", Long.toString(retries));
            m.put("deviceErrors", Long.toString(deviceErrors));
            m.put("avgProcessingMs", fmt(avgProcessingMs()));
            m.put("avgChallengeCompletionMs", fmt(avgChallengeCompletionMs()));
            m.put("retryRate", fmt(retryRate()));
            m.put("failureRate", fmt(failureRate()));
            m.put("passiveShare", fmt(passiveShare()));
            for (Map.Entry<ErrorCode, Long> e : finalFailuresByCode.entrySet()) {
                m.put("finalFailure." + e.getKey().name(), e.getValue().toString());
            }
            return m;
        }

        private static String fmt(double d) {
            return Double.isNaN(d) ? "NaN" : String.format(Locale.ROOT, "%.3f", d);
        }
    }
}
