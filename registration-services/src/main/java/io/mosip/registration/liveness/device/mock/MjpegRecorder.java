package io.mosip.registration.liveness.device.mock;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/**
 * Writes recordings for {@link MockFrameSource#ofVideoFile}: concatenated
 * Content-Length-delimited JPEG frames, byte-for-byte the framing the SBI /stream
 * endpoint (and MockMDS) sends. A recording can be made from any FrameSource
 * (a real SBI device, the webcam relay), from a folder of images, or from the
 * command line:
 *
 * <pre>
 *   java ... MjpegRecorder images &lt;imageFileOrDir&gt; &lt;out.mjpeg&gt;
 *   java ... MjpegRecorder webcam &lt;frames&gt; &lt;fps&gt; &lt;out.mjpeg&gt;
 * </pre>
 */
public final class MjpegRecorder implements Closeable {

    private final OutputStream out;
    private int framesWritten;

    public MjpegRecorder(OutputStream out) {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        this.out = out;
    }

    public static MjpegRecorder toFile(Path file) throws IOException {
        return new MjpegRecorder(new BufferedOutputStream(Files.newOutputStream(file)));
    }

    /** Writes one already-encoded JPEG as a frame. */
    public synchronized void writeJpeg(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 2 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("Not a JPEG (missing SOI marker)");
        }
        out.write(("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg);
        out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        framesWritten++;
    }

    public void writeImage(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            // The JDK JPEG writer rejects alpha; normalise everything to plain RGB.
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(image, 0, 0, null);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpg", jpeg)) {
            throw new IOException("No JPEG encoder available");
        }
        writeJpeg(jpeg.toByteArray());
    }

    public void writeFrame(Frame frame) throws IOException {
        byte[] rgb = frame.rgb();
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0, i = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++, i += 3) {
                image.setRGB(x, y, ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF));
            }
        }
        writeImage(image);
    }

    public synchronized int framesWritten() {
        return framesWritten;
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }

    /** Converts an image file, or every image in a folder (sorted by name), into a recording. */
    public static int fromImages(Path imageOrDirectory, Path file) throws IOException {
        List<Path> images = MockFrameSource.discoverImages(imageOrDirectory);
        if (images.isEmpty()) {
            throw new IOException("No .jpg/.jpeg/.png images in " + imageOrDirectory);
        }
        try (MjpegRecorder recorder = toFile(file)) {
            for (Path path : images) {
                BufferedImage image = ImageIO.read(path.toFile());
                if (image == null) {
                    throw new IOException("Unsupported image: " + path);
                }
                recorder.writeImage(image);
            }
            return recorder.framesWritten();
        }
    }

    /**
     * Records up to {@code frameCount} frames from a stopped source, then stops it.
     * Stops early on a non-recoverable device error or after {@code timeoutMs}.
     *
     * @return frames written
     */
    public static int record(FrameSource source, Path file, int frameCount, long timeoutMs)
            throws IOException, InterruptedException {
        if (frameCount < 1 || timeoutMs <= 0) {
            throw new IllegalArgumentException("frameCount >= 1 and timeoutMs > 0 are required");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger remaining = new AtomicInteger(frameCount);
        AtomicReference<IOException> writeFailure = new AtomicReference<>();

        try (MjpegRecorder recorder = toFile(file)) {
            source.start(new FrameSource.Listener() {
                @Override
                public void onFrame(Frame frame) {
                    if (remaining.get() <= 0) {
                        return;
                    }
                    try {
                        recorder.writeFrame(frame);
                    } catch (IOException e) {
                        writeFailure.compareAndSet(null, e);
                        done.countDown();
                        return;
                    }
                    if (remaining.decrementAndGet() == 0) {
                        done.countDown();
                    }
                }

                @Override
                public void onError(ErrorCode code, String detail, boolean recoverable) {
                    if (!recoverable) {
                        done.countDown();
                    }
                }
            });
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } finally {
                source.stop();
            }
            if (writeFailure.get() != null) {
                throw writeFailure.get();
            }
            return recorder.framesWritten();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "images".equals(args[0])) {
            int n = fromImages(Paths.get(args[1]), Paths.get(args[2]));
            System.out.println("Wrote " + n + " frames to " + args[2]);
        } else if (args.length == 4 && "webcam".equals(args[0])) {
            int frames = Integer.parseInt(args[1]);
            double fps = Double.parseDouble(args[2]);
            MockFrameSource webcam = MockFrameSource.ofWebcam(new SarxosWebcamFrameGrabber(), fps);
            long timeoutMs = (long) (frames / fps * 1000.0) + 10_000L;
            int n = record(webcam, Paths.get(args[3]), frames, timeoutMs);
            System.out.println("Wrote " + n + " frames to " + args[3]);
        } else {
            System.err.println("Usage: MjpegRecorder images <imageFileOrDir> <out.mjpeg>");
            System.err.println("       MjpegRecorder webcam <frames> <fps> <out.mjpeg>");
            System.exit(2);
        }
    }
}
