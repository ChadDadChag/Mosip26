package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guided PAD dataset session (development only, WITH the subject's consent; {@code docs/part2-dataset-protocol.md}):
 * walks through the protocol's takes with one-line prompts and a countdown beep, records each with
 * {@link DatasetRecorder} while the camera stays open, and prints the takes per species at the end.
 * <pre>
 *   LiveAnalyzerDemo --mode dataset --consent [--subject s01] [--only species-or-condition,...] [--root eval-data]
 * </pre>
 * Takes already on disk are skipped, so an interrupted session can simply be run again. A live readout (face size as a
 * share of the picture, whether the whole face is visible) comes from the analyzer on preview frames, in memory only.
 *
 * <p>Groups of extra steps run only when {@code --only} names them, outside the protocol layout so that
 * PadEvaluation never picks them up directly:
 * <ul>
 *   <li>{@code f16}: regression clips of the public-domain NASA portrait (print and phone) with NO person in view,
 *       5 s at 15 fps, to {@code <root>/f16-clips/<name>/}; packed with {@link ClipPacker} for RecordedVideoE2ETest;</li>
 *   <li>{@code f2_banding}: 3 s at 30 fps in lossless PNG, to {@code <root>/f2-banding/<subject>/<condition>/}, for
 *       the banding question of docs/part2-f2-screen-cue-design.md section 6.2;</li>
 *   <li>{@code slideshow} and {@code print_multi}: continuous recordings (3.5 and 4 minutes) of many consented people's
 *       photos, shown as a phone slideshow or held up as printed cut-outs, to {@code <root>/streams/<subject>/<condition>/};
 *       {@link SlideshowSegmenter} then cuts them into one take per presentation.</li>
 * </ul>
 */
final class DatasetSession {

    /** One planned take. */
    static final class Step {
        final String label, condition, prompt;
        final int take;
        /** null for protocol takes; else the extra group (f16, f2_banding, slideshow, print_multi). */
        final String group;
        /** Output folder under the root for extra steps (null: the protocol layout). */
        final String dir;
        final int seconds;
        final double fps;
        final String format;

        Step(String label, int take, String condition, String prompt) {
            this(label, take, condition, prompt, null, null, 6, 10, "jpg");
        }

        Step(String label, int take, String condition, String prompt, String group, String dir, int seconds, double fps,
             String format) {
            this.label = label; this.take = take; this.condition = condition; this.prompt = prompt;
            this.group = group; this.dir = dir; this.seconds = seconds; this.fps = fps; this.format = format;
        }

        DatasetRecorder.Take take(String subject, File root) {
            DatasetRecorder.Take t = new DatasetRecorder.Take(label, subject, take, seconds, fps, root, format, condition);
            return dir == null ? t : t.in(new File(root, dir.replace("<subject>", subject)));
        }
    }

    static final String F16 = "f16";
    static final String BANDING = "f2_banding";
    static final String SLIDESHOW = "slideshow";
    static final String PRINT_MULTI = "print_multi";
    static final String CUTOUTS_CURVED = "cutouts_curved";
    private static final String OUT_OF_VIEW = "; your own face OUT of view";
    private static final String NOBODY = "; ONLY the picture and your hand in view: your face, body and anyone else OUT"
            + " of view";

    /** The protocol's single-subject plan: 10 bona fide takes and 6 per screen species (prints come later). */
    static List<Step> plan() {
        List<Step> p = new ArrayList<Step>();
        add(p, "bona_fide", 1, 3, "normal", "YOUR FACE: sit normally and look at the camera");
        add(p, "bona_fide", 4, 2, "close", "YOUR FACE: lean CLOSE, your face fills a big part of the picture");
        add(p, "bona_fide", 6, 2, "far", "YOUR FACE: sit BACK, about half a metre further away than normal");
        add(p, "bona_fide", 8, 1, "dim", "YOUR FACE: DIM the room light, then look at the camera");
        add(p, "bona_fide", 9, 2, "moving", "YOUR FACE: move your head slowly left/right and up/down");
        add(p, "screen_photo", 1, 3, "selfie", "PHONE: your SELFIE PHOTO, normal distance, phone edges visible" + OUT_OF_VIEW);
        add(p, "screen_photo", 4, 3, "nasa", "PHONE: the NASA PORTRAIT, normal distance, phone edges visible" + OUT_OF_VIEW);
        add(p, "screen_photo_close", 1, 3, "selfie", "PHONE CLOSE: your SELFIE PHOTO, the face fills much of the picture" + OUT_OF_VIEW);
        add(p, "screen_photo_close", 4, 3, "nasa", "PHONE CLOSE: the NASA PORTRAIT, the face fills much of the picture" + OUT_OF_VIEW);
        add(p, "screen_replay", 1, 6, "selfie_video", "PHONE: PLAY your SELFIE VIDEO, normal distance, hand-held" + OUT_OF_VIEW);
        add(p, "screen_replay_monitor", 1, 4, "selfie_video",
                "OPTIONAL (S = skip): SECOND SCREEN playing your SELFIE VIDEO" + OUT_OF_VIEW);
        // F5 retake: takes 1-4 (a tablet at 40-60 cm) were all non-responses, the face too small (3.4% of the picture).
        // These hold the screen's face at real-face size, with the live readout.
        add(p, "screen_replay_monitor", 5, 4, "video_realsize", "OPTIONAL (S = skip): SECOND SCREEN / TABLET playing"
                + " your VIDEO: hold it so the face on the screen is AS BIG AS A REAL FACE (face size 20-35%)" + OUT_OF_VIEW);
        // Round 1 follow-up: the first close takes were too close (bona fide, face cut off) or not close enough
        // (phone, face about 4% of the picture); these use the live face-size readout.
        add(p, "bona_fide", 11, 2, "close_whole", "YOUR FACE: lean in until face size reads 25-40%, WHOLE face in the picture");
        add(p, "screen_photo_close", 7, 3, "selfie_fill",
                "PHONE VERY CLOSE (~10 cm): your SELFIE PHOTO, face size 20% or more" + OUT_OF_VIEW);
        add(p, "screen_photo_close", 10, 3, "nasa_fill",
                "PHONE VERY CLOSE (~10 cm): the NASA PORTRAIT, face size 20% or more" + OUT_OF_VIEW);
        // Prints, hand-held (S skips them until they exist): per picture, 2 at normal distance and 1 close.
        add(p, "print_flat", 1, 2, "selfie", "PRINT held FLAT, normal distance: your SELFIE printed in colour" + OUT_OF_VIEW);
        add(p, "print_flat", 3, 1, "selfie_close",
                "PRINT held FLAT, CLOSE: your SELFIE, face size 20% or more" + OUT_OF_VIEW);
        add(p, "print_flat", 4, 2, "nasa", "PRINT held FLAT, normal distance: the NASA PORTRAIT in colour" + OUT_OF_VIEW);
        add(p, "print_flat", 6, 1, "nasa_close",
                "PRINT held FLAT, CLOSE: the NASA PORTRAIT, face size 20% or more" + OUT_OF_VIEW);
        add(p, "print_curved", 1, 2, "selfie",
                "PRINT CURVED around your hand, normal distance: your SELFIE print" + OUT_OF_VIEW);
        add(p, "print_curved", 3, 1, "selfie_close",
                "PRINT CURVED around your hand, CLOSE: your SELFIE, face size 20% or more" + OUT_OF_VIEW);
        add(p, "print_curved", 4, 2, "nasa",
                "PRINT CURVED around your hand, normal distance: the NASA PORTRAIT print" + OUT_OF_VIEW);
        add(p, "print_curved", 6, 1, "nasa_close",
                "PRINT CURVED around your hand, CLOSE: the NASA PORTRAIT, face size 20% or more" + OUT_OF_VIEW);
        // F16 regression clips (committed after review, so NO person may appear): the NASA portrait only.
        clip(p, "print_flat", "nasa-print-normal", "F16 CLIP: NASA PRINT held flat, normal distance (about 50 cm)");
        clip(p, "print_flat", "nasa-print-close", "F16 CLIP: NASA PRINT held flat, CLOSE: face size 20% or more");
        clip(p, "screen_photo", "nasa-phone-normal",
                "F16 CLIP: PHONE showing the NASA PORTRAIT, normal distance, phone edges visible");
        clip(p, "screen_photo_close", "nasa-phone-close-fill",
                "F16 CLIP: PHONE VERY CLOSE (~10 cm) showing the NASA PORTRAIT, face size 20% or more");
        // F2 banding: lossless 30 fps, the screen filling the view with PWM dimming on (30%) and off (100%).
        banding(p, "screen_photo_close", 1, "nasa_fill_pwm30",
                "BANDING: PHONE at 30% BRIGHTNESS, VERY CLOSE (~10 cm), NASA PORTRAIT, face size 20% or more" + OUT_OF_VIEW);
        banding(p, "screen_photo_close", 2, "nasa_fill_100",
                "BANDING: PHONE at 100% BRIGHTNESS, VERY CLOSE (~10 cm), NASA PORTRAIT, face size 20% or more" + OUT_OF_VIEW);
        banding(p, "bona_fide", 3, "close_whole_30fps",
                "BANDING: YOUR FACE, lean in until face size reads 25-40%, WHOLE face in the picture");
        // Many identities: one continuous recording each, cut into presentations by SlideshowSegmenter.
        stream(p, SLIDESHOW, "screen_photo_multi", "slideshow", 210,
                "SLIDESHOW: PHONE at normal distance (about 50 cm), the PHOTO SLIDESHOW playing from the first photo,"
                        + " phone edges visible; hold it until the slideshow ends" + OUT_OF_VIEW);
        stream(p, SLIDESHOW, "screen_photo_multi", "slideshow_close", 210,
                "OPTIONAL (S = skip) SLIDESHOW CLOSE: PHONE about 15 cm away, the slideshow again from the first photo,"
                        + " face size 20% or more" + OUT_OF_VIEW);
        stream(p, PRINT_MULTI, "print_multi", "flat", 240,
                "CUT-OUTS FLAT: each cut-out in turn held FLAT about 20 cm from the camera (face size about 10%), twice"
                        + " for 5 s, taking it out of view for 1 s between" + OUT_OF_VIEW);
        stream(p, PRINT_MULTI, "print_multi", "curved", 240,
                "CUT-OUTS CURVED: each cut-out in turn CURVED around your hand, about 20 cm from the camera, twice for"
                        + " 5 s, taking it out of view for 1 s between" + OUT_OF_VIEW);
        // The same, one short recording per cut-out (SPACE before each), segmented the same way.
        for (int k = 1; k <= 12; k++) {
            String condition = String.format("curved_%02d", k);
            p.add(new Step("print_multi", 1, condition, "CUT-OUT " + k + " of 12, CURVED: bend it gently, about 20 cm from"
                    + " the camera, face to the lens: 5 s, out of view 1 s, 5 s again" + OUT_OF_VIEW, CUTOUTS_CURVED,
                    "streams/<subject>/" + condition, 13, 10, "jpg"));
        }
        return p;
    }

    private static void stream(List<Step> p, String group, String label, String condition, int seconds, String prompt) {
        p.add(new Step(label, 1, condition, prompt, group, "streams/<subject>/" + condition, seconds, 10, "jpg"));
    }

    private static void add(List<Step> p, String label, int firstTake, int n, String condition, String prompt) {
        for (int i = 0; i < n; i++) p.add(new Step(label, firstTake + i, condition, prompt));
    }

    private static void clip(List<Step> p, String label, String name, String prompt) {
        p.add(new Step(label, 1, name.replace('-', '_'), prompt + NOBODY, F16, "f16-clips/" + name, 5, 15, "jpg"));
    }

    private static void banding(List<Step> p, String label, int take, String condition, String prompt) {
        p.add(new Step(label, take, condition, prompt, BANDING, "f2-banding/<subject>/" + condition, 3, 30, "png"));
    }

    /**
     * The steps {@code --only} selects: protocol steps by label or condition (all of them without {@code --only});
     * extra steps only when their group, label-independent, is named.
     */
    static List<Step> select(List<Step> plan, List<String> only) {
        List<Step> out = new ArrayList<Step>();
        for (Step s : plan) {
            boolean pick = s.group == null
                    ? only == null || only.contains(s.label) || only.contains(s.condition)
                    : only != null && only.contains(s.group);
            if (pick) out.add(s);
        }
        return out;
    }

    /** The camera rate for the selected steps: 15 fps as before, faster when a step records faster than 10 fps. */
    static double cameraFps(List<Step> steps) {
        double fps = 10;
        for (Step s : steps) fps = Math.max(fps, s.fps);
        return Math.min(30, Math.max(15, fps * 1.5));
    }

    private DatasetSession() { }

    static void run(ModelBundleTool.Args a, int camera) throws Exception {
        if (!a.has("--consent")) {
            System.err.println("REFUSED: recording a person requires their consent; pass --consent once they have agreed.");
            System.exit(1);
        }
        String subject = a.has("--subject") ? a.single("--subject") : "s01";
        File root = new File(a.has("--root") ? a.single("--root") : "eval-data");
        List<String> only = a.has("--only") ? Arrays.asList(a.single("--only").split(",")) : null;
        List<Step> steps = select(plan(), only);
        if (steps.isEmpty()) {
            System.err.println("nothing to record: --only " + only + " names no species, condition or group");
            System.exit(2);
        }

        final DemoWindow w = new DemoWindow("PAD dataset session (frames stay on this laptop under " + root + ")");
        w.mirrored(true);
        final WebcamFrameSource source = new WebcamFrameSource(camera, 640, 480, cameraFps(steps));
        final Readout readout = new Readout(w);
        source.setPreviewListener(new WebcamFrameSource.PreviewListener() {
            @Override public void onPreview(Frame f) {
                w.video(DemoWindow.image(f), readout.overlay());
                readout.latest.set(f);
            }
        });
        KeptOpenSource warm = new KeptOpenSource(source);
        final AtomicReference<String> key = new AtomicReference<String>();
        final LiveAnalyzerDemo.LiveLoop alive = new LiveAnalyzerDemo.LiveLoop();
        w.key("SPACE", new Runnable() { @Override public void run() { key.set("SPACE"); } });
        w.key("S", new Runnable() { @Override public void run() { key.set("S"); } });
        w.onClose(new Runnable() { @Override public void run() { alive.running = false; } });
        w.show();
        warm.open();
        readout.start();
        int i = 0;
        for (Step s : steps) {
            i++;
            DatasetRecorder.Take take = s.take(subject, root);
            if (take.dir().exists()) {
                System.out.println("already recorded: " + take.dir());
                continue;
            }
            key.set(null);
            w.message("NEXT " + i + "/" + steps.size() + ": " + s.prompt, new Color(200, 100, 0));
            readout.header = (s.group == null ? s.label + " take " + s.take + " (" + s.condition + ")"
                    : s.group + ": " + s.condition) + "\n\n" + wrap(s.prompt) + "\n\nSPACE = start (3 s countdown, then " + s.seconds
                    + " s)\nS = skip this take\n\nFrames stay on this laptop\n(" + root + "/, never committed).";
            w.progress(-1);
            String k = LiveAnalyzerDemo.awaitKey(key, alive);
            if (k == null) break;
            if (k.equals("S")) continue;
            for (int c = 3; c >= 1; c--) {
                Toolkit.getDefaultToolkit().beep();
                w.message(c + "…  " + s.prompt, Color.BLACK);
                Thread.sleep(1000);
            }
            Toolkit.getDefaultToolkit().beep();
            w.message("RECORDING (" + s.seconds + " s): " + s.prompt, Color.RED);
            Thread progress = progressBar(w, s.seconds * 1000L);
            try {
                File dir = DatasetRecorder.record(warm, take, System.out);
                w.message("Saved " + (s.group == null ? s.label + " take " + s.take : s.condition), new Color(0, 130, 0));
                System.out.println("wrote " + dir);
            } catch (Exception e) {
                w.message("Take failed: " + e.getMessage(), Color.RED);
                System.err.println("take failed: " + e);
            } finally {
                progress.interrupt();
            }
            Thread.sleep(700);
        }
        readout.stop();
        warm.close();
        Map<String, Integer> counts = EvalDataset.counts(EvalDataset.scan(root));
        StringBuilder sb = new StringBuilder("Takes per species under " + root + ":\n");
        for (Map.Entry<String, Integer> e : counts.entrySet()) sb.append(String.format("  %-22s %d%n", e.getKey(), e.getValue()));
        System.out.print(sb);
        readout.header = sb.toString();
        w.info(sb.toString());
        w.message("Session finished: thank you!", new Color(0, 130, 0));
        Thread.sleep(2500);
        System.exit(0);
    }

    /**
     * Live face-size readout under the step's instructions: the analyzer runs on the newest preview frame about five
     * times a second (in memory; nothing is written) and reports the face box as a share of the picture.
     */
    static final class Readout {
        final AtomicReference<Frame> latest = new AtomicReference<Frame>();
        volatile String header = "";
        private final DemoWindow w;
        private volatile float[] box;
        private volatile boolean running;
        private Thread worker;

        Readout(DemoWindow w) {
            this.w = w;
        }

        void start() throws Exception {
            final OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory();
            final FaceAnalyzer analyzer = factory.create();
            running = true;
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (running) {
                            Frame f = latest.getAndSet(null);
                            if (f != null) w.info(header + "\n\nLIVE: " + describe(analyzer.analyze(f), f));
                            Thread.sleep(200);
                        }
                    } catch (Exception e) {
                        w.info(header + "\n\nLIVE readout stopped: " + e.getMessage());
                    } finally {
                        analyzer.close();
                        factory.close();
                    }
                }
            }, "dataset-readout");
            worker.setDaemon(true);
            worker.start();
        }

        void stop() throws InterruptedException {
            running = false;
            if (worker != null) worker.join(2000);
            box = null;
        }

        private String describe(FrameAnalysis a, Frame f) {
            box = a.faceBox;
            if (a.faceCount == 0) return "no face";
            if (a.faceCount > 1) return a.faceCount + " faces";
            float area = a.faceBox[2] * a.faceBox[3] / ((float) f.width() * f.height());
            return String.format(Locale.ROOT, "face size %.0f%% of the picture%nwhole face visible: %s",
                    100 * area, a.qualityIssues.contains(QualityIssue.FACE_CUT_OFF) ? "NO (cut off)" : "yes");
        }

        /** The last face box, drawn in image coordinates (so it follows the mirrored preview). */
        DemoWindow.Overlay overlay() {
            final float[] b = box;
            if (b == null) return null;
            return new DemoWindow.Overlay() {
                @Override public void paint(Graphics2D g) {
                    g.setStroke(new BasicStroke(2f));
                    g.setColor(Color.CYAN);
                    g.drawRect(Math.round(b[0]), Math.round(b[1]), Math.round(b[2]), Math.round(b[3]));
                }
            };
        }
    }

    private static String wrap(String s) {
        return s.replace(", ", ",\n").replace("; ", ";\n");
    }

    private static Thread progressBar(final DemoWindow w, final long ms) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long start = System.currentTimeMillis();
                try {
                    while (true) {
                        w.progress((System.currentTimeMillis() - start) / (float) ms);
                        Thread.sleep(100);
                    }
                } catch (InterruptedException done) {
                    w.progress(-1);
                }
            }
        }, "dataset-progress");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
