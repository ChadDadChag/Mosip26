package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.EngineTuning;
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
 *   h  real face,                 RESIDENT, forced challenges, coached beforehand to turn clearly and come back
 *   p  phone photo ~10 cm away,   RESIDENT (the close-up the session moire cap must send to a challenge)
 *   q  as p, tilting the phone to fake BLINK / SMILE (the pose-drift guard must hold)
 *   r  phone photo, normal distance, forced challenges, tilting to fake BLINK / SMILE
 *   m  real face plus one printed cut-out beside it, RESIDENT (problem statement section 9: multiple faces)
 *   n  two printed cut-outs side by side,       RESIDENT
 *   o  a printed group photo (run it once per group photo), RESIDENT
 *   s  printed cut-out No. k ("s3" = cut-out 3), still, about 20 cm, RESIDENT (single-print retest)
 *   t  printed cut-out No. k, still, about 30 cm,                     RESIDENT
 *   u  the NASA A4 print, still, about 30 cm,                          RESIDENT
 *   v  printed cut-out No. k, slight natural hand motion, about 20 cm, RESIDENT
 *   w  printed cut-out No. k, about 20 cm, ROTATED to fake a turn, RESIDENT, forced, pool TURN_LEFT,TURN_RIGHT
 *   x  printed cut-out No. k, about 20 cm, TILTED to fake a blink or smile, RESIDENT, forced, pool BLINK,SMILE
 *   i  real face, close (face about a third of the picture), RESIDENT
 *   j  real face, far (about 1 m),                            RESIDENT
 *   k  real face, approaching the camera slowly during the check, RESIDENT
 *   l  real face, moving the head gently,                     RESIDENT
 * </pre>
 * The multi-face cases expect GUIDANCE(MULTIPLE_FACES), then each attempt failing with MULTIPLE_FACES and the session
 * with MAX_RETRIES_EXCEEDED, never PASSED. A second face counts only from 20% of the largest face's area
 * ({@code detectorSecondFaceMinAreaRatio}), hence the cut-out held closer than the live face in case m.
 * Forced challenges = a ConfigProvider override of {@code mosip.registration.liveness.face.default.passiveThreshold}
 * (default 0.9999, {@code --force-threshold}) so a real face cannot pass passively; padRejectThreshold stays 0.30.
 * {@code --challenge-types TURN_LEFT,TURN_RIGHT,BLINK} narrows the forced runs' challenge pool (for a turn retest);
 * cases w and x bring their own pool.
 * {@code --config <full key>=<value>} (repeatable) overrides an engine setting for every case, for example
 * {@code mosip.registration.liveness.face.default.padRejectThreshold=0.0}; {@code --set <analyzer setting>=<value>}
 * (repeatable) changes an analyzer setting, as in PadEvaluation, for example {@code moireCapThreshold=off}. Each
 * challenge line gives the pose drift the tilt guard sees ({@link #drift}).
 * Each run is logged (events, result, per-challenge timing and every analysed frame's features: pose, face size, the
 * score, both PAD models' live probabilities, moire, its session median and cap state, the volatility cap and PAD
 * latch flags, quality issues) to
 * {@code <--log-dir, default liveness-eval/target>/sessions-<time>.jsonl}; no images are stored.
 */
final class SessionDemo {

    static final String PASSIVE_KEY = "mosip.registration.liveness.face.default.passiveThreshold";
    static final String TYPES_KEY = "mosip.registration.liveness.face.default.challengeTypes";

    private SessionDemo() { }

    /** One scripted case. */
    static final class Case {
        final String id, instruction;
        final WorkflowType workflow;
        final boolean forceActive;
        /** The numbered item presented (a cut-out's number), 0 if none. */
        final int item;
        /** The forced runs' challenge pool for this case, null = {@code --challenge-types} or the default pool. */
        final String types;

        Case(String id, WorkflowType workflow, boolean forceActive, String instruction) {
            this(id, workflow, forceActive, instruction, 0);
        }

        Case(String id, WorkflowType workflow, boolean forceActive, String instruction, int item) {
            this(id, workflow, forceActive, instruction, item, null);
        }

        Case(String id, WorkflowType workflow, boolean forceActive, String instruction, int item, String types) {
            this.id = id;
            this.workflow = workflow;
            this.forceActive = forceActive;
            this.instruction = instruction;
            this.item = item;
            this.types = types;
        }
    }

    /** A plan entry: the case letter, then the item number for the cut-out cases ("s3"). */
    static Case scripted(String token) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) throw new IllegalArgumentException("empty plan entry");
        char c = t.charAt(0);
        boolean numbered = c == 's' || c == 't' || c == 'v' || c == 'w' || c == 'x';
        if (numbered != t.length() > 1) {
            throw new IllegalArgumentException(numbered ? "case '" + c + "' needs the cut-out number, for example " + c + "3"
                    : "case '" + c + "' takes no number, got '" + token + "'");
        }
        int k = numbered ? Integer.parseInt(t.substring(1)) : 0;
        String still = ", your face out of view: hold it STILL and steady until FINISHED";
        switch (c) {
            case 's': return new Case("s", WorkflowType.RESIDENT, false,
                    "CUT-OUT " + k + " about 20 cm from the camera, face to the lens" + still, k);
            case 't': return new Case("t", WorkflowType.RESIDENT, false,
                    "CUT-OUT " + k + " about 30 cm from the camera, face to the lens" + still, k);
            case 'u': return new Case("u", WorkflowType.RESIDENT, false,
                    "NASA A4 PRINT about 30 cm from the camera, face to the lens" + still);
            case 'v': return new Case("v", WorkflowType.RESIDENT, false, "CUT-OUT " + k + " about 20 cm from the camera,"
                    + " face to the lens, your face out of view: hold it in your hand with SLIGHT NATURAL MOTION (do not"
                    + " shake it) until FINISHED", k);
            case 'w': return new Case("w", WorkflowType.RESIDENT, true, "CUT-OUT " + k + " about 20 cm from the camera,"
                    + " your face out of view: when asked to TURN, ROTATE the cut-out to that side (as the person would turn"
                    + " their head) and back; until FINISHED", k, "TURN_LEFT,TURN_RIGHT");
            case 'x': return new Case("x", WorkflowType.RESIDENT, true, "CUT-OUT " + k + " about 20 cm from the camera,"
                    + " your face out of view: when asked to BLINK or SMILE, TILT the cut-out (top edge toward you and"
                    + " back); until FINISHED", k, "BLINK,SMILE");
            default: return scripted(c);
        }
    }

    static Case scripted(char c) {
        switch (c) {
            case 'a': return new Case("a", WorkflowType.RESIDENT, false, "REAL FACE: just look at the camera");
            case 'b': return new Case("b", WorkflowType.RESIDENT, true, "REAL FACE: follow the instructions on screen");
            case 'c': return new Case("c", WorkflowType.OPERATOR, true, "REAL FACE (operator): follow the instruction");
            case 'd': return new Case("d", WorkflowType.RESIDENT, false, "PRINTED PHOTO in front of the camera, your face out of view");
            case 'e': return new Case("e", WorkflowType.RESIDENT, false,
                    "PHONE PHOTO in front of the camera, your face out of view, until the run says FINISHED");
            case 'f': return new Case("f", WorkflowType.RESIDENT, false, "PHONE VIDEO playing in front of the camera, your face out of view");
            case 'g': return new Case("g", WorkflowType.RESIDENT, true, "PHONE PHOTO, forced challenges: hold it still, do NOTHING when asked");
            case 'h': return new Case("h", WorkflowType.RESIDENT, true,
                    "REAL FACE: when asked to turn, turn clearly (about 30 deg) and then come back to face the camera");
            case 'p': return new Case("p", WorkflowType.RESIDENT, false,
                    "PHONE PHOTO about 10 cm from the camera (face fills the picture), your face out of view: hold still until FINISHED");
            case 'q': return new Case("q", WorkflowType.RESIDENT, false,
                    "PHONE PHOTO about 10 cm away, your face out of view: when asked to BLINK or SMILE, TILT the phone to fake it");
            case 'r': return new Case("r", WorkflowType.RESIDENT, true,
                    "PHONE PHOTO at normal distance, forced challenges: when asked to BLINK or SMILE, TILT the phone to fake it");
            case 'm': return new Case("m", WorkflowType.RESIDENT, false,
                    "MULTI-FACE: YOUR FACE plus ONE CUT-OUT beside it, the cut-out about 30 cm from the camera (at least half"
                            + " as tall as your face): hold until FINISHED");
            case 'n': return new Case("n", WorkflowType.RESIDENT, false,
                    "MULTI-FACE: TWO CUT-OUTS side by side about 30 cm from the camera, your face out of view: hold until FINISHED");
            case 'o': return new Case("o", WorkflowType.RESIDENT, false,
                    "MULTI-FACE: a GROUP PHOTO print (several faces) about 30 cm from the camera, your face out of view: hold until"
                            + " FINISHED");
            case 'i': return new Case("i", WorkflowType.RESIDENT, false, "REAL FACE, CLOSE: lean in until your face fills"
                    + " about a third of the picture (whole face visible, chin to hair), look at the camera until FINISHED");
            case 'j': return new Case("j", WorkflowType.RESIDENT, false,
                    "REAL FACE, FAR: sit back about 1 m from the camera, look at it until FINISHED");
            case 'k': return new Case("k", WorkflowType.RESIDENT, false, "REAL FACE, APPROACHING: start about 1 m back;"
                    + " after SPACE move SLOWLY toward the camera, looking at it, until FINISHED");
            case 'l': return new Case("l", WorkflowType.RESIDENT, false, "REAL FACE, MOVING: look at the camera and move"
                    + " your head gently (small turns and nods) until FINISHED");
            default: throw new IllegalArgumentException("unknown case '" + c
                    + "' (a..h, i, j, k, l, m, n, o, p, q, r, s1, t1, u, v1, w1, x1)");
        }
    }

    /** One analysed frame's features, as seen by the engine. */
    static final class Sample {
        final long t;
        final int faces;
        final float score, yaw, pitch, ear, mouthWidth, mouthOpen, cornerLift, boxWidth;
        /**
         * Roll, face box area / frame area, the two PAD models' live probabilities, the moire cap state and the print
         * guards' flags.
         */
        final float roll, areaFrac, v2Live, v1seLive, moire, moireMed, moireCap, volCap, padLatch;
        /** Quality issues, space separated. */
        final String issues;

        Sample(long t, FrameAnalysis a) {
            this.t = t;
            this.faces = a.faceCount;
            this.boxWidth = a.faceBox == null ? Float.NaN : a.faceBox[2];
            this.score = a.livenessScore;
            this.yaw = a.yawDeg;
            this.pitch = a.pitchDeg;
            this.ear = FaceGeometry.meanEar(a.landmarks);
            this.mouthWidth = FaceGeometry.mouthWidthRatio(a.landmarks);
            this.mouthOpen = FaceGeometry.mouthOpenRatio(a.landmarks);
            this.cornerLift = FaceGeometry.cornerLift(a.landmarks);
            this.roll = a.rollDeg;
            this.areaFrac = a.faceBox == null || a.frame == null ? Float.NaN
                    : a.faceBox[2] * a.faceBox[3] / ((float) a.frame.width() * a.frame.height());
            this.v2Live = detail(a, "minifas_v2_live");
            this.v1seLive = detail(a, "minifas_v1se_live");
            this.moire = detail(a, "moire");
            this.moireMed = detail(a, "moire_med");
            this.moireCap = detail(a, "moire_cap");
            this.volCap = detail(a, "volatility_cap");
            this.padLatch = detail(a, "pad_latch");
            StringBuilder b = new StringBuilder();
            for (QualityIssue q : a.qualityIssues) b.append(b.length() > 0 ? " " : "").append(q);
            this.issues = b.toString();
        }

        private static float detail(FrameAnalysis a, String key) {
            Float v = a.padDetails.get(key);
            return v == null ? Float.NaN : v;
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
                d += "(" + e.challenge + " " + e.challengeIndex + "/" + e.challengeCount + ")"; // index is 1-based
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
                        + (autoRetry ? "\n\nRETRYING IN 2 s:\nkeep presenting the same thing\nuntil the run says FINISHED" : "\nPress Retry"));
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
            w.message((r.passed() ? "Good, face captured successfully" : UiTexts.failure(r.failureCode)) + "  (run FINISHED)",
                    r.passed() ? new Color(0, 130, 0) : Color.RED);
            done.countDown();
            timer.cancel();
        }
    }

    static void run(ModelBundleTool.Args a, int camera) throws Exception {
        boolean series = "series".equals(a.single("--mode"));
        final String threshold = a.has("--force-threshold") ? a.single("--force-threshold") : "0.9999";
        final String types = a.has("--challenge-types") ? a.single("--challenge-types") : null;
        List<Case> plan = new ArrayList<Case>();
        if (series) {
            for (String p : a.required("--plan").split(",")) plan.add(scripted(p));
        } else {
            WorkflowType wf = WorkflowType.valueOf(a.has("--workflow") ? a.single("--workflow") : "RESIDENT");
            plan.add(new Case("session", wf, a.has("--force-active"), "Look at the camera and follow the instructions"));
        }
        boolean autoRetry = series || a.has("--auto-retry");
        final ConfigProvider overrides = overrides(a.all("--config"));
        if (!a.all("--config").isEmpty()) System.out.println("engine config overrides: " + a.all("--config"));
        if (!a.all("--set").isEmpty()) System.out.println("analyzer settings: " + a.all("--set"));

        final OnnxFaceAnalyzerFactory real = new OnnxFaceAnalyzerFactory(new ClasspathBundleSource(),
                PadEvaluation.analyzerConfig(a.all("--set")));
        real.preload();
        final DemoWindow w = new DemoWindow("Part 3 session with the Part 2 analyzer (" + real.modelVersion() + ")");
        w.mirrored(true); // selfie view, so "turn left" matches what the person sees
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

        File logFile = new File(a.has("--log-dir") ? a.single("--log-dir") : "liveness-eval/target", "sessions-"
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
                String pool = c.types != null ? c.types : types;
                final ConfigProvider base = c.forceActive ? forcedConfig(threshold, pool) : new ConfigProvider() {
                    @Override public String get(String keyName) { return null; }
                };
                ConfigProvider cfg = new ConfigProvider() {
                    @Override public String get(String keyName) {
                        String v = overrides.get(keyName);
                        return v != null ? v : base.get(keyName);
                    }
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

    /** {@code --config <full key>=<value>} overrides, for every case and above the forced settings. */
    static ConfigProvider overrides(List<String> pairs) {
        final java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--config needs key=value, got '" + p + "'");
            m.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return new ConfigProvider() {
            @Override public String get(String key) {
                return m.get(key);
            }
        };
    }

    /** The passive threshold override, and the challenge pool override when types is not null. */
    static ConfigProvider forcedConfig(final String threshold, final String types) {
        return new ConfigProvider() {
            @Override public String get(String key) {
                if (PASSIVE_KEY.equals(key)) return threshold;
                return TYPES_KEY.equals(key) ? types : null;
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
        float limit = new EngineTuning().actionMaxPoseDriftDeg;
        synchronized (r.challengeTimes) {
            for (int i = 0; i < r.challengeTimes.size(); i++) {
                long[] ct = r.challengeTimes.get(i);
                long end = ct[1] < 0 ? Long.MAX_VALUE : ct[1];
                sb.append(String.format("%s %s in %d ms %s %s; ", r.challenges.get(i), ct[2] == 1 ? "DETECTED" : "not detected",
                        ct[1] - ct[0], features(rf, ct[0], end), drift(rf, ct[0], end, limit)));
            }
        }
        sb.append("events ").append(String.join(" > ", new ArrayList<String>(r.events)));
        return sb.toString();
    }

    /** The analysis rate's look-back for the drift baseline: the frames this long before a prompt. */
    static final long DRIFT_BASELINE_MS = 600;

    /**
     * The tilt guard's view of one challenge (P3-3): the pose drift of each frame from a baseline, the median yaw and
     * pitch of the frames in the {@value #DRIFT_BASELINE_MS} ms before the prompt (an approximation of the engine's
     * baseline, which is not exposed); the frames over the guard's limit (the detectors ignore them), and the
     * excursions over it (each one cancels a closure or smile in progress).
     */
    static String drift(RecordingFactory rf, long prompt, long end, float limitDeg) {
        List<Float> by = new ArrayList<Float>(), bp = new ArrayList<Float>();
        List<float[]> during = new ArrayList<float[]>();
        synchronized (rf.samples) {
            for (Sample s : rf.samples) {
                if (s.faces != 1) continue;
                if (s.t >= prompt - DRIFT_BASELINE_MS && s.t < prompt) {
                    if (!Float.isNaN(s.yaw)) by.add(s.yaw);
                    if (!Float.isNaN(s.pitch)) bp.add(s.pitch);
                } else if (s.t >= prompt && s.t <= end) {
                    during.add(new float[] {s.yaw, s.pitch});
                }
            }
        }
        if (by.isEmpty() || bp.isEmpty()) return "[drift: no baseline frames]";
        float yaw0 = median(by), pitch0 = median(bp), max = 0;
        int over = 0, excursions = 0, counted = 0;
        boolean wasOver = false;
        for (float[] p : during) {
            float dy = Math.abs(p[0] - yaw0), dp = Math.abs(p[1] - pitch0);
            float d = Float.isNaN(dy) ? dp : Float.isNaN(dp) ? dy : Math.max(dy, dp);
            if (Float.isNaN(d)) continue;
            counted++;
            max = Math.max(max, d);
            boolean isOver = d > limitDeg;
            if (isOver) over++;
            if (isOver && !wasOver) excursions++;
            wasOver = isOver;
        }
        return String.format(Locale.ROOT, "[drift max %.1f deg; %d of %d frames over %.0f deg; %d excursions]", max,
                over, counted, limitDeg, excursions);
    }

    private static float median(List<Float> v) {
        List<Float> s = new ArrayList<Float>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2f;
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

    private static String num(float v) {
        return Float.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.3f", v);
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
                .append("\",\"forced\":").append(c.forceActive).append(",\"item\":").append(c.item);
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
        // Every frame the engine analysed (numbers and codes only), fields as named in "frameFields".
        sb.append("],\"frameFields\":[\"t\",\"faces\",\"yaw\",\"pitch\",\"ear\",\"score\",\"boxWidth\",\"roll\",\"areaFrac\","
                + "\"v2Live\",\"v1seLive\",\"moire\",\"moireMed\",\"moireCap\",\"volCap\",\"padLatch\",\"issues\"],\"frames\":[");
        synchronized (rf.samples) {
            for (int i = 0; i < rf.samples.size(); i++) {
                Sample s = rf.samples.get(i);
                sb.append(i > 0 ? "," : "").append('[').append(s.t).append(',').append(s.faces).append(',')
                        .append(num(s.yaw)).append(',').append(num(s.pitch)).append(',').append(num(s.ear)).append(',')
                        .append(num(s.score)).append(',').append(num(s.boxWidth)).append(',').append(num(s.roll))
                        .append(',').append(num(s.areaFrac)).append(',').append(num(s.v2Live)).append(',')
                        .append(num(s.v1seLive)).append(',').append(num(s.moire)).append(',').append(num(s.moireMed))
                        .append(',').append(num(s.moireCap)).append(',').append(num(s.volCap)).append(',')
                        .append(num(s.padLatch)).append(",\"").append(s.issues).append("\"]");
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
