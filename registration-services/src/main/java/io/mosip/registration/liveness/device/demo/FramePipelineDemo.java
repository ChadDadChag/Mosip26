package io.mosip.registration.liveness.device.demo;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.device.mock.MockFrameSource;
import io.mosip.registration.liveness.device.mock.WebcamGrabbers;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Developer demo for the Part 1 pipeline: opens a mock-device source and shows the
 * delivered {@link Frame}s with sequence, measured FPS, resolution and any device errors.
 *
 * <pre>
 *   FramePipelineDemo webcam [fps] [stride]
 *   FramePipelineDemo video  &lt;file.mjpeg&gt; [fps] [stride]
 *   FramePipelineDemo images &lt;fileOrDir&gt;  [fps] [stride]
 * </pre>
 *
 * Without a display it prints one line per frame instead (stop with Ctrl+C).
 */
public final class FramePipelineDemo {

    private FramePipelineDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            return;
        }
        String mode = args[0];
        int next = "webcam".equals(mode) ? 1 : 2;
        if (args.length < next) {
            usage();
            return;
        }
        double fps = args.length > next ? Double.parseDouble(args[next]) : 15.0;
        int stride = args.length > next + 1 ? Integer.parseInt(args[next + 1]) : 1;

        FrameSource source;
        switch (mode) {
            case "webcam":
                source = MockFrameSource.ofWebcam(WebcamGrabbers.create(), fps, stride);
                break;
            case "video":
                source = MockFrameSource.ofVideoFile(Paths.get(args[1]), fps, true, stride);
                break;
            case "images":
                source = new MockFrameSource(Paths.get(args[1]), fps, stride);
                break;
            default:
                usage();
                return;
        }

        System.out.println("Device: " + source.info().deviceId + "  certification=" + source.info().certificationLevel);
        System.out.println("Capabilities: " + source.info().capabilities);

        if (GraphicsEnvironment.isHeadless()) {
            runHeadless(source);
        } else {
            runWithWindow(source);
        }
    }

    private static void runHeadless(FrameSource source) throws InterruptedException {
        source.start(new StatsListener(null));
        Runtime.getRuntime().addShutdownHook(new Thread(source::stop));
        new CountDownLatch(1).await();
    }

    private static void runWithWindow(FrameSource source) throws Exception {
        PreviewPanel panel = new PreviewPanel();
        CountDownLatch closed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            JFrame window = new JFrame("Liveness frame pipeline - " + source.info().deviceId);
            window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            window.add(panel, BorderLayout.CENTER);
            window.setSize(new Dimension(800, 600));
            window.setLocationRelativeTo(null);
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    closed.countDown();
                }
            });
            window.setVisible(true);
        });
        source.start(new StatsListener(panel));
        closed.await();
        WebcamGrabbers.exit(0, source::stop);
    }

    private static void usage() {
        System.err.println("Usage: FramePipelineDemo webcam [fps] [stride]");
        System.err.println("       FramePipelineDemo video  <file.mjpeg> [fps] [stride]");
        System.err.println("       FramePipelineDemo images <fileOrDir>  [fps] [stride]");
    }

    /** Measures delivered FPS and forwards frames to the panel (or the console). */
    private static final class StatsListener implements FrameSource.Listener {
        private final PreviewPanel panel;
        private long windowStartMs;
        private int framesInWindow;
        private double fps;

        StatsListener(PreviewPanel panel) {
            this.panel = panel;
        }

        @Override
        public void onFrame(Frame frame) {
            long now = frame.timestampMs();
            if (windowStartMs == 0) {
                windowStartMs = now;
            }
            framesInWindow++;
            if (now - windowStartMs >= 1000) {
                fps = framesInWindow * 1000.0 / (now - windowStartMs);
                windowStartMs = now;
                framesInWindow = 0;
            }
            String status = String.format("seq=%d  %dx%d %s  delivered=%.1f fps",
                    frame.sequence(), frame.width(), frame.height(), frame.format(), fps);
            if (panel == null) {
                System.out.println(status);
            } else {
                panel.show(toImage(frame), status);
            }
        }

        @Override
        public void onError(ErrorCode code, String detail, boolean recoverable) {
            String message = "ERROR " + code + (recoverable ? " (recoverable)" : " (fatal)") + ": " + detail;
            System.err.println(message);
            if (panel != null) {
                panel.showError(message);
            }
        }
    }

    private static BufferedImage toImage(Frame frame) {
        byte[] rgb = frame.rgb();
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[frame.width() * frame.height()];
        for (int i = 0, p = 0; p < pixels.length; p++, i += 3) {
            pixels[p] = ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF);
        }
        image.setRGB(0, 0, frame.width(), frame.height(), pixels, 0, frame.width());
        return image;
    }

    private static final class PreviewPanel extends JPanel {
        private volatile BufferedImage image;
        private volatile String status = "Waiting for frames...";
        private volatile String error;

        PreviewPanel() {
            setBackground(Color.BLACK);
        }

        void show(BufferedImage next, String nextStatus) {
            image = next;
            status = nextStatus;
            repaint();
        }

        void showError(String message) {
            error = message;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage current = image;
            if (current != null) {
                double scale = Math.min((double) getWidth() / current.getWidth(),
                        (double) (getHeight() - 40) / current.getHeight());
                int w = (int) (current.getWidth() * scale);
                int h = (int) (current.getHeight() * scale);
                g.drawImage(current, (getWidth() - w) / 2, 0, w, h, null);
            }
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
            g.setColor(Color.GREEN);
            g.drawString(status, 10, getHeight() - 24);
            if (error != null) {
                g.setColor(Color.RED);
                g.drawString(error, 10, getHeight() - 6);
            }
        }
    }
}
