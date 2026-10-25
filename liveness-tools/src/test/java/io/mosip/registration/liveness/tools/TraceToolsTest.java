package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.FaceGeometry;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TraceToolsTest {

    static File traceDir() throws Exception {
        URL u = TraceToolsTest.class.getResource("/traces");
        return new File(u.toURI());
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> m = Json.parse("{\"a\":1.5,\"b\":\"x\\\"y\",\"c\":[1,2],\"d\":null,\"e\":true,\"f\":[]}");
        assertThat(m.get("a")).isEqualTo(1.5);
        assertThat(m.get("b")).isEqualTo("x\"y");
        assertThat((List<?>) m.get("c")).hasSize(2);
        assertThat(m.get("d")).isNull();
        assertThat(m.get("e")).isEqualTo(Boolean.TRUE);
        assertThat(Json.parse(Json.write(m))).isEqualTo(m);
    }

    @Test
    void traceRoundTripKeepsFeaturesOnly() throws Exception {
        Trace t = SyntheticTraceGenerator.generate("smile", new Random(1));
        StringWriter w = new StringWriter();
        t.write(w);
        String text = w.toString();
        // Privacy: no pixels and no landmark coordinates are ever written.
        assertThat(text).doesNotContain("rgb").doesNotContain("leftEye").doesNotContain("landmarks")
                .doesNotContain("mouthLeft");
        Trace back = Trace.read(new StringReader(text));
        assertThat(back.label).isEqualTo("smile");
        assertThat(back.promptAtMs).isEqualTo(t.promptAtMs);
        assertThat(back.frames).hasSize(t.frames.size());
        TraceFrame a = t.frames.get(10), b = back.frames.get(10);
        assertThat(b.ear).isCloseTo(a.ear, within(1e-3f));
        assertThat(b.mouthWidth).isCloseTo(a.mouthWidth, within(1e-3f));
        assertThat(b.yaw).isCloseTo(a.yaw, within(1e-3f));
    }

    @Test
    void syntheticFaceReproducesTheRecordedFeatures() {
        TraceFrame f = new TraceFrame(0, 1, 1, new float[] {0.3f, 0.2f, 0.35f, 0.55f}, 5f, -3f, 0f, 0.7f, 0.21f, 1.07f,
                0.04f, EnumSet.noneOf(FrameAnalysis.QualityIssue.class), 20);
        FrameAnalysis a = SyntheticFace.analysis(f, 1000);
        assertThat(FaceGeometry.meanEar(a.landmarks)).isCloseTo(0.21f, within(1e-4f));
        assertThat(FaceGeometry.mouthWidthRatio(a.landmarks)).isCloseTo(1.07f, within(1e-4f));
        assertThat(FaceGeometry.cornerLift(a.landmarks)).isCloseTo(0.04f, within(1e-4f));
        assertThat(TraceFrame.of(a, 1000).ear).isCloseTo(0.21f, within(1e-4f));
    }

    /** Stand-ins for Part 1's source and Part 2's analyzer: only prove the recorder's plumbing. */
    @Test
    void recorderKeepsOnlyFeaturesFromAnyFrameSourceAndAnalyzer() throws Exception {
        final byte[] px = new byte[8 * 8 * 3];
        FrameSource src = new FrameSource() {
            volatile Thread t;
            @Override public DeviceInfo info() { return new DeviceInfo("dev-1", "v", "m", "1", "L0", 8, 8, 30); }
            @Override public void start(final Listener l) {
                t = new Thread(new Runnable() {
                    @Override public void run() {
                        for (long i = 1; !Thread.currentThread().isInterrupted() && i < 100000; i++) {
                            l.onFrame(new Frame(px, 8, 8, i * 10, i, "dev-1"));
                            Thread.yield();
                        }
                    }
                });
                t.start();
            }
            @Override public void stop() { if (t != null) t.interrupt(); }
            @Override public boolean isRunning() { return t != null && t.isAlive(); }
        };
        FaceAnalyzer analyzer = new FaceAnalyzer() {
            @Override public FrameAnalysis analyze(Frame f) {
                float[][] eye = {{0, 0}, {1, -1}, {2, -1}, {3, 0}, {2, 1}, {1, 1}};
                float[][] eye2 = {{6, 0}, {7, -1}, {8, -1}, {9, 0}, {8, 1}, {7, 1}};
                FaceLandmarks l = new FaceLandmarks(eye, eye2, new float[] {3, 6}, new float[] {6, 6},
                        new float[] {4.5f, 5.8f}, new float[] {4.5f, 6.2f}, new float[] {4.5f, 3}, new float[] {4.5f, 8}, null, null);
                return new FrameAnalysis(f, 1, new float[] {1, 1, 6, 6}, l, 2, 1, 0, null, 0.8f, null, 3);
            }
            @Override public String modelVersion() { return "stub-model"; }
            @Override public void close() { }
        };
        Trace t = TraceRecorder.record(src, analyzer, "neutral", 50, 300, 50, null);
        assertThat(t.frames).isNotEmpty();
        assertThat(t.header).containsEntry("model", "stub-model").containsEntry("device", "dev-1")
                .containsEntry("synthetic", false);
        assertThat(t.frames.get(0).ear).isCloseTo(2f / 3f, within(1e-4f));
        StringWriter w = new StringWriter();
        t.write(w);
        assertThat(w.toString()).doesNotContain("rgb").doesNotContain("leftEye");
    }

    // ------------------------------------------------------------------ regression over committed traces

    @Test
    void committedActionTracesAreDetectedWithDefaultTuning() throws Exception {
        List<Trace> traces = Trace.readDir(traceDir());
        assertThat(traces).hasSizeGreaterThanOrEqualTo(10);
        EngineTuning t = EngineTuning.defaults();
        int actions = 0;
        for (Trace tr : traces) {
            for (ChallengeType c : ChallengeType.values()) {
                if (!DetectorTuningReport.label(c).equals(tr.label)) continue;
                actions++;
                TraceReplayer.DetectorRun run = TraceReplayer.runDetector(tr, c, t);
                assertThat(run.detected).as(tr.label + " " + tr.header).isTrue();
                assertThat(run.timeToDetectMs).isBetween(150L, 6000L);
            }
        }
        assertThat(actions).isGreaterThanOrEqualTo(12);
    }

    @Test
    void committedTracesNeverTriggerTheWrongMotionDetector() throws Exception {
        EngineTuning t = EngineTuning.defaults();
        for (Trace tr : Trace.readDir(traceDir())) {
            if (tr.isAttack()) continue;
            for (ChallengeType c : new ChallengeType[] {ChallengeType.SMILE, ChallengeType.TURN_LEFT,
                    ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN}) {
                if (DetectorTuningReport.label(c).equals(tr.label)) continue;
                assertThat(TraceReplayer.runDetector(tr, c, t).detected).as(c + " fired on " + tr.label).isFalse();
            }
        }
    }

    @Test
    void attackTracesNeverPassPassively() throws Exception {
        LivenessPolicy p = new LivenessPolicy();
        for (Trace tr : Trace.readDir(traceDir())) {
            if (!tr.isAttack()) continue;
            TraceReplayer.PassiveRun run = TraceReplayer.runPassive(tr, p, EngineTuning.defaults());
            assertThat(run.verdict).as(tr.label).isNotEqualTo(PassiveEvaluator.Verdict.PASS);
        }
    }

    @Test
    void reportRendersAndKeepsDefaultsOnTies() throws Exception {
        String r = DetectorTuningReport.report(Trace.readDir(traceDir()));
        assertThat(r).contains("WARNING: synthetic").contains("blinkClosedRatio").contains("turnMinDeg")
                .contains("APCER").contains("Recommended EngineTuning values");
        List<DetectorTuningReport.Row> rows = DetectorTuningReport.sweep(Trace.readDir(traceDir()), DetectorTuningReport.TURN);
        assertThat(DetectorTuningReport.recommend(rows, 20f).value).isEqualTo(20f);
    }

    @Test
    void allZeroTprIsInsufficientEvidenceNotAnFprPick() throws Exception {
        // Real traces once gave TPR 0 at every blinkClosedRatio and the report "recommended" 0.45 on FPR alone.
        List<DetectorTuningReport.Row> zero = java.util.Arrays.asList(
                new DetectorTuningReport.Row(0.45f, 0, 0.0, 0, -1, 3, 8),
                new DetectorTuningReport.Row(0.60f, 0, 0.13, 0, -1, 3, 8),
                new DetectorTuningReport.Row(0.75f, 0, 0.5, 0, -1, 3, 8));
        assertThat(DetectorTuningReport.insufficient(zero)).isTrue();
        assertThat(DetectorTuningReport.recommend(zero, 0.60f).value).as("keeps the current value").isEqualTo(0.60f);
        assertThat(DetectorTuningReport.recommend(zero, 0.50f)).as("current value not swept").isNull();
        assertThat(DetectorTuningReport.insufficient(java.util.Arrays.asList(
                new DetectorTuningReport.Row(0.6f, 1.0, 0, 0, 300, 1, 8)))).as("one positive").isTrue();

        // A report over "blink" traces that never blink (EAR flattened) says so instead of recommending a value.
        List<Trace> traces = new java.util.ArrayList<Trace>();
        for (Trace t : Trace.readDir(traceDir())) {
            if (!t.label.equals("neutral")) continue;
            traces.add(t);
            StringWriter w = new StringWriter();
            t.write(w);
            String flat = w.toString().replaceAll("\"ear\":[-0-9.Ee]+", "\"ear\":0.3")
                    .replace("\"label\":\"neutral\"", "\"label\":\"blink\"");
            Trace noBlink = Trace.read(new StringReader(flat));
            assertThat(noBlink.label).isEqualTo("blink");
            traces.add(noBlink);
        }
        String r = DetectorTuningReport.report(traces);
        assertThat(r).contains("insufficient positive evidence: keep current blinkClosedRatio = 0.60")
                .contains("blinkReopenRatio = 0.85 (unchanged; insufficient positive evidence)")
                .contains("blinkMaxClosedMs = 600.00 (unchanged; insufficient positive evidence)");
    }

    @Test
    void newKnobsAreSwept() throws Exception {
        List<Trace> traces = Trace.readDir(traceDir());
        assertThat(DetectorTuningReport.sweep(traces, DetectorTuningReport.BLINK_REOPEN)).hasSize(3);
        assertThat(DetectorTuningReport.sweep(traces, DetectorTuningReport.BLINK_MAX_CLOSED)).hasSize(4);
        List<DetectorTuningReport.Row> drift = DetectorTuningReport.sweep(traces, DetectorTuningReport.POSE_DRIFT);
        assertThat(drift).hasSize(3);
        assertThat(drift.get(0).positives).as("blink and smile traces").isEqualTo(4);
        assertThat(DetectorTuningReport.report(traces)).contains("actionMaxPoseDriftDeg").contains("blinkMaxClosedMs");
    }

    @Test
    void recommendationPrefersBetterSeparation() {
        List<DetectorTuningReport.Row> rows = java.util.Arrays.asList(
                new DetectorTuningReport.Row(10, 1.0, 0.5, 0, 500, 4, 4),
                new DetectorTuningReport.Row(20, 0.9, 0.0, 0, 900, 4, 4),
                new DetectorTuningReport.Row(30, 0.5, 0.0, 0, 1200, 4, 4));
        assertThat(DetectorTuningReport.recommend(rows, 30f).value).isEqualTo(20f);
        assertThat(DetectorTuningReport.recommend(java.util.Collections.<DetectorTuningReport.Row>emptyList(), 1f)).isNull();
    }

    @Test
    void recorderSurfacesNonRecoverableDeviceErrors() {
        FrameSource broken = new FrameSource() {
            @Override public DeviceInfo info() { return null; }
            @Override public void start(Listener l) { l.onError(ErrorCode.DEVICE_UNAVAILABLE, "unplugged", false); }
            @Override public void stop() { }
            @Override public boolean isRunning() { return false; }
        };
        try {
            TraceRecorder.record(broken, null, "neutral", 0, 1000, 30, null);
            throw new AssertionError("expected failure");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("DEVICE_UNAVAILABLE");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
