package io.mosip.registration.liveness.device.mock;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
 * sarxos is never loaded and registers no hook. {@link #exit} waits until the tool's camera is released and stops
 * sarxos' discovery service, so the hook has nothing left to do; a watchdog halts the JVM if the exit still hangs.
 */
public final class WebcamGrabbers {

    /** System property naming the backend: opencv (default) or sarxos. */
    public static final String CAPTURE_PROPERTY = "liveness.capture";
    /** How long {@link #exit} may take (closes, the camera's release, the shutdown hooks) before it halts the JVM. */
    public static final long EXIT_GRACE_MS = 5_000L;
    /** How long {@link #exit} waits for the frame source's worker to release the camera. */
    static final long RELEASE_WAIT_MS = 2_000L;

    private static volatile boolean sarxosUsed;
    /** The grabbers of {@link #create(String)} that hold a camera (grabbed, not closed since). Guarded by itself. */
    private static final Set<Held> HELD = new HashSet<>();

    private WebcamGrabbers() {
    }

    /** The backend named by {@value #CAPTURE_PROPERTY} (default opencv). */
    public static MockFrameSource.WebcamFrameGrabber create() throws IOException {
        return create(System.getProperty(CAPTURE_PROPERTY, "opencv"));
    }

    /** opencv (with the sarxos fallback) or sarxos; {@link #exit} waits until its camera is released. */
    public static MockFrameSource.WebcamFrameGrabber create(String backend) throws IOException {
        String b = backend == null ? "opencv" : backend.trim().toLowerCase(Locale.ROOT);
        switch (b) {
            case "opencv":
                return new Held(new Fallback(OpenCvWebcamFrameGrabber::new, WebcamGrabbers::sarxos));
            case "sarxos":
                return new Held(sarxos());
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

        /** The backend in use, for messages. */
        @Override
        public String toString() {
            return active == null ? "webcam" : active.toString();
        }
    }

    /**
     * A grabber of {@link #create(String)}: counts its camera as held from the first grab until {@link #close()}, so
     * {@link #exit} can wait for it, and reports a backend's unchecked failure as the {@link IOException} that
     * {@link MockFrameSource} turns into {@code onError} (an OpenCV binding throws {@code CvException}).
     */
    static final class Held implements MockFrameSource.WebcamFrameGrabber {
        private final MockFrameSource.WebcamFrameGrabber camera;

        Held(MockFrameSource.WebcamFrameGrabber camera) {
            this.camera = camera;
        }

        @Override
        public BufferedImage grabFrame() throws IOException {
            hold(this, true);
            try {
                return camera.grabFrame();
            } catch (RuntimeException e) {
                throw new IOException(camera + ": " + e, e);
            }
        }

        @Override
        public DeviceCapabilities capabilities() {
            return camera.capabilities();
        }

        @Override
        public void close() throws IOException {
            try {
                camera.close();
            } finally {
                hold(this, false);
            }
        }

        @Override
        public String toString() {
            return camera.toString();
        }
    }

    private static void hold(Held grabber, boolean held) {
        synchronized (HELD) {
            if (held) {
                HELD.add(grabber);
            } else if (HELD.remove(grabber)) {
                HELD.notifyAll();
            }
        }
    }

    /** Waits until no grabber of {@link #create(String)} holds a camera; false if one still does after the wait. */
    static boolean awaitReleased(long timeoutMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (HELD) {
            while (!HELD.isEmpty()) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(HELD, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Ends a development tool that used a webcam: arms a daemon watchdog that halts the JVM after
     * {@value #EXIT_GRACE_MS} ms (a close or a shutdown hook that hangs, F13b), closes {@code resources} in order (stop
     * the frame source before anything else), waits up to {@value #RELEASE_WAIT_MS} ms until the frame source's worker
     * has released the camera (stop only signals it; a native grab in progress finishes first), stops sarxos'
     * discovery service if sarxos was used, then {@code System.exit(status)}. Never returns.
     */
    public static void exit(int status, AutoCloseable... resources) {
        exit(status, EXIT_GRACE_MS, Arrays.asList(resources), System::exit, s -> Runtime.getRuntime().halt(s));
    }

    /** {@link #exit(int, AutoCloseable...)} with the exit and halt steps injected (tests). */
    public static void exit(int status, long graceMs, List<? extends AutoCloseable> resources, IntConsumer exit,
            IntConsumer halt) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(graceMs);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("exit: did not finish in " + graceMs + " ms; halting");
            halt.accept(status);
        }, "exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        for (AutoCloseable r : resources) {
            closeQuietly(r);
        }
        if (!awaitReleased(RELEASE_WAIT_MS)) {
            System.err.println("exit: the camera was not released in " + RELEASE_WAIT_MS + " ms");
        }
        if (sarxosUsed) {
            SarxosWebcamFrameGrabber.stopDiscovery();
        }
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
