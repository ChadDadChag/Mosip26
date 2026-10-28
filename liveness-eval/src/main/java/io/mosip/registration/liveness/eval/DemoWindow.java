package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.Frame;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;

/** The demo's Swing window: video with an overlay, a big message line, an info panel, progress and buttons. */
final class DemoWindow {

    /** Draws on top of the video, in image pixel coordinates. */
    interface Overlay {
        void paint(Graphics2D g);
    }

    private final JFrame frame;
    private final VideoPanel video = new VideoPanel();
    private final JLabel message = new JLabel(" ", JLabel.CENTER);
    private final JTextArea info = new JTextArea(18, 44);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));

    DemoWindow(String title) {
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        message.setFont(message.getFont().deriveFont(Font.BOLD, 26f));
        message.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        info.setEditable(false);
        info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        progress.setStringPainted(false);
        JPanel south = new JPanel(new BorderLayout());
        south.add(progress, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        frame.getContentPane().add(message, BorderLayout.NORTH);
        frame.getContentPane().add(video, BorderLayout.CENTER);
        frame.getContentPane().add(info, BorderLayout.EAST);
        frame.getContentPane().add(south, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    void show() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                frame.setVisible(true);
                frame.toFront();
            }
        });
    }

    boolean isClosed() {
        return !frame.isDisplayable();
    }

    /** Shows the video like a selfie (display only; the engine still analyses the unmirrored frames). */
    void mirrored(boolean mirror) {
        video.mirror = mirror;
    }

    boolean isMirrored() {
        return video.mirror;
    }

    void onClose(final Runnable r) {
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                r.run();
            }
        });
    }

    void video(final BufferedImage img, final Overlay overlay) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                video.image = img;
                video.overlay = overlay;
                video.repaint();
            }
        });
    }

    void message(final String text, final Color color) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                message.setText(text);
                message.setForeground(color == null ? Color.BLACK : color);
            }
        });
    }

    void info(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                info.setText(text);
            }
        });
    }

    /** 0..1, or a negative value to clear. */
    void progress(final float value) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                progress.setValue(value < 0 ? 0 : Math.round(Math.min(1f, value) * 1000));
            }
        });
    }

    void button(final String label, final Runnable action) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                JButton b = new JButton(label);
                b.addActionListener(e -> action.run());
                b.setFocusable(false);
                buttons.add(b);
                buttons.revalidate();
            }
        });
    }

    /** A key (e.g. "SPACE", "R") bound to an action anywhere in the window. */
    void key(final String key, final Runnable action) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                JComponent root = frame.getRootPane();
                root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
                root.getActionMap().put(key, new AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override public void actionPerformed(ActionEvent e) {
                        action.run();
                    }
                });
            }
        });
    }

    static BufferedImage image(Frame f) {
        return DatasetRecorder.toImage(f);
    }

    /** Scales the video to the panel (aspect kept) and paints the overlay in image coordinates. */
    private static final class VideoPanel extends JComponent {
        private static final long serialVersionUID = 1L;
        volatile BufferedImage image;
        volatile Overlay overlay;
        volatile boolean mirror;

        VideoPanel() {
            setPreferredSize(new Dimension(800, 600));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage img = image;
            if (img != null) {
                double s = Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight());
                int w = (int) (img.getWidth() * s), h = (int) (img.getHeight() * s);
                if (mirror) {
                    g.translate((getWidth() - w) / 2 + w, (getHeight() - h) / 2);
                    g.scale(-s, s);
                } else {
                    g.translate((getWidth() - w) / 2, (getHeight() - h) / 2);
                    g.scale(s, s);
                }
                g.drawImage(img, 0, 0, null);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Overlay o = overlay;
                if (o != null) o.paint(g);
            }
            g.dispose();
        }
    }
}
