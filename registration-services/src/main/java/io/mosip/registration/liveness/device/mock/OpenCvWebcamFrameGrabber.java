package io.mosip.registration.liveness.device.mock;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.Locale;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

/**
 * Development webcam capture with OpenCV's {@code VideoCapture} (the openpnp 4.5.3 build that MOSIP's biometrics-util
 * already brings, natives inside the jar; DirectShow on Windows). It is the default backend of {@link WebcamGrabbers}
 * since F13b: sarxos/BridJ ended long sessions with a native exit (F13), and its shutdown hook can hang the JVM's exit.
 *
 * <p>The camera opens on the first grab and again after {@link #close()}, so it is held only while a
 * {@link MockFrameSource} runs, as with {@link SarxosWebcamFrameGrabber}. Development only: the client's camera is
 * the SBI device.
 */
public final class OpenCvWebcamFrameGrabber implements MockFrameSource.WebcamFrameGrabber {

    private final int index;
    private VideoCapture capture;
    private Mat mat;
    private BufferedImage image;

    /** The system's first camera. */
    public OpenCvWebcamFrameGrabber() {
        this(0);
    }

    public OpenCvWebcamFrameGrabber(int index) {
        this.index = index;
    }

    private void ensureOpen() throws IOException {
        if (capture != null) {
            return;
        }
        nu.pattern.OpenCV.loadLocally();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        VideoCapture c = new VideoCapture(index, windows ? Videoio.CAP_DSHOW : Videoio.CAP_ANY);
        if (!c.isOpened()) {
            c.release();
            throw new IOException("OpenCV could not open camera " + index);
        }
        capture = c;
        mat = new Mat();
    }

    @Override
    public BufferedImage grabFrame() throws IOException {
        ensureOpen();
        if (!capture.read(mat) || mat.empty()) {
            throw new IOException("Webcam returned no frame (device may have been disconnected): opencv:" + index);
        }
        if (mat.channels() != 3) {
            throw new IOException("Unexpected " + mat.channels() + "-channel webcam frame");
        }
        if (image == null || image.getWidth() != mat.cols() || image.getHeight() != mat.rows()) {
            image = new BufferedImage(mat.cols(), mat.rows(), BufferedImage.TYPE_3BYTE_BGR);
        }
        // MockFrameSource converts the image to RGB888 before the next grab, so one image is reused.
        mat.get(0, 0, ((DataBufferByte) image.getRaster().getDataBuffer()).getData());
        return image;
    }

    @Override
    public void close() {
        if (capture != null) {
            capture.release();
        }
        if (mat != null) {
            mat.release();
        }
        capture = null;
        mat = null;
    }

    @Override
    public String toString() {
        return "opencv:" + index;
    }
}
