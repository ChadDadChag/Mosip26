package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.detect.FaceGeometry;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;

import java.awt.Color;
import java.awt.Toolkit;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Part 3 sessions ({@code DefaultLivenessService} with Part 3's bundled defaults and the real analyzer) on the
 * webcam, showing every event as the problem statement's on-screen text. {@code --mode session} runs one session at
 * a time (Retry / Cancel / New session buttons); {@code --mode series --plan a,b,...} runs scripted cases with a
 * prompt before each:
 * <pre>
 *   a  real face,                 RESIDENT
 *   b  real face,                 RESIDENT, forced challenges
 *   c  real face,                 OPERATOR, forced challenges
 *   d  printed photo,             RESIDENT
 *   e  phone showing a photo,     RESIDENT
 *   f  phone playing a video,     RESIDENT
 *   g  phone showing a photo,     RESIDENT, forced challenges (do nothing when asked)
 * </pre>
 * Forced challenges = a ConfigProvider override of {@code mosip.registration.liveness.face.default.passiveThreshold}
 * (default 0.9999, {@code --force-threshold}) so a real face cannot pass passively; padRejectThreshold stays 0.30.
 * Each run is logged (events, result, per-challenge timing and the analyzer features seen during each challenge) to
 * {@code liveness-eval/target/sessions-<time>.jsonl}; no images are stored.
 */
final class SessionDemo {

    static final String PASSIVE_KEY = "mosip.registration.liveness.face.default.passiveThreshold";

    private SessionDemo() { }

    /** One scripted case. */
    static final class Case {
        final String id, instruction;
        final WorkflowType workflow;
        final boolean forceActive;

        Case(String id, WorkflowType workflow, boolean forceActive, String instruction) {
            this.id = id;
            this.workflow = workflow;
            this.forceActive = forceActive;
            this.instruction = instruction;
        }
    }

    static Case scripted(char c) {
        switch (c) {
            case 'a': return new Case("a", WorkflowType.RESIDENT, false, "REAL FACE: just look at the camera");
            case 'b': return new Case("b", WorkflowType.RESIDENT, true, "REAL FACE: follow the instructions on screen");
            case 'c': return new Case("c", WorkflowType.OPERATOR, true, "REAL FACE (operator): follow the instruction");
            case 'd': return new Case("d", WorkflowType.RESIDENT, false, "PRINTED PHOTO in front of the camera, your face out of view");
            case 'e': return new Case("e", WorkflowType.RESIDENT, false, "PHONE PHOTO in front of the camera, your face out of view");
            case 'f': return new Case("f", WorkflowType.RESIDENT, false, "PHONE VIDEO playing in front of the camera, your face out of view");
            case 'g': return new Case("g", WorkflowType.RESIDENT, true, "PHONE PHOTO, forced challenges: hold it still, do NOTHING when asked");
            default: throw new IllegalArgumentException("unknown case '" + c + "' (a..g)");
        }
    }

    /** One analysed frame's features, as seen by the engine. */
    static final class Sample {
        final long t;
        final int faces;
        final float score, yaw, pitch, ear, mouthWidth, mouthOpen, cornerLift;

        Sample(long t, FrameAnalysis a) {
            this.t = t;
            this.faces = a.faceCount;
            this.score = a.livenessScore;
            this.yaw = a.yawDeg;
            this.pitch = a.pitchDeg;
            this.ear = FaceGeometry.meanEar(a.landmarks);
            this.mouthWidth = FaceGeometry.mouthWidthRatio(a.landmarks);
            this.mouthOpen = FaceGeometry.mouthOpenRatio(a.landmarks);
            this.cornerLift = FaceGeometry.cornerLift(a.landmarks);
        }
    }

    /** Wraps the real factory so every analysis inside the engine is also recorded (features only). */
    static final class RecordingFactory implements FaceAnalyzerFactory {
        private final FaceAnalyzerFactory delegate;
        final List<Sample> samples = Collections.synchronizedList(new ArrayList<Sample>());
        volatile long t0;

        RecordingFactory(FaceAnalyzerFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public FaceAnalyzer create() throws LivenessEngineException {
            final FaceAnalyzer a = delegate.create();
            return new FaceAnalyzer() {
                @Override public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
                    FrameAnalysis r = a.analyze(frame);
                    samples.add(new Sample(System.currentTimeMillis() - t0, r));
                    return r;
                }

                @Override public String modelVersion() {
                    return a.modelVersion();
                }

                @Override public void close() {
                    a.close();
                }
            };
        }
    }

    /** Collects a run's events, timing and result. */
    static final class RunLog implements LivenessListener {
        final long t0 = System.currentTimeMillis();
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final List<long[]> challengeTimes = Collections.synchronizedList(new ArrayList<long[]>()); // start, end, detected(1/0)
        final List<ChallengeType> challenges = Collections.synchronizedList(new ArrayList<ChallengeType>());
        final CountDownLatch done = new CountDownLatch(1);
        final DemoWindow w;
        final boolean autoRetry;
        volatile LivenessSession session;
        volatile LivenessResult result;
        private final Timer timer = new Timer(true);

        RunLog(DemoWindow w, boolean autoRetry) {
            this.w = w;
            this.autoRetry = autoRetry;
        }

        long now() {
            return System.currentTimeMillis() - t0;
        }

        @Override
        public void onEvent(LivenessEvent e) {
            long t = now();
            String d = e.type.name();
            if (e.type == LivenessEvent.Type.GUIDANCE) d += "(" + e.guidance + ")";
            if (e.type == LivenessEvent.Type.CHALLENGE_PROMPT) {
                d += "(" + e.challenge + " " + (e.challengeIndex + 1) + "/" + e.challengeCount + ")";
                closeChallenge(t, false);
                challenges.add(e.challenge);
                challengeTimes.add(new long[] {t, -1, 0});
            }
            if (e.type == LivenessEvent.Type.ACTION_DETECTED) closeChallenge(t, true);
            if (e.type == LivenessEvent.Type.ATTEMPT_FAILED || e.type == LivenessEvent.Type.FAILED_FINAL) {
                closeChallenge(t, false);
                d += "(" + e.error + (e.type == LivenessEvent.Type.ATTEMPT_FAILED ? ", retriesLeft " + e.retriesLeft : "") + ")";
            }
            if (e.type != LivenessEvent.Type.CHALLENGE_PROGRESS) events.add(t + "ms " + d);
            boolean bad = e.type == LivenessEvent.Type.ATTEMPT_FAILED || e.type == LivenessEvent.Type.FAILED_FINAL
                    || e.type == LivenessEvent.Type.DEVICE_ERROR;
            Color color = e.type == LivenessEvent.Type.PASSED ? new Color(0, 130, 0) : bad ? Color.RED : Color.BLACK;
            if (e.type != LivenessEvent.Type.CHALLENGE_PROGRESS || e.progress <= 0) w.message(UiTexts.forEvent(e), color);
            w.progress(e.type == LivenessEvent.Type.CHALLENGE_PROGRESS ? e.progress : -1);
            if (e.type == LivenessEvent.Type.CHALLENGE_PROMPT) Toolkit.getDefaultToolkit().beep();
            if (e.type == LivenessEvent.Type.ATTEMPT_FAILED) {
                w.info("Attempt failed: " + e.error + "\nretries left: " + e.retriesLeft
                        + (autoRetry ? "\n(retrying automatically)" : "\nPress Retry"));
                if (autoRetry) {
                    timer.schedule(new TimerTask() {
                        @Override public void run() {
                            LivenessSession s = session;
                            if (s != null) s.retry();
                        }
                    }, 2000);
                }
            }
        }

        private void closeChallenge(long t, boolean detected) {
            synchronized (challengeTimes) {
                if (challengeTimes.isEmpty()) return;
                long[] last = challengeTimes.get(challengeTimes.size() - 1);
                if (last[1] < 0) {
                    last[1] = t;
                    last[2] = detected ? 1 : 0;
                }
            }
        }

        @Override
        public void onComplete(LivenessResult r) {
            closeChallenge(now(), false);
            result = r;
            w.message(r.passed() ? "Good, face captured successfully" : UiTexts.failure(r.failureCode),
                    r.passed() ? new Color(0, 130, 0) : Color.RED);
            done.countDown();
            timer.cancel();
        }
    }

    static void run(ModelBundleTool.Args a, int camera) throws Exception {
        boolean series = "series".equals(a.single("--mode"));
        final String threshold = a.has("--force-threshold") ? a.single("--force-threshold") : "0.9999";
        List<Case> plan = new ArrayList<Case>();
        if (series) {
            for (String p : a.required("--plan").split(",")) plan.add(scripted(p.trim().toLowerCase(Locale.ROOT).charAt(0)));
        } else {
            WorkflowType wf = WorkflowType.valueOf(a.has("--workflow") ? a.single("--workflow") : "RESIDENT");
            plan.add(new Case("session", wf, a.has("--force-active"), "Look at the camera and follow the instructions"));
        }
        boolean autoRetry = series || a.has("--auto-retry");

        final OnnxFaceAnalyzerFactory real = new OnnxFaceAnalyzerFactory();
        real.preload();
        final DemoWindow w = new DemoWindow("Part 3 session with the Part 2 analyzer (" + real.modelVersion() + ")");
        final WebcamFrameSource source = new WebcamFrameSource(camera, 640, 480, 15);
        source.setPreviewListener(new WebcamFrameSource.PreviewListener() {
            @Override public void onPreview(Frame f) {
                w.video(DemoWindow.image(f), null);
            }
        });
        final AtomicReference<String> key = new AtomicReference<String>();
        final AtomicReference<RunLog> current = new AtomicReference<RunLog>();
        w.key("SPACE", new Runnable() { @Override public void run() { key.set("SPACE"); } });
        w.key("S", new Runnable() { @Override public void run() { key.set("S"); } });
        w.button("Retry", new Runnable() { @Override public void run() {
            RunLog r = current.get();
            if (r != null && r.session != null) r.session.retry();
        } });
        w.button("Cancel", new Runnable() { @Override public void run() {
            RunLog r = current.get();
            if (r != null && r.session != null) r.session.cancel();
        } });
        w.button("New session", new Runnable() { @Override public void run() { key.set("SPACE"); } });
        final LiveAnalyzerDemo.LiveLoop alive = new LiveAnalyzerDemo.LiveLoop();
        w.onClose(new Runnable() { @Override public void run() { alive.running = false; } });
        w.show();

        File logFile = new File("liveness-eval/target/sessions-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl");
        logFile.getParentFile().mkdirs();
        try (Writer log = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
            int n = 0;
            for (int i = 0; i < plan.size() && alive.running; i++) {
                Case c = plan.get(i);
                n++;
                key.set(null);
                w.message("NEXT " + c.id + n + ": " + c.instruction + "  (SPACE = start, S = skip)", new Color(200, 100, 0));
                w.info("Run " + n + " of " + plan.size() + "\nworkflow " + c.workflow + (c.forceActive ? ", forced challenges" : ""));
                String k = LiveAnalyzerDemo.awaitKey(key, alive);
                if (k == null) break;
                if (k.equals("S")) continue;
                RecordingFactory rf = new RecordingFactory(real);
                ConfigProvider cfg = c.forceActive ? forcedConfig(threshold) : new ConfigProvider() {
                    @Override public String get(String keyName) { return null; }
                };
                DefaultLivenessService service = DefaultLivenessService.builder().config(cfg).analyzerFactory(rf).build();
                RunLog r = new RunLog(w, autoRetry);
                current.set(r);
                rf.t0 = r.t0;
                r.session = service.newSession(c.workflow);
                r.session.start(source, r);
                r.done.await(180, TimeUnit.SECONDS);
                if (r.result == null) {
                    r.session.cancel();
                    r.done.await(5, TimeUnit.SECONDS);
                }
                String line = summary(c, n, r, rf);
                System.out.println(line);
                log.write(json(c, n, r, rf));
                log.write('\n');
                log.flush();
                w.info(line.replace("; ", "\n"));
                if (!series) {
                    key.set(null);
                    w.message((r.result != null && r.result.passed() ? "PASSED" : "FAILED")
                            + ": SPACE / New session to run again", Color.DARK_GRAY);
                    plan.add(c); // session mode: repeat until the window is closed
                }
            }
        }
        source.stop();
        real.close();
        System.out.println("log: " + logFile);
        System.exit(0);
    }

    static ConfigProvider forcedConfig(final String threshold) {
        return new ConfigProvider() {
            @Override public String get(String key) {
                return PASSIVE_KEY.equals(key) ? threshold : null;
            }
        };
    }

    /** One line per run; challenge details include the features seen while each challenge was open. */
    static String summary(Case c, int n, RunLog r, RecordingFactory rf) {
        LivenessResult res = r.result;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("RUN %s%d %s%s: ", c.id, n, c.workflow, c.forceActive ? " forced" : ""));
        if (res == null) return sb.append("no result (timed out)").toString();
        sb.append(String.format("%s passPath %s failure %s passiveScore %.4f attempts %d duration %d ms challenges %s; ",
                res.decision, res.passPath, res.failureCode, res.passiveScore, res.attempts, res.durationMs, res.challengesIssued));
        synchronized (r.challengeTimes) {
            for (int i = 0; i < r.challengeTimes.size(); i++) {
                long[] ct = r.challengeTimes.get(i);
                sb.append(String.format("%s %s in %d ms %s; ", r.challenges.get(i), ct[2] == 1 ? "DETECTED" : "not detected",
                        ct[1] - ct[0], features(rf, ct[0], ct[1])));
            }
        }
        sb.append("events ").append(String.join(" > ", new ArrayList<String>(r.events)));
        return sb.toString();
    }

    /** Ranges of the engine-visible features between two times. */
    static String features(RecordingFactory rf, long from, long to) {
        float[] ear = range(), yaw = range(), pitch = range(), mw = range(), lift = range(), score = range();
        int count = 0;
        synchronized (rf.samples) {
            for (Sample s : rf.samples) {
                if (s.t < from || s.t > to) continue;
                count++;
                extend(ear, s.ear);
                extend(yaw, s.yaw);
                extend(pitch, s.pitch);
                extend(mw, s.mouthWidth);
                extend(lift, s.cornerLift);
                extend(score, s.score);
            }
        }
        return String.format("[%d frames: EAR %s yaw %s pitch %s mouthW %s lift %s score %s]", count, fmt(ear), fmt(yaw),
                fmt(pitch), fmt(mw), fmt(lift), fmt(score));
    }

    private static float[] range() {
        return new float[] {Float.NaN, Float.NaN};
    }

    private static void extend(float[] r, float v) {
        if (Float.isNaN(v)) return;
        r[0] = Float.isNaN(r[0]) ? v : Math.min(r[0], v);
        r[1] = Float.isNaN(r[1]) ? v : Math.max(r[1], v);
    }

    private static String fmt(float[] r) {
        return Float.isNaN(r[0]) ? "--" : String.format("%.3f..%.3f", r[0], r[1]);
    }

    static String json(Case c, int n, RunLog r, RecordingFactory rf) {
        LivenessResult res = r.result;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"case\":\"").append(c.id).append(n).append("\",\"workflow\":\"").append(c.workflow)
                .append("\",\"forced\":").append(c.forceActive);
        if (res != null) {
            sb.append(",\"decision\":\"").append(res.decision).append("\",\"passPath\":\"").append(res.passPath)
                    .append("\",\"failureCode\":").append(res.failureCode == null ? "null" : "\"" + res.failureCode + "\"")
                    .append(",\"passiveScore\":").append(Float.isNaN(res.passiveScore) ? "null" : String.valueOf(res.passiveScore))
                    .append(",\"attempts\":").append(res.attempts).append(",\"durationMs\":").append(res.durationMs)
                    .append(",\"challenges\":\"").append(res.challengesIssued).append('"');
        }
        sb.append(",\"events\":[");
        List<String> ev = new ArrayList<String>(r.events);
        for (int i = 0; i < ev.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(ev.get(i)).append('"');
        sb.append("],\"samples\":").append(rf.samples.size()).append('}');
        return sb.toString();
    }
}
