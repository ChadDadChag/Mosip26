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
        assertThat(SessionDemo.scripted('o').instruction).contains("GROUP PHOTO print");
        assertThatThrownBy(() -> SessionDemo.scripted('z')).hasMessageContaining("m, n, o");
    }

    @Test
    void theSinglePrintRetestCasesNameTheCutOutAndTheDistance() {
        SessionDemo.Case s = SessionDemo.scripted("s3");
        assertThat(s.item).isEqualTo(3);
        assertThat(s.workflow).isEqualTo(io.mosip.registration.liveness.api.WorkflowType.RESIDENT);
        assertThat(s.forceActive).isFalse();
        assertThat(s.instruction).startsWith("CUT-OUT 3 about 20 cm").contains("your face out of view", "STILL");
        assertThat(SessionDemo.scripted(" T6 ").instruction).startsWith("CUT-OUT 6 about 30 cm");
        assertThat(SessionDemo.scripted("u").instruction).startsWith("NASA A4 PRINT about 30 cm");
        assertThat(SessionDemo.scripted("u").item).isZero();
        assertThat(SessionDemo.scripted("v2").instruction).startsWith("CUT-OUT 2 about 20 cm").contains("SLIGHT NATURAL MOTION");
        assertThat(SessionDemo.scripted("m").instruction).startsWith("MULTI-FACE");
        assertThatThrownBy(() -> SessionDemo.scripted("s")).hasMessageContaining("s3");
        assertThatThrownBy(() -> SessionDemo.scripted("m2")).hasMessageContaining("takes no number");
    }

    @Test
    void everyFrameIsLoggedWithTheModelOutputsAndTheFaceSize() {
        java.util.Map<String, Float> pad = new java.util.HashMap<String, Float>();
        pad.put("minifas_v2_live", 0.97f);
        pad.put("minifas_v1se_live", 0.88f);
        pad.put("moire", 1.5f);
        pad.put("moire_med", 1.25f);
        pad.put("moire_cap", 0f);
        pad.put("volatility_cap", 1f);
        pad.put("pad_latch", 0f);
        io.mosip.registration.liveness.api.Frame frame = new io.mosip.registration.liveness.api.Frame(
                new byte[640 * 480 * 3], 640, 480, 0, 0, "test");
        FrameAnalysis a = new FrameAnalysis(frame, 1, new float[] {100, 80, 160, 192}, null, 3, -2, 1.5f,
                java.util.EnumSet.of(FrameAnalysis.QualityIssue.FACE_TOO_SMALL,
                        FrameAnalysis.QualityIssue.TOO_DARK), 0.99f, pad, 1);
        SessionDemo.RecordingFactory rf = new SessionDemo.RecordingFactory(null);
        rf.samples.add(new SessionDemo.Sample(40, a));
        rf.samples.add(sample(80, 1, 1)); // no frame, no box, no PAD details
        SessionDemo.RunLog r = new SessionDemo.RunLog(null, false);
        String json = SessionDemo.json(SessionDemo.scripted("t4"), 2, r, rf);
        assertThat(json).startsWith("{\"case\":\"t2\",\"workflow\":\"RESIDENT\",\"forced\":false,\"item\":4")
                .contains("\"frameFields\":[\"t\",\"faces\",\"yaw\",\"pitch\",\"ear\",\"score\",\"boxWidth\",\"roll\","
                        + "\"areaFrac\",\"v2Live\",\"v1seLive\",\"moire\",\"moireMed\",\"moireCap\",\"volCap\",\"padLatch\","
                        + "\"issues\"]")
                .contains("[40,1,3.000,-2.000,null,0.990,160.000,1.500,0.100,0.970,0.880,1.500,1.250,0.000,1.000,0.000,"
                        + "\"TOO_DARK FACE_TOO_SMALL\"]")
                .contains("[80,1,1.000,1.000,null,0.900,null,0.000,null,null,null,null,null,null,null,null,\"\"]");
    }

    @Test
    void theCombinedRetestCasesBringTheirOwnChallengePoolAndRealFaceCases() {
        SessionDemo.Case w = SessionDemo.scripted("w2");
        assertThat(w.forceActive).isTrue();
        assertThat(w.item).isEqualTo(2);
        assertThat(w.types).isEqualTo("TURN_LEFT,TURN_RIGHT");
        assertThat(w.instruction).startsWith("CUT-OUT 2 about 20 cm").contains("your face out of view", "ROTATE");
        SessionDemo.Case x = SessionDemo.scripted("x1");
        assertThat(x.forceActive).isTrue();
        assertThat(x.types).isEqualTo("BLINK,SMILE");
        assertThat(x.instruction).contains("TILT", "your face out of view");
        assertThat(SessionDemo.scripted("s1").types).isNull();
        for (String real : new String[] {"a", "i", "j", "k", "l"}) {
            SessionDemo.Case c = SessionDemo.scripted(real);
            assertThat(c.forceActive).isFalse();
            assertThat(c.workflow).isEqualTo(io.mosip.registration.liveness.api.WorkflowType.RESIDENT);
            assertThat(c.instruction).startsWith("REAL FACE");
        }
        assertThat(SessionDemo.scripted("k").instruction).contains("APPROACHING", "SLOWLY");
        assertThatThrownBy(() -> SessionDemo.scripted("w")).hasMessageContaining("w3");
    }
}
