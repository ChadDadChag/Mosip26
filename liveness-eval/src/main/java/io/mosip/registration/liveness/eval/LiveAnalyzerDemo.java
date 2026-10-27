package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.AnalyzerStats;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.pad.ReplayCues;
import io.mosip.registration.liveness.analyzer.quality.QualityAssessor;
import io.mosip.registration.liveness.analyzer.quality.QualityReport;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.core.detect.FaceGeometry;

import javax.swing.JOptionPane;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live webcam demo of the Part 2 analyzer and of a full Part 3 session (development only). Camera frames stay in
 * memory; nothing is written unless a dataset take is recorded with consent (R key) or a report is saved.
 * <pre>
 *   LiveAnalyzerDemo --mode analyze    [--camera 0] [--set name=value ...]   overlay: box, eye points, irises, EAR,
 *                                      pose, scores, issues, ms; the largest yaw with landmarks per side (Z resets)
 *   LiveAnalyzerDemo --mode calibrate  [--camera 0]   guided quality-calibration conditions, per-condition issue table
 *   LiveAnalyzerDemo --mode session    [--workflow RESIDENT|OPERATOR|SUPERVISOR] [--force-active] [--auto-retry]
 *   LiveAnalyzerDemo --mode series     --plan a,b,c,...   scripted session runs with prompts (see {@link SessionDemo})
 *                                      [--challenge-types ...] [--config key=value ...] [--set name=value ...]
 *   LiveAnalyzerDemo --mode traces     --consent [--labels ...] [--takes 2]   guided Part 3 TraceRecorder takes
 *   LiveAnalyzerDemo --mode dataset    --consent [--subject s01] [--only species,...]   guided PAD dataset takes
 * </pre>
 */
public final class LiveAnalyzerDemo {

    private LiveAnalyzerDemo() { }

    public static void main(String[] args) throws Exception {
        ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0,
                Arrays.asList("--force-active", "--auto-retry", "--consent"));
        String mode = a.has("--mode") ? a.single("--mode") : "analyze";
        int camera = Integer.parseInt(a.has("--camera") ? a.single("--camera") : "0");
        switch (mode) {
            case "analyze": analyze(camera, PadEvaluation.analyzerConfig(a.all("--set"))); break;
            case "calibrate": calibrate(camera); break;
            case "session":
            case "series": SessionDemo.run(a, camera); break;
            case "traces": TraceCapture.run(a, camera); break;
            case "dataset": DatasetSession.run(a, camera); break;
            default: throw new IllegalArgumentException("unknown --mode " + mode);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // analyze
    // ---------------------------------------------------------------------------------------------

    /** Analyses the newest frame on a worker thread (older frames are dropped while busy). */
    static final class LiveLoop {
        final AtomicReference<Frame> latest = new AtomicReference<Frame>();
        volatile boolean running = true;

        FrameSource.Listener listener(final DemoWindow w) {
            return new FrameSource.Listener() {
                @Override public void onFrame(Frame f) { latest.set(f); }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                    w.message("Camera: " + code + " " + detail, Color.RED);
                }
            };
        }
    }

    /** The largest yaw on each side over frames that had landmarks (F7 check); Z resets it. */
    static final class YawRange {
        volatile float left = Float.NaN, right = Float.NaN; // + = the subject's left, - = their right

        synchronized void add(FrameAnalysis r) {
            if (r.landmarks == null || Float.isNaN(r.yawDeg)) return;
            if (r.yawDeg > 0 && (Float.isNaN(left) || r.yawDeg > left)) left = r.yawDeg;
            if (r.yawDeg < 0 && (Float.isNaN(right) || r.yawDeg < right)) right = r.yawDeg;
        }

        synchronized void reset() {
            left = Float.NaN;
            right = Float.NaN;
        }

        @Override public synchronized String toString() {
            return String.format("max yaw with landmarks: subject's left %+.1f, subject's right %+.1f", left, right);
        }
    }

    static void analyze(final int camera, AnalyzerConfig config) throws Exception {
        final OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory(
                new io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource(), config);
        final OnnxFaceAnalyzer analyzer = (OnnxFaceAnalyzer) factory.create();
        final DemoWindow w = new DemoWindow("Part 2 analyzer: live (" + factory.modelVersion() + ", mesh tracking "
                + (config.meshTracking ? "on" : "off") + ")");
        final WebcamFrameSource[] source = {new WebcamFrameSource(camera, 640, 480, 15)};
        final LiveLoop loop = new LiveLoop();
        final Deque<Float> last10 = new ArrayDeque<Float>();
        final YawRange yaw = new YawRange();
        w.message("Live analysis: R = record a dataset take (with consent), Z = reset the max yaw", Color.DARK_GRAY);
        w.key("R", new Runnable() {
            @Override public void run() {
                recordTake(w, camera, source, loop);
            }
        });
        w.key("Z", new Runnable() {
            @Override public void run() {
                yaw.reset();
                System.out.println("max yaw reset");
                w.message("Max yaw reset", new Color(0, 120, 0));
            }
        });
        w.onClose(new Runnable() {
            @Override public void run() {
                loop.running = false;
            }
        });
        w.show();
        source[0].start(loop.listener(w));
        long nextStatus = System.currentTimeMillis() + 5000;
        int analysed = 0;
        while (loop.running) {
            Frame f = loop.latest.getAndSet(null);
            if (f == null) {
                Thread.sleep(5);
                continue;
            }
            FrameAnalysis r = analyzer.analyze(f);
            analysed++;
            yaw.add(r);
            if (!Float.isNaN(r.livenessScore)) {
                last10.addLast(r.livenessScore);
                if (last10.size() > 10) last10.removeFirst();
            }
            w.video(DemoWindow.image(f), overlay(r));
            w.info(describe(r, analyzer.stats(), median(last10), analyzer.lastReplayCues()) + yaw + "\n"
                    + String.format("tracked frames %d, fallbacks %d%n", analyzer.stats().framesTracked(),
                    analyzer.stats().trackingFallbacks()));
            if (System.currentTimeMillis() >= nextStatus) {
                System.out.printf("status: %d frames analysed; last: faces %d score %.3f usable %b issues %s total %.1f ms; %s%n",
                        analysed, r.faceCount, r.livenessScore, r.usable(), r.qualityIssues,
                        analyzer.stats().lastTotalNs() / 1e6, yaw);
                nextStatus += 5000;
            }
        }
        System.out.println("final " + yaw + "; tracked frames " + analyzer.stats().framesTracked() + ", fallbacks "
                + analyzer.stats().trackingFallbacks() + " (mesh tracking " + (config.meshTracking ? "on" : "off") + ")");
        source[0].stop();
        analyzer.close();
        factory.close();
        System.exit(0);
    }

    private static void recordTake(final DemoWindow w, final int camera, final WebcamFrameSource[] source, final LiveLoop loop) {
        int ok = JOptionPane.showConfirmDialog(null,
                "Record a 6-second dataset take of the person in front of the camera?\n"
                        + "JPEG frames go to eval-data/ (gitignored, never committed). Only continue with their consent.",
                "Consent", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        final String label = (String) JOptionPane.showInputDialog(null, "Label", "Dataset take", JOptionPane.QUESTION_MESSAGE,
                null, DatasetRecorder.LABELS.toArray(), "bona_fide");
        final String subject = JOptionPane.showInputDialog("Subject code (sNN, no names)", "s01");
        final String take = JOptionPane.showInputDialog("Take number", "1");
        if (label == null || subject == null || take == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    source[0].stop();
                    w.message("Recording " + label + " " + subject + " take " + take + "…", Color.RED);
                    File dir = DatasetRecorder.record(new WebcamFrameSource(camera, 640, 480, 15),
                            new DatasetRecorder.Take(label, subject, Integer.parseInt(take), 6, 10, new File("eval-data"), "jpg", null),
                            System.out);
                    w.message("Saved " + dir, new Color(0, 120, 0));
                } catch (Exception e) {
                    w.message("Recording failed: " + e.getMessage(), Color.RED);
                } finally {
                    source[0] = new WebcamFrameSource(camera, 640, 480, 15);
                    source[0].start(loop.listener(w));
                }
            }
        }, "dataset-take").start();
    }

    static DemoWindow.Overlay overlay(final FrameAnalysis r) {
        return new DemoWindow.Overlay() {
            @Override public void paint(Graphics2D g) {
                if (r.faceBox == null) return;
                g.setStroke(new BasicStroke(2f));
                g.setColor(r.usable() ? new Color(0, 200, 0) : Color.ORANGE);
                g.drawRect(Math.round(r.faceBox[0]), Math.round(r.faceBox[1]), Math.round(r.faceBox[2]), Math.round(r.faceBox[3]));
                FaceLandmarks l = r.landmarks;
                if (l != null) {
                    g.setColor(Color.YELLOW);
                    for (float[][] eye : new float[][][] {l.leftEye, l.rightEye}) {
                        for (float[] p : eye) g.fillOval(Math.round(p[0]) - 2, Math.round(p[1]) - 2, 4, 4);
                    }
                    g.setColor(Color.CYAN);
                    for (float[] p : new float[][] {l.leftIris, l.rightIris}) {
                        if (p != null) g.drawOval(Math.round(p[0]) - 3, Math.round(p[1]) - 3, 6, 6);
                    }
                }
                g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
                g.setColor(Color.WHITE);
                g.drawString(Float.isNaN(r.livenessScore) ? "score --" : String.format("score %.3f", r.livenessScore),
                        Math.round(r.faceBox[0]), Math.max(16, Math.round(r.faceBox[1]) - 6));
            }
        };
    }

    static String describe(FrameAnalysis r, AnalyzerStats s, float median10, ReplayCues.Cues cues) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("faceCount   %d%n", r.faceCount));
        if (r.landmarks != null) {
            sb.append(String.format("EAR L/R     %.3f / %.3f  mean %.3f%n", FaceGeometry.eyeAspectRatio(r.landmarks.leftEye),
                    FaceGeometry.eyeAspectRatio(r.landmarks.rightEye), FaceGeometry.meanEar(r.landmarks)));
            sb.append(String.format("mouth w/open %.3f / %.3f%n", FaceGeometry.mouthWidthRatio(r.landmarks), FaceGeometry.mouthOpenRatio(r.landmarks)));
        }
        sb.append(String.format("yaw/pitch/roll %+.1f / %+.1f / %+.1f%n", r.yawDeg, r.pitchDeg, r.rollDeg));
        sb.append(String.format("livenessScore %.4f  median10 %.4f%n", r.livenessScore, median10));
        Float v2 = r.padDetails.get("minifas_v2_live"), v1 = r.padDetails.get("minifas_v1se_live");
        sb.append(String.format("v2 / v1se live %s / %s%n", fmt(v2), fmt(v1)));
        sb.append(String.format("moire %s  session median %s%s  glare %s%n", fmt(r.padDetails.get("moire")),
                fmt(r.padDetails.get("moire_med")), Float.valueOf(1f).equals(r.padDetails.get("moire_cap")) ? " CAPPED" : "",
                cues == null ? "--" : String.format("%.3f", cues.glare)));
        if (r.padDetails.containsKey("pose_gated")) sb.append("PAD pose-gated (outside the model's pose range)\n");
        if (Float.valueOf(1f).equals(r.padDetails.get("volatility_cap"))) sb.append("VOLATILITY CAP (unsteady scores)\n");
        if (Float.valueOf(1f).equals(r.padDetails.get("pad_latch"))) sb.append("PAD LATCHED for this session\n");
        sb.append("issues      ").append(r.qualityIssues).append('\n');
        sb.append("usable()    ").append(r.usable()).append('\n');
        sb.append(String.format("ms detect %.1f mesh %.1f pose %.2f quality %.1f pad %.1f cues %.1f total %.1f%n",
                s.lastDetectNs() / 1e6, s.lastMeshNs() / 1e6, s.lastPoseNs() / 1e6, s.lastQualityNs() / 1e6,
                s.lastPadNs() / 1e6, s.lastCuesNs() / 1e6, s.lastTotalNs() / 1e6));
        return sb.toString();
    }

    private static String fmt(Float v) {
        return v == null ? "--" : String.format("%.3f", v);
    }

    static float median(Deque<Float> values) {
        if (values.isEmpty()) return Float.NaN;
        List<Float> s = new ArrayList<Float>(values);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2f;
    }

    // ---------------------------------------------------------------------------------------------
    // calibrate
    // ---------------------------------------------------------------------------------------------

    static final String[][] CONDITIONS = {
        {"normal", "Sit normally and look at the camera"},
        {"close", "Move CLOSE to the camera"},
        {"far", "Move BACK, far from the camera"},
        {"side", "Move to one SIDE of the picture"},
        {"dim", "Make it DIM (lights off or shade your face)"},
        {"bright", "Make it BRIGHT (face a window or lamp)"},
        {"motion", "Move your head QUICKLY side to side"},
        {"partial", "Move so PART of your face is out of the picture"},
    };

    /** Per-condition counts of a calibration run. */
    static final class ConditionStats {
        int frames, oneFace, usable;
        final Map<QualityIssue, Integer> issues = new EnumMap<QualityIssue, Integer>(QualityIssue.class);
        final List<double[]> metrics = new ArrayList<double[]>(); // lumaMean, lapVar, areaFrac, faceWidthPx, centreDx, centreDy

        void add(FrameAnalysis r, QualityReport q) {
            frames++;
            if (r.faceCount == 1) oneFace++;
            if (r.usable()) usable++;
            for (QualityIssue i : r.qualityIssues) {
                Integer n = issues.get(i);
                issues.put(i, n == null ? 1 : n + 1);
            }
            if (q != null) metrics.add(new double[] {q.lumaMean, q.lapVar, q.areaFrac, q.faceWidthPx, q.centreDx, q.centreDy});
        }
    }

    static void calibrate(int camera) throws Exception {
        final OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory();
        OnnxFaceAnalyzer analyzer = (OnnxFaceAnalyzer) factory.create();
        QualityAssessor quality = new QualityAssessor(AnalyzerConfig.defaults());
        final DemoWindow w = new DemoWindow("Quality calibration");
        final WebcamFrameSource source = new WebcamFrameSource(camera, 640, 480, 15);
        final LiveLoop loop = new LiveLoop();
        final AtomicReference<String> keyPressed = new AtomicReference<String>();
        w.key("SPACE", new Runnable() { @Override public void run() { keyPressed.set("SPACE"); } });
        w.key("S", new Runnable() { @Override public void run() { keyPressed.set("S"); } });
        w.onClose(new Runnable() { @Override public void run() { loop.running = false; } });
        w.show();
        source.start(loop.listener(w));
        Map<String, ConditionStats> results = new LinkedHashMap<String, ConditionStats>();
        for (String[] c : CONDITIONS) {
            if (!loop.running) break;
            keyPressed.set(null);
            w.message("NEXT: " + c[1] + "  (SPACE = start 6 s, S = skip)", new Color(200, 100, 0));
            while (loop.running && keyPressed.get() == null) {
                Frame f = loop.latest.getAndSet(null);
                if (f != null) {
                    FrameAnalysis r = analyzer.analyze(f);
                    w.video(DemoWindow.image(f), overlay(r));
                    w.info(describe(r, analyzer.stats(), Float.NaN, analyzer.lastReplayCues()));
                } else {
                    Thread.sleep(5);
                }
            }
            if (!"SPACE".equals(keyPressed.get())) continue;
            Toolkit.getDefaultToolkit().beep();
            ConditionStats st = new ConditionStats();
            long end = System.currentTimeMillis() + 6000;
            while (loop.running && System.currentTimeMillis() < end) {
                Frame f = loop.latest.getAndSet(null);
                if (f == null) {
                    Thread.sleep(5);
                    continue;
                }
                FrameAnalysis r = analyzer.analyze(f);
                QualityReport q = null;
                if (r.faceCount == 1 && r.faceBox != null) {
                    q = quality.assess(f.rgb(), f.width(), f.height(),
                            new FaceDetection(r.faceBox[0], r.faceBox[1], r.faceBox[2], r.faceBox[3], 1, new double[10]));
                }
                st.add(r, q);
                w.video(DemoWindow.image(f), overlay(r));
                w.message(String.format("RECORDING: %s  %.1f s", c[1], (end - System.currentTimeMillis()) / 1000.0), Color.RED);
                w.info(describe(r, analyzer.stats(), Float.NaN, analyzer.lastReplayCues()));
            }
            Toolkit.getDefaultToolkit().beep();
            results.put(c[0], st);
            System.out.println(row(c[0], st));
        }
        source.stop();
        String report = calibrationReport(results);
        System.out.print(report);
        File out = new File("liveness-eval/target/quality-calibration-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), report.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out);
        w.message("Done: thank you!", new Color(0, 120, 0));
        w.info(report);
        analyzer.close();
        factory.close();
        Thread.sleep(1500);
        System.exit(0);
    }

    static String row(String name, ConditionStats s) {
        return String.format("%-8s frames %3d  one face %5.1f%%  usable %5.1f%%  issues %s", name, s.frames,
                pct(s.oneFace, s.frames), pct(s.usable, s.frames), issuePct(s));
    }

    static String issuePct(ConditionStats s) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<QualityIssue, Integer> e : s.issues.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("%s %.0f%%", e.getKey(), pct(e.getValue(), s.frames)));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static double pct(int n, int of) {
        return of == 0 ? 0 : 100.0 * n / of;
    }

    static String calibrationReport(Map<String, ConditionStats> results) {
        StringBuilder sb = new StringBuilder("# Quality calibration (live webcam, one subject)\n\n");
        sb.append("| condition | frames | one face | usable() | issues (% of frames) | luma median | lapVar median (min) | area median | face width median |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, ConditionStats> e : results.entrySet()) {
            ConditionStats s = e.getValue();
            sb.append(String.format("| %s | %d | %.0f%% | %.0f%% | %s | %s | %s | %s | %s |%n", e.getKey(), s.frames,
                    pct(s.oneFace, s.frames), pct(s.usable, s.frames), issuePct(s),
                    med(s, 0, "%.0f"), med(s, 1, "%.0f") + " (" + min(s, 1, "%.0f") + ")", med(s, 2, "%.3f"), med(s, 3, "%.0f")));
        }
        return sb.toString();
    }

    private static String med(ConditionStats s, int k, String fmt) {
        if (s.metrics.isEmpty()) return "--";
        double[] v = new double[s.metrics.size()];
        for (int i = 0; i < v.length; i++) v[i] = s.metrics.get(i)[k];
        Arrays.sort(v);
        return String.format(fmt, v[v.length / 2]);
    }

    private static String min(ConditionStats s, int k, String fmt) {
        if (s.metrics.isEmpty()) return "--";
        double m = Double.MAX_VALUE;
        for (double[] v : s.metrics) m = Math.min(m, v[k]);
        return String.format(fmt, m);
    }

    /** Waits for one of the keys; null if the window closed. */
    static String awaitKey(AtomicReference<String> key, LiveLoop loop) throws InterruptedException {
        while (loop.running && key.get() == null) Thread.sleep(20);
        return key.getAndSet(null);
    }
}
