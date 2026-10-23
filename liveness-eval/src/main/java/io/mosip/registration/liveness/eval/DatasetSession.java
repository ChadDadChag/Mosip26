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
 */
final class DatasetSession {

    /** One planned take. */
    static final class Step {
        final String label, condition, prompt;
        final int take;

        Step(String label, int take, String condition, String prompt) {
            this.label = label; this.take = take; this.condition = condition; this.prompt = prompt;
        }
    }

    private static final String OUT_OF_VIEW = "; your own face OUT of view";

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
        // Round 1 follow-up: the first close takes were too close (bona fide, face cut off) or not close enough
        // (phone, face about 4% of the picture); these use the live face-size readout.
        add(p, "bona_fide", 11, 2, "close_whole", "YOUR FACE: lean in until face size reads 25-40%, WHOLE face in the picture");
        add(p, "screen_photo_close", 7, 3, "selfie_fill",
                "PHONE VERY CLOSE (~10 cm): your SELFIE PHOTO, face size 20% or more" + OUT_OF_VIEW);
        add(p, "screen_photo_close", 10, 3, "nasa_fill",
                "PHONE VERY CLOSE (~10 cm): the NASA PORTRAIT, face size 20% or more" + OUT_OF_VIEW);
        return p;
    }

    private static void add(List<Step> p, String label, int firstTake, int n, String condition, String prompt) {
        for (int i = 0; i < n; i++) p.add(new Step(label, firstTake + i, condition, prompt));
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
        List<Step> steps = new ArrayList<Step>();
        for (Step s : plan()) if (only == null || only.contains(s.label) || only.contains(s.condition)) steps.add(s);

        final DemoWindow w = new DemoWindow("PAD dataset session (frames stay on this laptop under " + root + ")");
        w.mirrored(true);
        final WebcamFrameSource source = new WebcamFrameSource(camera, 640, 480, 15);
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
            DatasetRecorder.Take take = new DatasetRecorder.Take(s.label, subject, s.take, 6, 10, root, "jpg", s.condition);
            if (take.dir().exists()) {
                System.out.println("already recorded: " + take.dir());
                continue;
            }
            key.set(null);
            w.message("NEXT " + i + "/" + steps.size() + ": " + s.prompt, new Color(200, 100, 0));
            readout.header = s.label + " take " + s.take + " (" + s.condition + ")\n\n" + wrap(s.prompt)
                    + "\n\nSPACE = start (3 s countdown, then 6 s)\nS = skip this take\n\nFrames stay on this laptop\n(" + root
                    + "/, never committed).";
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
            w.message("RECORDING (6 s): " + s.prompt, Color.RED);
            Thread progress = progressBar(w, 6000);
            try {
                File dir = DatasetRecorder.record(warm, take, System.out);
                w.message("Saved " + s.label + " take " + s.take, new Color(0, 130, 0));
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
