package io.mosip.registration.liveness.device.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * F13b: the development webcam backends (OpenCV by default, sarxos on request or as the fallback) and the exit order
 * of a development tool: the camera is released before the JVM exits, and a hung shutdown is halted. No camera is
 * opened here.
 */
public class WebcamGrabbersTest {

    private final List<String> steps = new CopyOnWriteArrayList<>();

    @Test
    public void theBackendIsOpencvByDefaultAndSarxosOnlyOnRequest() throws Exception {
        assertTrue("default", WebcamGrabbers.create(null) instanceof WebcamGrabbers.Fallback);
        assertTrue(WebcamGrabbers.create(" OpenCV ") instanceof WebcamGrabbers.Fallback);
        assertEquals("liveness.capture", WebcamGrabbers.CAPTURE_PROPERTY);
        try {
            WebcamGrabbers.create("directshow");
            fail("an unknown backend must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("opencv or sarxos"));
        }
        assertFalse("creating the opencv grabber never touches sarxos", WebcamGrabbers.sarxosUsed());
    }

    @Test
    public void openCvIsUsedWhenItWorksAndTheCameraIsReleasedAndReopened() throws Exception {
        Fake opencv = new Fake("opencv", false);
        WebcamGrabbers.Fallback g = new WebcamGrabbers.Fallback(() -> opencv, () -> {
            throw new AssertionError("no fallback needed");
        });
        assertEquals(8, g.grabFrame().getWidth());
        g.close();
        g.grabFrame();
        assertEquals(Arrays.asList("opencv grab", "opencv close", "opencv grab"), steps);
    }

    @Test
    public void aCameraOpenCvCannotOpenFallsBackToSarxosOnce() throws Exception {
        Fake opencv = new Fake("opencv", true);
        Fake sarxos = new Fake("sarxos", false);
        int[] created = new int[1];
        WebcamGrabbers.Fallback g = new WebcamGrabbers.Fallback(() -> opencv, () -> {
            created[0]++;
            return sarxos;
        });
        g.grabFrame();
        g.grabFrame();
        g.close();
        assertEquals(1, created[0]);
        assertEquals(Arrays.asList("opencv grab", "opencv close", "sarxos grab", "sarxos grab", "sarxos close"), steps);
    }

    @Test
    public void exitReleasesTheCameraFirstThenExits() {
        WebcamGrabbers.exit(3, 60_000L, Arrays.<AutoCloseable>asList(() -> steps.add("stop source"),
                () -> steps.add("close other")),
                s -> steps.add("exit " + s), s -> steps.add("halt " + s));
        assertEquals(Arrays.asList("stop source", "close other", "exit 3"), steps);
    }

    @Test
    public void aFailingCloseDoesNotStopTheExit() {
        WebcamGrabbers.exit(0, 60_000L, Arrays.<AutoCloseable>asList(() -> {
            throw new IOException("already closed");
        }, () -> steps.add("next")), s -> steps.add("exit " + s), s -> steps.add("halt " + s));
        assertEquals(Arrays.asList("next", "exit 0"), steps);
    }

    @Test(timeout = 10_000)
    public void aHungShutdownIsHaltedAfterTheGracePeriod() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        Thread exiting = new Thread(() -> WebcamGrabbers.exit(1, 100L, Arrays.<AutoCloseable>asList(), s -> {
            steps.add("exit " + s);
            try {
                halted.await(); // a shutdown hook that never finishes (F13b)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, s -> {
            steps.add("halt " + s);
            halted.countDown();
        }));
        exiting.start();
        assertTrue("the watchdog halts", halted.await(5, TimeUnit.SECONDS));
        exiting.join(5_000);
        assertEquals(Arrays.asList("exit 1", "halt 1"), steps);
    }

    /** A grabber that records its calls; with {@code fail}, every grab fails as a camera that does not open. */
    private final class Fake implements MockFrameSource.WebcamFrameGrabber {
        private final String name;
        private final boolean fail;

        Fake(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public BufferedImage grabFrame() throws IOException {
            steps.add(name + " grab");
            if (fail) {
                throw new IOException(name + " could not open camera 0");
            }
            return new BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR);
        }

        @Override
        public void close() {
            steps.add(name + " close");
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
