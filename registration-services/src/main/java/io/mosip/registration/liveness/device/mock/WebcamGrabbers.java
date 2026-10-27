package io.mosip.registration.liveness.device.mock;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import io.mosip.registration.liveness.api.DeviceCapabilities;

/**
 * The development webcam backends of {@link MockFrameSource#ofWebcam} (F13b), and a clean way out of a development
 * tool that used one.
 *
 * <p>Backend: the {@value #CAPTURE_PROPERTY} system property (the same one as liveness-eval's tools), or
 * {@link #create(String)}:
 * <ul>
 *   <li>{@code opencv} (default): {@link OpenCvWebcamFrameGrabber}. If OpenCV cannot load or open the camera, the
 *       grabber falls back to sarxos and says so on stderr;</li>
 *   <li>{@code sarxos}: {@link SarxosWebcamFrameGrabber}, as before.</li>
 * </ul>
 *
 * <p>Why: sarxos/BridJ ended long sessions with a native exit (F13), and its JVM shutdown hook can hang
 * {@code System.exit} (F13b): the hook waits for sarxos' processor threads, which wait on each other. With OpenCV,
 * sarxos is never loaded and registers no hook. {@link #exit} closes the tool's camera first and stops sarxos'
 * discovery service, so the hook has nothing left to do; a watchdog halts the JVM if the hooks still hang.
 */
public final class WebcamGrabbers {

    /** System property naming the backend: opencv (default) or sarxos. */
    public static final String CAPTURE_PROPERTY = "liveness.capture";
    /** How long {@link #exit} lets the shutdown hooks run before it halts the JVM. */
    public static final long EXIT_GRACE_MS = 5_000L;

    private static volatile boolean sarxosUsed;

    private WebcamGrabbers() {
    }

    /** The backend named by {@value #CAPTURE_PROPERTY} (default opencv). */
    public static MockFrameSource.WebcamFrameGrabber create() throws IOException {
        return create(System.getProperty(CAPTURE_PROPERTY, "opencv"));
    }

    /** opencv (with the sarxos fallback) or sarxos. */
    public static MockFrameSource.WebcamFrameGrabber create(String backend) throws IOException {
        String b = backend == null ? "opencv" : backend.trim().toLowerCase(Locale.ROOT);
        switch (b) {
            case "opencv":
                return new Fallback(OpenCvWebcamFrameGrabber::new, WebcamGrabbers::sarxos);
            case "sarxos":
                return sarxos();
            default:
                throw new IllegalArgumentException("unknown capture backend '" + backend + "' (opencv or sarxos)");
        }
    }

    private static MockFrameSource.WebcamFrameGrabber sarxos() throws IOException {
        sarxosUsed = true;
        return new SarxosWebcamFrameGrabber();
    }

    /** Whether this JVM created a sarxos grabber (its shutdown hook is then registered). */
    static boolean sarxosUsed() {
        return sarxosUsed;
    }

    /** A grabber that may fail to construct, for the fallback. */
    interface GrabberFactory {
        MockFrameSource.WebcamFrameGrabber create() throws IOException;
    }

    /** Grabs with the first backend; if it cannot load or open the camera, says so on stderr and uses the second. */
    static final class Fallback implements MockFrameSource.WebcamFrameGrabber {
        private final Supplier<MockFrameSource.WebcamFrameGrabber> first;
        private final GrabberFactory second;
        private MockFrameSource.WebcamFrameGrabber active;
        private boolean firstFailed;

        Fallback(Supplier<MockFrameSource.WebcamFrameGrabber> first, GrabberFactory second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public BufferedImage grabFrame() throws IOException {
            if (active == null && !firstFailed) {
                MockFrameSource.WebcamFrameGrabber g = first.get();
                try {
                    BufferedImage img = g.grabFrame();
                    active = g;
                    return img;
                } catch (IOException | LinkageError | RuntimeException e) {
                    firstFailed = true;
                    closeQuietly(g);
                    System.err.println("capture: " + g + " unavailable (" + e.getMessage() + "); using sarxos");
                }
            }
            if (active == null) {
                active = second.create();
            }
            return active.grabFrame();
        }

        @Override
        public DeviceCapabilities capabilities() {
            return active == null ? DeviceCapabilities.unknown() : active.capabilities();
        }

        /** Releases the camera; the next grab opens the same backend again. */
        @Override
        public void close() throws IOException {
            if (active != null) {
                active.close();
            }
        }
    }

    /**
     * Ends a development tool that used a webcam: closes {@code resources} in order (stop the frame source, which
     * releases the camera, before anything else), stops sarxos' discovery service if sarxos was used, then
     * {@code System.exit(status)}. A daemon watchdog halts the JVM after {@value #EXIT_GRACE_MS} ms if a shutdown hook
     * hangs (F13b). Never returns.
     */
    public static void exit(int status, AutoCloseable... resources) {
        exit(status, EXIT_GRACE_MS, Arrays.asList(resources), System::exit, s -> Runtime.getRuntime().halt(s));
    }

    /** {@link #exit(int, AutoCloseable...)} with the exit and halt steps injected (tests). */
    public static void exit(int status, long graceMs, List<? extends AutoCloseable> resources, IntConsumer exit,
            IntConsumer halt) {
        for (AutoCloseable r : resources) {
            closeQuietly(r);
        }
        if (sarxosUsed) {
            SarxosWebcamFrameGrabber.stopDiscovery();
        }
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(graceMs);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("exit: the shutdown hooks did not finish in " + graceMs + " ms; halting");
            halt.accept(status);
        }, "exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        exit.accept(status);
    }

    private static void closeQuietly(AutoCloseable r) {
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (Exception | LinkageError e) {
            System.err.println("close failed: " + e);
        }
    }
}
