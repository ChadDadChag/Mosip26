package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.FrameAnalysis;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionDemoTest {

    private static SessionDemo.Sample sample(long t, float yaw, float pitch) {
        return new SessionDemo.Sample(t, new FrameAnalysis(null, 1, null, null, yaw, pitch, 0, null, 0.9f, null, 1));
    }

    @Test
    void driftIsMeasuredFromThePosesBeforeThePrompt() {
        SessionDemo.RecordingFactory rf = new SessionDemo.RecordingFactory(null);
        // Baseline (the 600 ms before the prompt at 1000 ms): yaw 2, pitch -1.
        rf.samples.addAll(Arrays.asList(sample(500, 2, -1), sample(700, 2, -1), sample(900, 3, -1)));
        // During the challenge: a tilt to 20 deg of pitch drift, back, then to 15 deg of yaw drift.
        rf.samples.addAll(Arrays.asList(sample(1100, 2, -1), sample(1200, 2, 19), sample(1300, 2, 18),
                sample(1400, 2, 0), sample(1500, 17, -1), sample(1600, 2, -1)));
        rf.samples.add(sample(5000, 40, 40)); // after the challenge: ignored
        assertThat(SessionDemo.drift(rf, 1000, 2000, 12))
                .isEqualTo("[drift max 20.0 deg; 3 of 6 frames over 12 deg; 2 excursions]");
        assertThat(SessionDemo.drift(new SessionDemo.RecordingFactory(null), 1000, 2000, 12))
                .isEqualTo("[drift: no baseline frames]");
    }

    @Test
    void configOverridesNeedKeyEqualsValue() {
        ConfigProvider c = SessionDemo.overrides(Arrays.asList(
                "mosip.registration.liveness.face.default.padRejectThreshold = 0.0", "a=b=c"));
        assertThat(c.get("mosip.registration.liveness.face.default.padRejectThreshold")).isEqualTo("0.0");
        assertThat(c.get("a")).isEqualTo("b=c");
        assertThat(c.get("missing")).isNull();
        assertThat(SessionDemo.overrides(Collections.<String>emptyList()).get("x")).isNull();
        assertThatThrownBy(() -> SessionDemo.overrides(Collections.singletonList("novalue")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMultiFaceCasesArePassiveResidentSessions() {
        for (char c : new char[] {'m', 'n', 'o'}) {
            SessionDemo.Case k = SessionDemo.scripted(c);
            assertThat(k.workflow).isEqualTo(io.mosip.registration.liveness.api.WorkflowType.RESIDENT);
            assertThat(k.forceActive).isFalse();
            assertThat(k.instruction).startsWith("MULTI-FACE").contains("FINISHED");
        }
        assertThat(SessionDemo.scripted('m').instruction).contains("YOUR FACE", "30 cm");
        assertThat(SessionDemo.scripted('n').instruction).contains("TWO CUT-OUTS", "your face out of view");
        assertThat(SessionDemo.scripted('o').instruction).contains("UNCUT SHEET of 6");
        assertThatThrownBy(() -> SessionDemo.scripted('z')).hasMessageContaining("m, n, o");
    }
}
