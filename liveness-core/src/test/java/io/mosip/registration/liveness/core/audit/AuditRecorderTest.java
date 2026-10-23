package io.mosip.registration.liveness.core.audit;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.audit.AuditRecorder.ChallengeOutcome;
import io.mosip.registration.liveness.core.audit.AuditRecorder.Keys;
import io.mosip.registration.liveness.core.util.ManualTimeSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecorderTest {

    static final class Sink implements AuditSink {
        final List<Event> events = new ArrayList<Event>();
        final List<Map<String, String>> details = new ArrayList<Map<String, String>>();
        final List<String> sessionIds = new ArrayList<String>();
        @Override public void record(Event e, WorkflowType wf, String sessionId, Map<String, String> d) {
            events.add(e); details.add(d); sessionIds.add(sessionId);
        }
        Map<String, String> last(Event e) {
            for (int i = events.size() - 1; i >= 0; i--) if (events.get(i) == e) return details.get(i);
            throw new AssertionError("no " + e);
        }
    }

    private final ManualTimeSource clock = new ManualTimeSource(10000);
    private final Sink sink = new Sink();
    private final AuditRecorder rec = new AuditRecorder(sink, WorkflowType.RESIDENT, "sid-1", "pad-2026.09.1", clock, "core");

    @Test
    void publicApiCannotAcceptImagesOrLandmarks() {
        List<Class<?>> forbidden = Arrays.<Class<?>>asList(Frame.class, FrameAnalysis.class, FaceLandmarks.class,
                byte[].class, float[].class, float[][].class, int[].class, Object.class);
        for (Method m : AuditRecorder.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) {
                assertThat(forbidden).as(m.getName() + " parameter " + p.getSimpleName()).doesNotContain(p);
            }
        }
    }

    @Test
    void fullSessionProducesSummaryWithScoresChallengesTimingsAndMetrics() {
        rec.setDevice("SBI-123", "L1");
        rec.attemptStarted(1);
        rec.frameAnalyzed(40);
        rec.frameAnalyzed(60);
        rec.frameDropped();
        clock.advance(1200);
        rec.passiveDecided("NEED_ACTIVE", 0.62f, 10, 1200);
        rec.challengeIssued(ChallengeType.BLINK);
        clock.advance(1800);
        rec.challengeResolved(ChallengeType.BLINK, ChallengeOutcome.DETECTED, 1800);
        rec.challengeIssued(ChallengeType.TURN_LEFT);
        clock.advance(6000);
        rec.challengeResolved(ChallengeType.TURN_LEFT, ChallengeOutcome.TIMEOUT, 6000);
        rec.attemptFailed(ErrorCode.CHALLENGE_TIMEOUT, 3, null);

        Map<String, String> failed = sink.last(AuditSink.Event.LIVENESS_ATTEMPT_FAILED);
        assertThat(failed).containsEntry(Keys.ATTEMPT, "1")
                .containsEntry(Keys.ERROR_CODE, "CHALLENGE_TIMEOUT")
                .containsEntry(Keys.RETRIES_LEFT, "3")
                .containsEntry(Keys.PASSIVE_OUTCOME, "NEED_ACTIVE")
                .containsEntry(Keys.PASSIVE_SCORE, "0.620")
                .containsEntry(Keys.CHALLENGES, "BLINK:DETECTED:1800,TURN_LEFT:TIMEOUT:6000")
                .containsEntry(Keys.ATTEMPT_MS, "9000")
                .containsEntry(Keys.MODEL_VERSION, "pad-2026.09.1")
                .containsEntry(Keys.DEVICE_ID, "SBI-123")
                .containsEntry(Keys.DEVICE_CERTIFICATION, "L1");

        rec.attemptStarted(2);
        rec.passiveDecided("PASS", 0.91f, 10, 900);
        clock.advance(1000);
        rec.passed(LivenessResult.PassPath.PASSIVE_ONLY, 0.91f);

        Map<String, String> passed = sink.last(AuditSink.Event.LIVENESS_PASSED);
        assertThat(passed).containsEntry(Keys.ATTEMPT, "2")
                .containsEntry(Keys.PASS_PATH, "PASSIVE_ONLY")
                .containsEntry(Keys.DURATION_MS, "10000")
                .containsEntry(Keys.FRAMES_ANALYZED, "2")
                .containsEntry(Keys.FRAMES_DROPPED, "1")
                .containsEntry(Keys.AVG_PROCESSING_MS, "50")
                .doesNotContainKey(Keys.CHALLENGES);
        assertThat(sink.sessionIds).containsOnly("sid-1");
    }

    @Test
    void unresolvedChallengeIsLoggedAsAborted() {
        rec.attemptStarted(1);
        rec.challengeIssued(ChallengeType.SMILE);
        rec.cancelled();
        assertThat(sink.last(AuditSink.Event.LIVENESS_FAILED_FINAL))
                .containsEntry(Keys.CHALLENGES, "SMILE:ABORTED:0")
                .containsEntry(Keys.ERROR_CODE, "CANCELLED")
                .containsEntry(Keys.CANCELLED, "true");
    }

    @Test
    void padDetailsAreScoresOnlyCappedAndSanitised() {
        rec.attemptStarted(1);
        Map<String, Float> pad = new LinkedHashMap<String, Float>();
        pad.put("minifas v2", 0.1234f);
        pad.put("moire\nscore", 0.9f);
        for (int i = 0; i < 20; i++) pad.put("extra" + i, 0.5f);
        rec.padSuspected(0.12f, pad, "median below padRejectThreshold");

        Map<String, String> d = sink.last(AuditSink.Event.PAD_ATTACK_SUSPECTED);
        assertThat(d).containsEntry("pad.minifas_v2", "0.123").containsEntry("pad.moire_score", "0.900")
                .containsEntry(Keys.ERROR_CODE, "PAD_FAILURE");
        long padKeys = 0;
        for (String k : d.keySet()) if (k.startsWith(Keys.PAD_PREFIX)) padKeys++;
        assertThat(padKeys).isEqualTo(AuditRecorder.MAX_PAD_KEYS);
    }

    @Test
    void finalFailureKeepsLastCauseAndCooldown() {
        rec.attemptStarted(4);
        rec.failedFinal(ErrorCode.MAX_RETRIES_EXCEEDED, ErrorCode.PAD_FAILURE, 60);
        assertThat(sink.last(AuditSink.Event.LIVENESS_FAILED_FINAL))
                .containsEntry(Keys.ERROR_CODE, "MAX_RETRIES_EXCEEDED")
                .containsEntry(Keys.LAST_CAUSE, "PAD_FAILURE")
                .containsEntry(Keys.COOLDOWN_SEC, "60");
    }

    @Test
    void deviceFreeTextIsTruncatedToOneLine() {
        StringBuilder huge = new StringBuilder("line1\nline2 ");
        for (int i = 0; i < 1000; i++) huge.append('x');
        rec.deviceError(ErrorCode.DEVICE_DISCONNECTED, true, huge.toString());
        String detail = sink.last(AuditSink.Event.DEVICE_ERROR).get(Keys.DEVICE_DETAIL);
        assertThat(detail).hasSize(AuditRecorder.MAX_FREE_TEXT).doesNotContain("\n");
    }

    @Test
    void failingSinkNeverPropagates() {
        AuditRecorder r = new AuditRecorder(new AuditSink() {
            @Override public void record(Event e, WorkflowType wf, String s, Map<String, String> d) {
                throw new IllegalStateException("db down");
            }
        }, WorkflowType.OPERATOR, "s", "m", clock, "core");
        r.attemptStarted(1);
        r.passed(LivenessResult.PassPath.ACTIVE_CHALLENGE, 0.5f);
        new AuditRecorder(null, WorkflowType.OPERATOR, "s", "m", clock, "core").attemptStarted(1);
    }

    @Test
    void configLoadedCarriesPolicySummaryAndError() {
        AuditRecorder.configLoaded(sink, WorkflowType.OPERATOR, new LivenessPolicy(), "bad value", "core");
        Map<String, String> d = sink.last(AuditSink.Event.CONFIG_LOADED);
        assertThat(d.get(Keys.POLICY)).contains("passive=0.85").contains("min=2").contains("maxRetries=3");
        assertThat(d).containsEntry(Keys.CONFIG_ERROR, "bad value");
        assertThat(sink.sessionIds).containsExactly((String) null);
    }

    @Test
    void cooldownRefusalIsAudited() {
        rec.refusedByCooldown(42000);
        assertThat(sink.last(AuditSink.Event.LIVENESS_FAILED_FINAL))
                .containsEntry(Keys.REASON, "cooldown").containsEntry(Keys.COOLDOWN_REMAINING_MS, "42000");
    }
}
