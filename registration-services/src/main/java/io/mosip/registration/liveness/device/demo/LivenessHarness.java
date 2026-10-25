package io.mosip.registration.liveness.device.demo;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.heuristic.HeuristicFaceAnalyzer;
import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;
import io.mosip.registration.liveness.device.mock.MockFrameSource;
import io.mosip.registration.liveness.device.mock.SarxosWebcamFrameGrabber;
import io.mosip.registration.liveness.device.util.PreviewFrameSource;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Debug and demo window for the whole liveness pipeline (Part 1 plan, week 2): live preview with the detected
 * face box, the current liveness event and prompt, challenge progress, the analyzer's liveness score, and the
 * result, with Start / Retry / Cancel. Frames come from {@link MockFrameSource} (webcam, recorded video or images),
 * analysis from Part 2's ONNX analyzer ({@link OnnxFaceAnalyzerFactory}: YuNet, FaceMesh V2 and MiniFASNet PAD from
 * the signed model bundle) or, with {@code --analyzer heuristic} or {@code -D}{@value #ANALYZER_PROPERTY}{@code =heuristic},
 * the earlier heuristic stand-in ({@link HeuristicFaceAnalyzer}), decisions from the real Part 3 engine
 * ({@link DefaultLivenessService}).
 *
 * <pre>
 * LivenessHarness webcam [fps]            [--workflow RESIDENT|OPERATOR|SUPERVISOR] [--analyzer onnx|heuristic]
 *                                         [--diagnostic] [--headless]
 * LivenessHarness video  &lt;file.mjpeg&gt; [fps] [...]
 * LivenessHarness images &lt;fileOrDir&gt;  [fps] [...]
 * </pre>
 * {@code --headless} (or a headless JVM) runs one session, pressing Retry automatically, prints events and audit
 * records to the console and exits with 0 if it PASSED, 1 if it FAILED, 2 if it was CANCELLED or timed out: handy
 * for scripted demos and CI.
 */
public final class LivenessHarness {

    /** A headless run gives up after this long. */
    static final long HEADLESS_TIMEOUT_SEC = 120;

    /** System property choosing the analyzer: {@code onnx} (default) or {@code heuristic}. */
    public static final String ANALYZER_PROPERTY = "liveness.analyzer";

    private LivenessHarness() {
    }

    /**
     * The analyzer factory by name: {@code heuristic} gives the heuristic stand-in, anything else (or null) Part 2's
     * ONNX analyzer with the bundled, signed models (loaded on the first session). Shared by the E2E tests.
     */
    public static FaceAnalyzerFactory analyzerFactory(String name) {
        if (name != null && "heuristic".equalsIgnoreCase(name.trim())) {
            return HeuristicFaceAnalyzer::new;
        }
        return new OnnxFaceAnalyzerFactory();
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o == null) {
            usage();
            System.exit(64);
            return;
        }
        FrameSource device;
        switch (o.mode) {
            case "webcam":
                device = MockFrameSource.ofWebcam(new SarxosWebcamFrameGrabber(), o.fps);
                break;
            case "video":
                device = MockFrameSource.ofVideoFile(Paths.get(o.path), o.fps, true);
                break;
            default:
                device = new MockFrameSource(Paths.get(o.path), o.fps);
                break;
        }
        HarnessModel model = new HarnessModel();
        PreviewFrameSource source = new PreviewFrameSource(device);
        DefaultLivenessService service = service(model, analyzerFactory(o.analyzer), o.diagnostic,
                o.headless || GraphicsEnvironment.isHeadless());
        if (o.headless || GraphicsEnvironment.isHeadless()) {
            System.exit(runHeadless(service, source, model, o.workflow));
        } else {
            runWindow(service, source, model, o.workflow);
        }
    }

    static DefaultLivenessService service(final HarnessModel model, final FaceAnalyzerFactory analyzers,
                                          boolean diagnostic, boolean printAudit) {
        final Map<String, String> config = new HashMap<>();
        if (diagnostic) config.put(LivenessPolicy.PREFIX + "default.diagnosticMode", "true");
        DefaultLivenessService.Builder b = DefaultLivenessService.builder()
                .config(new ConfigProvider() {
                    @Override
                    public String get(String key) {
                        return config.get(key);
                    }
                })
                .analyzerFactory(new FaceAnalyzerFactory() {
                    @Override
                    public FaceAnalyzer create() throws LivenessEngineException {
                        return model.observing(analyzers.create());
                    }
                });
        if (printAudit) {
            b.auditSink(new AuditSink() {
                @Override
                public void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details) {
                    System.out.println("AUDIT " + event + " " + workflow + " " + details);
                }
            });
        }
        return b.build();
    }

    /** One session on the console. Returns the exit code described in the class comment. */
    static int runHeadless(DefaultLivenessService service, FrameSource source, HarnessModel model,
                           WorkflowType workflow) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        LivenessSession session = service.newSession(workflow);
        if (session == null) {
            System.out.println("Liveness is disabled for " + workflow);
            return 2;
        }
        model.sessionStarting(workflow.name());
        final LivenessSession running = session;
        session.start(source, new LivenessListener() {
            @Override
            public void onEvent(LivenessEvent e) {
                model.onEvent(e);
                // A scripted run presses Retry like a user would, so it always reaches a final result.
                if (e.type == LivenessEvent.Type.ATTEMPT_FAILED
                        || (e.type == LivenessEvent.Type.DEVICE_ERROR && e.recoverable)) {
                    System.out.println("RETRY (pressed automatically)");
                    running.retry();
                }
                HarnessModel.Snapshot s = model.snapshot();
                System.out.println("EVENT " + s.log.get(s.log.size() - 1) + "  | " + s.status
                        + (s.prompt.isEmpty() ? "" : " | " + s.prompt) + (s.guidance.isEmpty() ? "" : " | " + s.guidance));
            }

            @Override
            public void onComplete(LivenessResult r) {
                model.onComplete(r);
                System.out.println("RESULT " + model.snapshot().result);
                done.countDown();
            }
        });
        if (!done.await(HEADLESS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            session.cancel();
            done.await(5, TimeUnit.SECONDS);
            return 2;
        }
        System.out.println("METRICS " + service.metrics().snapshot().toMap());
        LivenessResult r = model.lastResult();
        if (r == null || r.decision == LivenessResult.Decision.CANCELLED) return 2;
        return r.passed() ? 0 : 1;
    }

    // ------------------------------------------------------------------ window

    private static void runWindow(final DefaultLivenessService service, final PreviewFrameSource source,
                                  final HarnessModel model, final WorkflowType initial) throws Exception {
        final PreviewPanel preview = new PreviewPanel(model);
        final SidePanel side = new SidePanel(model);
        final LivenessSession[] current = new LivenessSession[1];
        source.addPreviewListener(new PreviewFrameSource.PreviewListener() {
            @Override
            public void onPreviewFrame(Frame frame) {
                preview.show(toImage(frame));
            }
        });
        model.addChangeListener(new HarnessModel.ChangeListener() {
            @Override
            public void changed() {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        side.refresh();
                        preview.repaint();
                    }
                });
            }
        });
        final CountDownLatch closed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                JFrame window = new JFrame("Liveness harness - " + source.info().deviceId);
                window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                final JComboBox<WorkflowType> workflow = new JComboBox<>(WorkflowType.values());
                workflow.setSelectedItem(initial);
                JButton start = new JButton("Start");
                JButton retry = new JButton("Retry");
                JButton cancel = new JButton("Cancel");
                start.addActionListener(e -> {
                    if (current[0] != null) current[0].cancel();
                    WorkflowType wf = (WorkflowType) workflow.getSelectedItem();
                    LivenessSession s = service.newSession(wf);
                    if (s == null) {
                        model.sessionStarting(wf + " (liveness disabled)");
                        return;
                    }
                    current[0] = s;
                    model.sessionStarting(wf.name());
                    s.start(source, model);
                });
                retry.addActionListener(e -> {
                    if (current[0] != null) current[0].retry();
                });
                cancel.addActionListener(e -> {
                    if (current[0] != null) current[0].cancel();
                });
                JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
                controls.add(new JLabel("Workflow"));
                controls.add(workflow);
                controls.add(start);
                controls.add(retry);
                controls.add(cancel);
                window.add(controls, BorderLayout.NORTH);
                window.add(preview, BorderLayout.CENTER);
                window.add(side, BorderLayout.EAST);
                window.setSize(new Dimension(1100, 640));
                window.setLocationRelativeTo(null);
                window.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        closed.countDown();
                    }
                });
                window.setVisible(true);
            }
        });
        closed.await();
        if (current[0] != null) current[0].cancel();
        source.stop();
        System.exit(0);
    }

    static BufferedImage toImage(Frame frame) {
        byte[] rgb = frame.rgb();
        int[] pixels = new int[frame.width() * frame.height()];
        for (int i = 0, p = 0; i < pixels.length; i++, p += 3) {
            pixels[i] = ((rgb[p] & 0xFF) << 16) | ((rgb[p + 1] & 0xFF) << 8) | (rgb[p + 2] & 0xFF);
        }
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, frame.width(), frame.height(), pixels, 0, frame.width());
        return image;
    }

    /** Live preview with the analyzer's face box and score drawn on top. */
    static final class PreviewPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient HarnessModel model;
        private volatile BufferedImage image;

        PreviewPanel(HarnessModel model) {
            this.model = model;
            setBackground(Color.DARK_GRAY);
        }

        void show(BufferedImage next) {
            image = next;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = image;
            if (img == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight());
            int w = (int) (img.getWidth() * scale), h = (int) (img.getHeight() * scale);
            int x0 = (getWidth() - w) / 2, y0 = (getHeight() - h) / 2;
            g2.drawImage(img, x0, y0, w, h, null);
            HarnessModel.Snapshot s = model.snapshot();
            if (s.faceBox != null) {
                g2.setColor(s.faceCount == 1 ? Color.GREEN : Color.ORANGE);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRect(x0 + (int) (s.faceBox[0] * scale), y0 + (int) (s.faceBox[1] * scale),
                        (int) (s.faceBox[2] * scale), (int) (s.faceBox[3] * scale));
            }
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 16f));
            g2.drawString(String.format(Locale.ROOT, "faces %d   liveness score %s", s.faceCount,
                    Float.isNaN(s.lastScore) ? "n/a" : String.format(Locale.ROOT, "%.2f", s.lastScore)), x0 + 10, y0 + 24);
        }
    }

    /** Status, prompt, guidance, progress, result and the event log. */
    static final class SidePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient HarnessModel model;
        private final JLabel status = new JLabel(" ");
        private final JLabel prompt = new JLabel(" ");
        private final JLabel guidance = new JLabel(" ");
        private final JLabel attempt = new JLabel(" ");
        private final JLabel result = new JLabel(" ");
        private final JProgressBar progress = new JProgressBar(0, 100);
        private final JTextArea log = new JTextArea(16, 34);

        SidePanel(HarnessModel model) {
            this.model = model;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            prompt.setFont(prompt.getFont().deriveFont(Font.BOLD, 20f));
            status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
            log.setEditable(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            add(status);
            add(prompt);
            add(guidance);
            add(progress);
            add(attempt);
            add(result);
            add(log);
        }

        void refresh() {
            HarnessModel.Snapshot s = model.snapshot();
            status.setText(s.status);
            prompt.setText(s.prompt.isEmpty() ? " " : s.prompt);
            guidance.setText(s.guidance.isEmpty() ? " " : s.guidance);
            progress.setValue(Math.round(s.progress * 100));
            attempt.setText(s.attempt.isEmpty() ? " " : s.attempt + "   frames analysed " + s.framesAnalysed);
            result.setText(s.result.isEmpty() ? " " : s.result);
            log.setText(String.join("\n", s.log));
        }
    }

    // ------------------------------------------------------------------ arguments

    static final class Options {
        String mode;
        String path;
        double fps = 15.0;
        WorkflowType workflow = WorkflowType.RESIDENT;
        String analyzer = System.getProperty(ANALYZER_PROPERTY, "onnx");
        boolean diagnostic;
        boolean headless;

        static Options parse(String[] args) {
            if (args.length < 1) return null;
            Options o = new Options();
            o.mode = args[0];
            if (!"webcam".equals(o.mode) && !"video".equals(o.mode) && !"images".equals(o.mode)) return null;
            int i = 1;
            if (!"webcam".equals(o.mode)) {
                if (args.length < 2) return null;
                o.path = args[1];
                i = 2;
            }
            for (; i < args.length; i++) {
                String a = args[i];
                if ("--workflow".equals(a) && i + 1 < args.length) {
                    o.workflow = WorkflowType.valueOf(args[++i].toUpperCase(Locale.ROOT));
                } else if ("--analyzer".equals(a) && i + 1 < args.length) {
                    o.analyzer = args[++i].toLowerCase(Locale.ROOT);
                    if (!"onnx".equals(o.analyzer) && !"heuristic".equals(o.analyzer)) return null;
                } else if ("--diagnostic".equals(a)) {
                    o.diagnostic = true;
                } else if ("--headless".equals(a)) {
                    o.headless = true;
                } else {
                    o.fps = Double.parseDouble(a);
                }
            }
            return o;
        }
    }

    private static void usage() {
        System.err.println("Usage: LivenessHarness webcam [fps] [--workflow W] [--analyzer onnx|heuristic] [--diagnostic] [--headless]");
        System.err.println("       LivenessHarness video  <file.mjpeg> [fps] [--workflow W] [--analyzer A] [--diagnostic] [--headless]");
        System.err.println("       LivenessHarness images <fileOrDir>  [fps] [--workflow W] [--analyzer A] [--diagnostic] [--headless]");
        System.err.println("       (-D" + ANALYZER_PROPERTY + "=heuristic also selects the heuristic stand-in; default onnx)");
    }
}
