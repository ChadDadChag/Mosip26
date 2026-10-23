package io.mosip.registration.liveness.device.sbi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
 * Verifies automatic reconnect behavior, distinct from plain stop()/start()
 * lifecycle handling: a stream that had been delivering frames and then drops
 * should be retried automatically, while the very first connection attempt
 * failing should not be silently retried.
 */
public class SbiStreamFrameSourceReconnectTest {

    @Test
    public void reconnectsAfterMidStreamDisconnectAndResumesDeliveryWithContinuousSequence() throws Exception {
        MdmBioDevice device = device();
        AtomicInteger connectionAttempts = new AtomicInteger();

        BioService bioService = FakeBioService.returning(() -> {
            connectionAttempts.incrementAndGet();
            return framesFollowedByDisconnect(2);
        });

        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULLFACE",
                5000L, 1, 2, 50L);

        List<Frame> frames = new CopyOnWriteArrayList<>();
        List<Object[]> errors = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(4);

        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); latch.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.add(new Object[]{code, recoverable});
            }
        });

        boolean got4 = latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        source.stop();

        assertTrue("frames should arrive from more than one connection attempt", got4);
        assertTrue("a reconnect must have actually happened", connectionAttempts.get() >= 2);

        long[] sequences = frames.stream().mapToLong(Frame::sequence).sorted().toArray();
        for (int i = 1; i < sequences.length; i++) {
            assertTrue("sequence must strictly increase across the reconnect boundary, not reset to 0",
                    sequences[i] > sequences[i - 1]);
        }

        assertTrue("at least one intermediate disconnect must be reported as recoverable=true",
                errors.stream().anyMatch(e -> e[0] == ErrorCode.DEVICE_DISCONNECTED && Boolean.TRUE.equals(e[1])));
    }

    @Test
    public void disablingReconnectPreservesThePreExistingImmediateFailureBehavior() throws Exception {
        MdmBioDevice device = device();
        AtomicInteger connectionAttempts = new AtomicInteger();

        BioService bioService = FakeBioService.returning(() -> {
            connectionAttempts.incrementAndGet();
            return framesFollowedByDisconnect(2);
        });

        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULLFACE",
                5000L, 1, 0, 50L); // maxReconnectAttempts = 0

        CountDownLatch frames = new CountDownLatch(2);
        List<Object[]> errors = new CopyOnWriteArrayList<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.add(new Object[]{code, recoverable});
                errorLatch.countDown();
            }
        });

        assertTrue(frames.await(3, TimeUnit.SECONDS));
        assertTrue(errorLatch.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);
        source.stop();

        assertEquals("exactly one connection attempt with reconnect disabled", 1, connectionAttempts.get());
        assertEquals(false, errors.get(0)[1]);
    }

    @Test
    public void reportsAFinalNonRecoverableFailureOnceReconnectAttemptsAreExhausted() throws Exception {
        MdmBioDevice device = device();
        AtomicInteger connectionAttempts = new AtomicInteger();

        BioService bioService = FakeBioService.returning(() -> {
            connectionAttempts.incrementAndGet();
            return framesFollowedByDisconnect(1);
        });

        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULLFACE",
                5000L, 1, 1, 30L); // maxReconnectAttempts = 1

        List<Object[]> errors = new CopyOnWriteArrayList<>();
        CountDownLatch finalFailure = new CountDownLatch(1);

        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.add(new Object[]{code, recoverable});
                if (!recoverable) {
                    finalFailure.countDown();
                }
            }
        });

        assertTrue(finalFailure.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);
        source.stop();

        assertEquals("initial attempt + exactly one reconnect attempt", 2, connectionAttempts.get());
        long recoverableCount = errors.stream().filter(e -> Boolean.TRUE.equals(e[1])).count();
        long finalCount = errors.stream().filter(e -> Boolean.FALSE.equals(e[1])).count();
        assertEquals("exactly one intermediate recoverable notification", 1, recoverableCount);
        assertEquals("exactly one final non-recoverable notification", 1, finalCount);
    }

    private static MdmBioDevice device() {
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("reconnect-test-device");
        return device;
    }

    /** A finite stream that delivers {@code frameCount} frames then naturally EOFs (simulated disconnect). */
    private static InputStream framesFollowedByDisconnect(int frameCount) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frameCount; i++) {
            out.write(mjpegFrame(jpeg(8, 8)));
        }
        return new ByteArrayInputStream(out.toByteArray());
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
