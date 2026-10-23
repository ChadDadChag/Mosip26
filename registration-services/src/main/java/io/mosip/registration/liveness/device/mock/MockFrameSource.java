package io.mosip.registration.liveness.device.mock;

import io.mosip.registration.liveness.api.DeviceCapabilities;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.device.util.JpegToRgb888Converter;
import io.mosip.registration.liveness.device.util.MjpegFrameReader;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/**
 * Hardware-independent FrameSource for Part 1 development and tests, with three
 * interchangeable frame-producing modes behind one common lifecycle:
 *
 * <ol>
 *   <li><b>Static images</b> ({@link #MockFrameSource(Path, double)}) - a single
 *       image or a folder of images, cycled repeatedly. Useful for presentation-
 *       attack-style tests (a fixed "photo").</li>
 *   <li><b>Recorded video-file playback</b> ({@link #ofVideoFile(Path, double, boolean)})
 *       - plays back a previously-recorded MJPEG capture file through the same,
 *       already-tested {@link MjpegFrameReader}/{@link JpegToRgb888Converter} the
 *       real SBI adapter uses. "Recording" here means literally capturing the raw
 *       Content-Length-delimited JPEG byte stream from a real or mock SBI device to
 *       a file once, then replaying those exact bytes deterministically - no video
 *       codec (H.264/MP4 etc.) or extra dependency is needed, and this exercises
 *       the identical parsing path production code uses.</li>
 *   <li><b>Live webcam relay</b> ({@link #ofWebcam(WebcamFrameGrabber, double)}) -
 *       delegates actual pixel capture to a {@link WebcamFrameGrabber}:
 *       {@link SarxosWebcamFrameGrabber} for a real camera (same library the
 *       ref-document-scanner module already uses), or a fake in tests (see
 *       {@code MockFrameSourceTest}).</li>
 * </ol>
 *
 * <p>All three modes support {@code frameStride} (deliver every Nth frame) the
 * same way {@code SbiStreamFrameSource} does, for consistency.</p>
 *
 * <p>{@link #info()} declares formats, resolutions and frame rate. By default the
 * mock reports certification "NONE"; {@link #asCertificationLevel(String)} makes it
 * pose as an L0 or L1 device so certification-level policies can be tested.</p>
 *
 * <p>Each start() runs its own worker. A worker still stuck in a blocking grab
 * after stop() never delivers to, or releases the grabber under, a later session.</p>
 */
public class MockFrameSource implements FrameSource {

    /** Pluggable actual frame capture for {@link #ofWebcam(WebcamFrameGrabber, double)}. */
    public interface WebcamFrameGrabber extends AutoCloseable {
        /** Captures one frame. */
        BufferedImage grabFrame() throws IOException;

        /** What the camera supports, if the implementation can tell. */
        default DeviceCapabilities capabilities() {
            return DeviceCapabilities.unknown();
        }

        @Override
        default void close() throws IOException {
            // No resources to release by default.
        }
    }

    private final FrameProducer producer;
    private final long frameIntervalMs;
    private final int frameStride;
    private volatile DeviceInfo deviceInfo;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    /** The current session's worker; null when stopped. */
    private volatile Thread workerThread;

    /** Static-image(s) mode: a single image or a folder of images, cycled repeatedly. */
    public MockFrameSource(Path imageOrDirectory, double fps) throws IOException {
        this(imageOrDirectory, fps, 1);
    }

    /** Static-image(s) mode with Nth-frame stride. */
    public MockFrameSource(Path imageOrDirectory, double fps, int frameStride) throws IOException {
        if (imageOrDirectory == null || !Files.exists(imageOrDirectory)) {
            throw new IllegalArgumentException("Mock image source does not exist");
        }
        requireValidFps(fps);
        requireValidStride(frameStride);

        List<Path> imagePaths = discoverImages(imageOrDirectory);
        if (imagePaths.isEmpty()) {
            throw new IllegalArgumentException("No supported images found in " + imageOrDirectory);
        }

        // Decode once up front rather than per frame: keeps per-frame delivery
        // timing predictable (no disk I/O or JPEG decode jitter in the loop) and
        // still yields a fresh, independent RGB888 byte[] per Frame below, since
        // Frame instances must not share a mutable backing array across deliveries.
        List<BufferedImage> images = new ArrayList<>(imagePaths.size());
        for (Path path : imagePaths) {
            images.add(readImage(path));
        }

        this.producer = new StaticImageProducer(images);
        this.frameIntervalMs = intervalMsFromFps(fps);
        this.frameStride = frameStride;

        Set<String> formats = new LinkedHashSet<>();
        for (Path path : imagePaths) {
            formats.add(path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") ? "PNG" : "JPEG");
        }
        List<DeviceCapabilities.Resolution> resolutions = new ArrayList<>();
        for (BufferedImage image : images) {
            addDistinct(resolutions, image.getWidth(), image.getHeight());
        }
        DeviceCapabilities caps = new DeviceCapabilities(new ArrayList<>(formats), resolutions,
                Collections.singletonList((int) Math.ceil(fps)), DeviceCapabilities.Origin.DECLARED);
        this.deviceInfo = new DeviceInfo(
                "mock:" + imageOrDirectory.getFileName(),
                "MOSIP-Mock", "ImageSource", "mock", "NONE", 0, 0, 0).withCapabilities(caps);
    }

    private MockFrameSource(FrameProducer producer, double fps, int frameStride, DeviceInfo deviceInfo) {
        this.producer = producer;
        this.frameIntervalMs = intervalMsFromFps(fps);
        this.frameStride = frameStride;
        this.deviceInfo = deviceInfo;
    }

    /**
     * Recorded video-file playback mode: replays a previously-captured MJPEG file
     * (the same Content-Length-delimited JPEG stream format the real SBI /stream
     * endpoint and mock MDS both produce) through {@link MjpegFrameReader}.
     *
     * @param mjpegFile a file containing one or more concatenated
     *                  {@code Content-Length}-delimited JPEG frames
     * @param fps       playback rate
     * @param loop      if true, replays the file repeatedly forever (useful for
     *                  soak/duration tests); if false, plays once and then this
     *                  source stops itself normally (no error) after the last frame
     */
    public static MockFrameSource ofVideoFile(Path mjpegFile, double fps, boolean loop) throws IOException {
        return ofVideoFile(mjpegFile, fps, loop, 1);
    }

    /** Recorded video-file playback mode with Nth-frame stride. */
    public static MockFrameSource ofVideoFile(Path mjpegFile, double fps, boolean loop, int frameStride)
            throws IOException {
        if (mjpegFile == null || !Files.isRegularFile(mjpegFile)) {
            throw new IllegalArgumentException("Recorded MJPEG file does not exist: " + mjpegFile);
        }
        requireValidFps(fps);
        requireValidStride(frameStride);

        byte[] fileBytes = Files.readAllBytes(mjpegFile);
        List<JpegToRgb888Converter.DecodedImage> decodedFrames = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(fileBytes)) {
            while (true) {
                byte[] jpeg;
                try {
                    jpeg = MjpegFrameReader.readNextFrame(in);
                } catch (EOFException endOfFile) {
                    break; // clean end of the recorded file
                }
                decodedFrames.add(JpegToRgb888Converter.convert(jpeg));
            }
        }
        if (decodedFrames.isEmpty()) {
            throw new IllegalArgumentException("Recorded MJPEG file contains no readable frames: " + mjpegFile);
        }

        List<DeviceCapabilities.Resolution> resolutions = new ArrayList<>();
        for (JpegToRgb888Converter.DecodedImage image : decodedFrames) {
            addDistinct(resolutions, image.getWidth(), image.getHeight());
        }
        DeviceCapabilities caps = new DeviceCapabilities(Collections.singletonList("MJPEG"), resolutions,
                Collections.singletonList((int) Math.ceil(fps)), DeviceCapabilities.Origin.DECLARED);
        DeviceInfo deviceInfo = new DeviceInfo(
                "mock:video:" + mjpegFile.getFileName(),
                "MOSIP-Mock", "RecordedVideoSource", "mock", "NONE", 0, 0, 0).withCapabilities(caps);

        return new MockFrameSource(new VideoFileProducer(decodedFrames, loop), fps, frameStride, deviceInfo);
    }

    /** Live webcam relay mode, e.g. with {@link SarxosWebcamFrameGrabber}. */
    public static MockFrameSource ofWebcam(WebcamFrameGrabber grabber, double fps) throws IOException {
        return ofWebcam(grabber, fps, 1);
    }

    /** Live webcam relay mode with Nth-frame stride. */
    public static MockFrameSource ofWebcam(WebcamFrameGrabber grabber, double fps, int frameStride)
            throws IOException {
        if (grabber == null) {
            throw new IllegalArgumentException("grabber must not be null");
        }
        requireValidFps(fps);
        requireValidStride(frameStride);

        // The relay delivers at the requested rate, whatever else the camera supports.
        DeviceCapabilities grabberCaps = grabber.capabilities();
        DeviceCapabilities caps = new DeviceCapabilities(grabberCaps.streamFormats, grabberCaps.resolutions,
                Collections.singletonList((int) Math.ceil(fps)), DeviceCapabilities.Origin.DECLARED);
        DeviceInfo deviceInfo = new DeviceInfo(
                "mock:webcam", "MOSIP-Mock", "WebcamSource", "mock", "NONE", 0, 0, 0).withCapabilities(caps);

        return new MockFrameSource(new WebcamProducer(grabber), fps, frameStride, deviceInfo);
    }

    /**
     * Makes this mock report itself as an "L0" or "L1" (or "NONE") device in {@link #info()},
     * so code that enforces a certification level can be exercised without hardware.
     */
    public MockFrameSource asCertificationLevel(String level) {
        if (!"L0".equals(level) && !"L1".equals(level) && !"NONE".equals(level)) {
            throw new IllegalArgumentException("certification level must be L0, L1 or NONE, was " + level);
        }
        deviceInfo = deviceInfo.withCertificationLevel(level);
        return this;
    }

    @Override
    public DeviceInfo info() {
        return deviceInfo;
    }

    @Override
    public void start(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (lifecycleLock) {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            Thread worker = new Thread(() -> run(listener), "LIVENESS_MOCK_FRAME_THREAD");
            worker.setDaemon(true);
            workerThread = worker;
            worker.start();
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            running.set(false);
            Thread worker = workerThread;
            workerThread = null;
            if (worker != null && worker != Thread.currentThread()) {
                worker.interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** True only for the worker of the session that is currently running. */
    private boolean isCurrentSession() {
        return running.get() && workerThread == Thread.currentThread();
    }

    private void run(Listener listener) {
        long sequence = 0L;
        try {
            while (isCurrentSession()) {
                FrameData data;
                try {
                    data = producer.next();
                } catch (IOException e) {
                    if (isCurrentSession()) {
                        notifyError(listener, ErrorCode.CAMERA_UNAVAILABLE, safeMessage(e), false);
                    }
                    return;
                }

                if (data == null) {
                    // Finite producer (non-looping video file) has no more frames -
                    // a normal, successful end of playback, not an error.
                    return;
                }

                long frameIndex = sequence;
                if (frameIndex % frameStride == 0 && isCurrentSession()) {
                    deliverFrame(listener, new Frame(
                            data.rgb, data.width, data.height,
                            System.nanoTime() / 1_000_000L,
                            frameIndex,
                            deviceInfo.deviceId));
                }
                sequence = frameIndex + 1;

                try {
                    Thread.sleep(frameIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            synchronized (lifecycleLock) {
                if (workerThread == Thread.currentThread()) {
                    // This session ended on its own (end of playback or device error).
                    running.set(false);
                    workerThread = null;
                }
                // Release only if no newer session has taken over the device.
                if (workerThread == null) {
                    producer.release();
                }
            }
        }
    }

    /** Isolates listener exceptions so a downstream bug can't kill the mock loop. */
    private static void deliverFrame(Listener listener, Frame frame) {
        try {
            listener.onFrame(frame);
        } catch (Throwable listenerFailure) {
            System.err.println("MockFrameSource listener.onFrame() threw: " + listenerFailure);
        }
    }

    private static void notifyError(Listener listener, ErrorCode code, String detail, boolean recoverable) {
        try {
            listener.onError(code, detail, recoverable);
        } catch (Throwable listenerFailure) {
            System.err.println("MockFrameSource listener.onError() threw: " + listenerFailure);
        }
    }

    private static void addDistinct(List<DeviceCapabilities.Resolution> resolutions, int width, int height) {
        DeviceCapabilities.Resolution r = new DeviceCapabilities.Resolution(width, height);
        if (!resolutions.contains(r)) {
            resolutions.add(r);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static void requireValidFps(double fps) {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be positive, was " + fps);
        }
    }

    private static void requireValidStride(int frameStride) {
        if (frameStride < 1) {
            throw new IllegalArgumentException("frameStride must be >= 1, was " + frameStride);
        }
    }

    private static long intervalMsFromFps(double fps) {
        return Math.max(1L, Math.round(1000.0 / fps));
    }

    static List<Path> discoverImages(Path source) throws IOException {
        List<Path> paths = new ArrayList<>();
        if (Files.isRegularFile(source)) {
            if (isSupportedImage(source)) {
                paths.add(source);
            }
            return paths;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(source)) {
            stream.filter(Files::isRegularFile)
                    .filter(MockFrameSource::isSupportedImage)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(paths::add);
        }
        return paths;
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    private static BufferedImage readImage(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Unsupported image format: " + path);
        }
        return image;
    }

    /** One decoded frame's pixels, decoupled from how each mode produces them. */
    private static final class FrameData {
        final byte[] rgb;
        final int width;
        final int height;

        FrameData(byte[] rgb, int width, int height) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
        }
    }

    /** Internal seam between "how do we get the next frame's pixels" and the shared run loop/lifecycle. */
    private interface FrameProducer {
        /** @return the next frame, or {@code null} if this (finite) producer has no more frames. */
        FrameData next() throws IOException;

        /** Called once when the run loop ends (stop, end of playback, or error). */
        default void release() {
        }
    }

    private static final class StaticImageProducer implements FrameProducer {
        private final List<BufferedImage> images;
        private int index;

        StaticImageProducer(List<BufferedImage> images) {
            this.images = images;
        }

        @Override
        public FrameData next() {
            BufferedImage image = images.get(index);
            index = (index + 1) % images.size();
            // toRgb888 always allocates a new array, so each Frame owns its own
            // buffer even though the source BufferedImage is reused.
            byte[] rgb = JpegToRgb888Converter.toRgb888(image);
            return new FrameData(rgb, image.getWidth(), image.getHeight());
        }
    }

    private static final class VideoFileProducer implements FrameProducer {
        private final List<JpegToRgb888Converter.DecodedImage> frames;
        private final boolean loop;
        private int index;
        private boolean exhausted;

        VideoFileProducer(List<JpegToRgb888Converter.DecodedImage> frames, boolean loop) {
            this.frames = frames;
            this.loop = loop;
        }

        @Override
        public FrameData next() {
            if (exhausted) {
                return null;
            }
            JpegToRgb888Converter.DecodedImage image = frames.get(index);
            index++;
            if (index >= frames.size()) {
                if (loop) {
                    index = 0;
                } else {
                    exhausted = true; // this frame is delivered; the next call() returns null
                }
            }
            return new FrameData(image.getRgb(), image.getWidth(), image.getHeight());
        }
    }

    private static final class WebcamProducer implements FrameProducer {
        private final WebcamFrameGrabber grabber;

        WebcamProducer(WebcamFrameGrabber grabber) {
            this.grabber = grabber;
        }

        @Override
        public FrameData next() throws IOException {
            BufferedImage image = grabber.grabFrame();
            if (image == null) {
                throw new IOException("Webcam grabber returned no frame");
            }
            byte[] rgb = JpegToRgb888Converter.toRgb888(image);
            return new FrameData(rgb, image.getWidth(), image.getHeight());
        }

        @Override
        public void release() {
            // Releases the camera when the source stops so it is not held (and its
            // indicator light left on) after stop(); the grabber re-opens on demand.
            try {
                grabber.close();
            } catch (IOException | RuntimeException e) {
                System.err.println("MockFrameSource webcam release failed: " + e);
            }
        }
    }
}
