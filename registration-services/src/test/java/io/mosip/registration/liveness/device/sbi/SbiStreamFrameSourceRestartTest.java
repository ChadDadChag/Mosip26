package io.mosip.registration.liveness.device.sbi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.BioService;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * stop() followed quickly by start() (what a UI retry does) while the previous
 * worker is still stuck inside a blocking getStream() call. The stale worker must
 * not deliver into the new session, and its cleanup must not stop the new session.
 */
public class SbiStreamFrameSourceRestartTest {

    @Test
    public void staleWorkerFromAStoppedSessionCannotDisturbTheNextSession() throws Exception {
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        BioService bioService = mock(BioService.class);
        when(bioService.getStream(any(MdmBioDevice.class), anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                // Old session: an HTTP connect that stop() cannot interrupt.
                firstCallEntered.countDown();
                awaitUninterruptibly(releaseFirstCall);
                return endless(jpeg(4, 4));
            }
            return endless(jpeg(8, 8));
        });

        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("restart-device");
        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULLFACE",
                5000L, 1, 0, 0L);

        List<Frame> oldSessionFrames = new CopyOnWriteArrayList<>();
        source.start(listener(oldSessionFrames, new CopyOnWriteArrayList<>()));
        assertTrue(firstCallEntered.await(2, TimeUnit.SECONDS));
        source.stop();

        List<Frame> newSessionFrames = new CopyOnWriteArrayList<>();
        List<ErrorCode> newSessionErrors = new CopyOnWriteArrayList<>();
        source.start(listener(newSessionFrames, newSessionErrors));
        waitFor(() -> !newSessionFrames.isEmpty(), 2000);

        releaseFirstCall.countDown();   // the stale worker now wakes up
        Thread.sleep(500);

        try {
            assertTrue("new session must still be running after the stale worker exits", source.isRunning());
            assertTrue("old listener must receive nothing after stop()", oldSessionFrames.isEmpty());
            for (Frame f : newSessionFrames) {
                assertEquals("frames from the stale stream leaked into the new session", 8, f.width());
            }
            int before = newSessionFrames.size();
            Thread.sleep(200);
            assertTrue("new session must keep delivering", newSessionFrames.size() > before);
            assertTrue("no errors expected in the new session: " + newSessionErrors, newSessionErrors.isEmpty());
        } finally {
            source.stop();
        }
    }

    private static FrameSource.Listener listener(List<Frame> frames, List<ErrorCode> errors) {
        return new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { errors.add(code); }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // keep blocking, like a native socket connect
            }
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("condition not met within " + timeoutMs + " ms", condition.getAsBoolean());
    }

    /** Repeats one MJPEG frame (paced, ~100 fps) until closed. */
    private static InputStream endless(byte[] jpeg) throws IOException {
        byte[] frame = mjpegFrame(jpeg);
        return new InputStream() {
            private volatile boolean closed;
            private int pos;

            @Override public int read() throws IOException {
                if (closed) {
                    throw new IOException("closed");
                }
                if (pos == frame.length) {
                    pos = 0;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted", e);
                    }
                }
                return frame[pos++] & 0xFF;
            }

            @Override public void close() {
                closed = true;
            }
        };
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private static byte[] mjpegFrame(byte[] jpeg) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: "
                + jpeg.length + "\r\n\r\n").getBytes("UTF-8"));
        output.write(jpeg);
        output.write("\r\n\r\n".getBytes("UTF-8"));
        return output.toByteArray();
    }
}
