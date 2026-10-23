package io.mosip.registration.liveness.device.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * stop() followed quickly by start() while the previous worker is stuck inside a
 * native, uninterruptible grabFrame() call. The stale worker must not deliver into
 * the new session, keep running as a second loop, or close the grabber under it.
 */
public class MockFrameSourceRestartTest {

    @Test
    public void staleWorkerFromAStoppedSessionCannotDisturbTheNextSession() throws Exception {
        CountDownLatch firstGrabEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstGrab = new CountDownLatch(1);
        AtomicInteger grabs = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();

        MockFrameSource.WebcamFrameGrabber grabber = new MockFrameSource.WebcamFrameGrabber() {
            @Override public BufferedImage grabFrame() {
                if (grabs.incrementAndGet() == 1) {
                    firstGrabEntered.countDown();
                    awaitUninterruptibly(releaseFirstGrab);
                    return new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
                }
                return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            }
            @Override public void close() { closes.incrementAndGet(); }
        };

        MockFrameSource source = MockFrameSource.ofWebcam(grabber, 50.0);
        List<Frame> oldFrames = new CopyOnWriteArrayList<>();
        source.start(listener(oldFrames));
        assertTrue(firstGrabEntered.await(2, TimeUnit.SECONDS));
        source.stop();

        List<Frame> newFrames = new CopyOnWriteArrayList<>();
        source.start(listener(newFrames));
        Thread.sleep(200);
        releaseFirstGrab.countDown();
        Thread.sleep(400);

        try {
            assertTrue("new session must still be running", source.isRunning());
            assertTrue("old listener must receive nothing after stop()", oldFrames.isEmpty());
            for (Frame f : newFrames) {
                assertEquals("stale frame leaked into the new session", 8, f.width());
            }
            assertEquals("grabber must not be closed while the new session uses it", 0, closes.get());
            assertEquals("exactly one worker loop must be running", 1, liveMockWorkers());
        } finally {
            source.stop();
        }
    }

    private static long liveMockWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals("LIVENESS_MOCK_FRAME_THREAD"))
                .count();
    }

    private static FrameSource.Listener listener(List<Frame> frames) {
        return new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // keep blocking, like a native driver call
            }
        }
    }
}
