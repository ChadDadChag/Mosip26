package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.tools.Trace;
import io.mosip.registration.liveness.tools.TraceRecorder;

import java.awt.Color;
import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guided Part 3 trace takes (development only, WITH the person's consent): for each label and take it runs
 * {@link TraceRecorder#record} (Part 3's recorder: features only, nothing image-like) on the webcam with the real
 * analyzer, shows the live preview and turns its console cues into big on-screen prompts and a beep.
 * <pre>
 *   LiveAnalyzerDemo --mode traces --consent [--subject p01] [--labels blink,smile,...] [--takes 2]
 *                    [--out liveness-eval/traces]
 * </pre>
 * Files are named {@code <subject>-<label>-<take>.jsonl}; subjects are codes, never names.
 */
final class TraceCapture {

    static final List<String> DEFAULT_LABELS = Arrays.asList("blink", "smile", "turn_left", "turn_right", "look_up",
            "look_down", "neutral", "photo_attack", "screen_attack");

    static final Map<String, String> INSTRUCTIONS = new LinkedHashMap<String, String>();

    static {
        INSTRUCTIONS.put("blink", "BLINK once or twice");
        INSTRUCTIONS.put("smile", "SMILE");
        INSTRUCTIONS.put("turn_left", "TURN your head to YOUR LEFT (~30 deg), hold 1 s, then back");
        INSTRUCTIONS.put("turn_right", "TURN your head to YOUR RIGHT (~30 deg), hold 1 s, then back");
        INSTRUCTIONS.put("look_up", "LOOK UP, hold 1 s, then back");
        INSTRUCTIONS.put("look_down", "LOOK DOWN, hold 1 s, then back");
        INSTRUCTIONS.put("neutral", "keep looking at the camera, do nothing");
        INSTRUCTIONS.put("photo_attack", "hold a PHOTO of a face (printed, or on a phone) still; your face out of view");
        INSTRUCTIONS.put("screen_attack", "hold a PHONE playing a face VIDEO; your face out of view");
    }

    private TraceCapture() { }

    static void run(ModelBundleTool.Args a, int camera) throws Exception {
        if (!a.has("--consent")) {
            System.err.println("REFUSED: traces are derived from a person; pass --consent once they have agreed.");
            System.exit(1);
        }
        String subject = a.has("--subject") ? a.single("--subject") : "p01";
        if (!subject.matches("^p\\d{2}$")) throw new IllegalArgumentException("--subject must be a code like p01 (no names)");
        List<String> labels = a.has("--labels") ? Arrays.asList(a.single("--labels").split(",")) : DEFAULT_LABELS;
        int takes = Integer.parseInt(a.has("--takes") ? a.single("--takes") : "2");
        File out = new File(a.has("--out") ? a.single("--out") : "liveness-eval/traces");
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);

        OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory();
        FaceAnalyzer analyzer = factory.create();
        final DemoWindow w = new DemoWindow("Trace capture for Part 3 (" + factory.modelVersion() + ")");
        w.mirrored(true); // selfie view
        final WebcamFrameSource source = new WebcamFrameSource(camera, 640, 480, 15);
        source.setPreviewListener(new WebcamFrameSource.PreviewListener() {
            @Override public void onPreview(Frame f) {
                w.video(DemoWindow.image(f), null);
            }
        });
        final KeptOpen warm = new KeptOpen(source);
        final AtomicReference<String> key = new AtomicReference<String>();
        final LiveAnalyzerDemo.LiveLoop alive = new LiveAnalyzerDemo.LiveLoop();
        w.key("SPACE", new Runnable() { @Override public void run() { key.set("SPACE"); } });
        w.key("S", new Runnable() { @Override public void run() { key.set("S"); } });
        w.onClose(new Runnable() { @Override public void run() { alive.running = false; } });
        w.show();
        warm.open();
        int done = 0, total = labels.size() * takes;
        outer:
        for (String label : labels) {
            String what = INSTRUCTIONS.containsKey(label) ? INSTRUCTIONS.get(label) : label;
            for (int take = 1; take <= takes; take++) {
                key.set(null);
                w.message("NEXT: " + what + "  (SPACE = start, S = skip)", new Color(200, 100, 0));
                w.info("take " + (done + 1) + " of " + total + "\nlabel " + label + " #" + take
                        + "\n\nAfter SPACE: keep still until the beep,\nthen " + what + ".\nThe take lasts 6 s.");
                String k = LiveAnalyzerDemo.awaitKey(key, alive);
                if (k == null) break outer;
                done++;
                if (k.equals("S")) continue;
                PrintStream console = new PrintStream(new CueStream(w, what), true, "UTF-8");
                Trace t = TraceRecorder.record(warm, analyzer, label, 2000, 6000, 15, console);
                File f = new File(out, subject + "-" + label + "-" + take + ".jsonl");
                t.write(f);
                System.out.println("wrote " + f + " (" + t.frames.size() + " frames)");
                w.message("Saved " + label + " #" + take + " (" + t.frames.size() + " frames)", new Color(0, 130, 0));
                Thread.sleep(800);
            }
        }
        warm.close();
        analyzer.close();
        factory.close();
        w.message("All takes done: thank you!", new Color(0, 130, 0));
        Thread.sleep(1500);
        System.exit(0);
    }

    /**
     * Keeps the camera open across takes. TraceRecorder starts and stops its source on every take, and a cold webcam
     * open (0.8 to 2.5 s here) ate the pre-prompt frames the detectors' baseline needs. Here start() and stop() only
     * attach and detach the take's listener, so sequence numbers run on across takes (traces keep times, not seq).
     */
    static final class KeptOpen implements FrameSource {
        private final FrameSource camera;
        private volatile Listener current;
        private volatile String fatal;

        KeptOpen(FrameSource camera) {
            this.camera = camera;
        }

        void open() {
            camera.start(new Listener() {
                @Override public void onFrame(Frame frame) {
                    Listener l = current;
                    if (l != null) l.onFrame(frame);
                }

                @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                    if (!recoverable) fatal = code + ": " + detail;
                    Listener l = current;
                    if (l != null) l.onError(code, detail, recoverable);
                }
            });
        }

        void close() {
            current = null;
            camera.stop();
        }

        @Override public DeviceInfo info() { return camera.info(); }

        @Override public void start(Listener listener) {
            current = listener;
            String f = fatal; // a camera that already failed fails the take at once
            if (f != null) listener.onError(ErrorCode.CAMERA_UNAVAILABLE, f, false);
        }

        @Override public void stop() { current = null; }

        @Override public boolean isRunning() { return current != null && camera.isRunning(); }
    }

    /** Turns TraceRecorder's console lines into on-screen cues ("Get ready" / ">>> NOW") and a beep. */
    static final class CueStream extends OutputStream {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final DemoWindow w;
        private final String what;

        CueStream(DemoWindow w, String what) {
            this.w = w;
            this.what = what;
        }

        @Override public void write(int b) {
            if (b == '\n') {
                String s = new String(line.toByteArray(), StandardCharsets.UTF_8).trim();
                line.reset();
                if (s.startsWith(">>> NOW")) {
                    Toolkit.getDefaultToolkit().beep();
                    w.message("NOW: " + what, Color.RED);
                } else if (s.startsWith("Get ready")) {
                    w.message("Get ready: look at the camera and keep still…", Color.BLACK);
                } else if (!s.isEmpty()) {
                    System.out.println(s);
                }
            } else {
                line.write(b);
            }
        }
    }
}
