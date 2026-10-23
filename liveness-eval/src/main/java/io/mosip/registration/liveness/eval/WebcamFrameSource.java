package io.mosip.registration.liveness.eval;

import com.github.sarxos.webcam.Webcam;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * A desktop webcam as a {@link FrameSource} (development only), using {@code com.github.sarxos:webcam-capture} 0.3.12
 * like Part 1. A grab thread delivers RGB888 frames (deviceId "webcam:&lt;index&gt;") with strictly increasing sequence
 * and timestamp; {@code start()} after {@code stop()} begins a fresh stream (sequence restarts at 0).
 *
 * <p>Errors: camera missing or not opening -> {@code onError(CAMERA_UNAVAILABLE, ..., false)} and the source stops;
 * a failed grab -> {@code onError(INVALID_FRAME, ..., true)} and the source keeps going.
 *
 * <p>{@link #setPreviewListener} adds a second, preview-only callback so a demo can show video while the engine owns
 * the main listener (Person 3's note N1).
 */
public final class WebcamFrameSource implements FrameSource {

    /** Preview-only callback, called on the grab thread after the main listener. */
    public interface PreviewListener {
        void onPreview(Frame frame);
    }

    /** The capture device (sarxos in production; a fake in tests). */
    interface Grabber {
        void open(int width, int height) throws IOException;

        /** The next image, or throws for a failed grab. */
        BufferedImage grab() throws IOException;

        void close();

        String name();
    }

    private final Grabber grabber;
    private final int index, width, height;
    private final double fps;
    private volatile PreviewListener preview;
    private volatile Thread thread;
    private volatile boolean running;
    private int generation;

    /** Default camera (index 0), 640 x 480, 15 fps. Public and no-arg for TraceRecorder's reflective construction. */
    public WebcamFrameSource() {
        this(0, 640, 480, 15);
    }

    public WebcamFrameSource(int deviceIndex, int width, int height, double fps) {
        this(new SarxosGrabber(deviceIndex), deviceIndex, width, height, fps);
    }

    WebcamFrameSource(Grabber grabber, int deviceIndex, int width, int height, double fps) {
        if (grabber == null || width <= 0 || height <= 0 || !(fps > 0)) throw new IllegalArgumentException("bad camera settings");
        this.grabber = grabber;
        this.index = deviceIndex;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    public void setPreviewListener(PreviewListener listener) {
        this.preview = listener;
    }

    @Override
    public DeviceInfo info() {
        return new DeviceInfo("webcam:" + index, "generic", grabber.name(), "none", "NONE", width, height, (int) Math.round(fps));
    }

    @Override
    public synchronized void start(final Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        if (running) return;
        running = true;
        final int gen = ++generation;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                grabLoop(listener, gen);
            }
        }, "webcam-" + index);
        thread.setDaemon(true);
        thread.start();
    }

    private boolean live(int gen) {
        synchronized (this) {
            return running && gen == generation;
        }
    }

    private void grabLoop(Listener listener, int gen) {
        try {
            grabber.open(width, height);
        } catch (IOException e) {
            synchronized (this) {
                if (gen == generation) running = false;
            }
            listener.onError(ErrorCode.CAMERA_UNAVAILABLE, "webcam " + index + ": " + e.getMessage(), false);
            return;
        }
        long seq = 0, lastTs = 0, periodNs = (long) (1e9 / fps);
        try {
            while (live(gen)) {
                long started = System.nanoTime();
                BufferedImage img;
                try {
                    img = grabber.grab();
                } catch (IOException e) {
                    if (live(gen)) listener.onError(ErrorCode.INVALID_FRAME, "grab failed: " + e.getMessage(), true);
                    sleepNs(periodNs);
                    continue;
                }
                long ts = Math.max(lastTs + 1, System.currentTimeMillis());
                lastTs = ts;
                Frame frame = new Frame(toRgb(img), img.getWidth(), img.getHeight(), ts, seq++, "webcam:" + index);
                if (!live(gen)) break;
                listener.onFrame(frame);
                PreviewListener p = preview;
                if (p != null) p.onPreview(frame);
                sleepNs(periodNs - (System.nanoTime() - started));
            }
        } finally {
            grabber.close();
        }
    }

    private static void sleepNs(long ns) {
        if (ns <= 0) return;
        try {
            Thread.sleep(ns / 1_000_000L, (int) (ns % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Packed RGB888, row-major. */
    static byte[] toRgb(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0, o = 0; i < argb.length; i++) {
            int p = argb[i];
            rgb[o++] = (byte) (p >> 16);
            rgb[o++] = (byte) (p >> 8);
            rgb[o++] = (byte) p;
        }
        return rgb;
    }

    /** Idempotent; waits briefly for the grab thread so the camera is released. */
    @Override
    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running && thread == null) return;
            running = false;
            generation++;
            t = thread;
            thread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** sarxos webcam-capture. */
    static final class SarxosGrabber implements Grabber {
        private final int index;
        private Webcam webcam;

        SarxosGrabber(int index) {
            this.index = index;
        }

        @Override
        public void open(int width, int height) throws IOException {
            try {
                List<Webcam> cams = Webcam.getWebcams(10000);
                if (index < 0 || index >= cams.size()) throw new IOException("no webcam at index " + index + " (found " + cams.size() + ")");
                webcam = cams.get(index);
                webcam.setViewSize(new Dimension(width, height));
                webcam.getLock().disable();
                if (!webcam.open()) throw new IOException("webcam " + webcam.getName() + " did not open");
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public BufferedImage grab() throws IOException {
            BufferedImage img;
            try {
                img = webcam.getImage();
            } catch (RuntimeException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (img == null) throw new IOException("no image");
            return img;
        }

        @Override
        public void close() {
            if (webcam != null && webcam.isOpen()) webcam.close();
        }

        @Override
        public String name() {
            return webcam != null ? webcam.getName() : "webcam " + index;
        }
    }
}
