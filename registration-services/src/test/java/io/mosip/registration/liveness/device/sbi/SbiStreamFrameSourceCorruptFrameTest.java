package io.mosip.registration.liveness.device.sbi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * Verifies that a single corrupt frame is recoverable without a full reconnect,
 * and precisely documents (with a passing test, not just a Javadoc claim) the one
 * condition under which that recovery does NOT hold - see the class Javadoc on
 * {@link SbiStreamFrameSource} for the full explanation.
 */
public class SbiStreamFrameSourceCorruptFrameTest {

    @Test
    public void aFrameWithARealSoiMarkerButUndecodableBodyIsSkippedWithoutReconnecting() throws Exception {
        byte[] validJpeg1 = jpeg(8, 8);
        // Starts with the real JPEG SOI marker (0xFF 0xD8) so MjpegFrameReader's own
        // SOI-rescan finds it immediately and reads exactly Content-Length bytes -
        // framing stays in sync even though ImageIO cannot decode the rest.
        byte[] corruptButSoiPrefixed = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] validJpeg2 = jpeg(8, 8);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(mjpegFrame(validJpeg1));
        stream.write(mjpegFrame(corruptButSoiPrefixed));
        stream.write(mjpegFrame(validJpeg2));

        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("corrupt-frame-test");
        BioService bioService = FakeBioService.returning(() -> FakeBioService.thenBlock(stream.toByteArray()));

        // maxReconnectAttempts=0 deliberately, to prove this recovery does not rely
        // on reconnect logic at all - it must work purely from the per-frame catch.
        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULLFACE",
                5000L, 1, 0, 0L);

        List<Frame> frames = new CopyOnWriteArrayList<>();
        List<Object[]> errors = new CopyOnWriteArrayList<>();
        CountDownLatch bothFrames = new CountDownLatch(2);

        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); bothFrames.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.add(new Object[]{code, recoverable});
            }
        });

        boolean gotBoth = bothFrames.await(5, TimeUnit.SECONDS);
        // The fixture blocks after the third frame (no EOF), so no unrelated
        // disconnect can race this snapshot.
        List<Object[]> errorsSoFar = new ArrayList<>(errors);
        source.stop();

        assertTrue("both valid frames (before and after the corrupt one) must be delivered", gotBoth);
        assertEquals("exactly one INVALID_FRAME for the corrupt frame", 1,
                errorsSoFar.stream().filter(e -> e[0] == ErrorCode.INVALID_FRAME).count());
        assertTrue("the INVALID_FRAME notification must be recoverable=true (per-frame skip)",
                errorsSoFar.stream().anyMatch(e -> e[0] == ErrorCode.INVALID_FRAME && Boolean.TRUE.equals(e[1])));
        assertFalse("the corrupt frame must never be reported as a stream-level failure",
                errorsSoFar.stream().anyMatch(e -> e[0] == ErrorCode.DEVICE_DISCONNECTED
                        || e[0] == ErrorCode.DEVICE_CONNECTION_FAILED));
        assertEquals("sequence gap of exactly one skipped frame between the two valid deliveries",
                frames.get(0).sequence() + 2, frames.get(1).sequence());
    }

    @Test
    public void garbageWithNoSoiMarkerAtAllDesyncsFramingButDoesNotHangOrCrash() throws Exception {
        // Honest limitation, verified rather than assumed: a payload with no byte
        // equal to 0xFF anywhere makes MjpegFrameReader's SOI-rescan keep looking
        // past the declared Content-Length boundary for *some* 0xFF byte, which
        // then comes from the wrong place in the stream. This is not a defect
        // introduced by this class - it is inherent to the Content-Length + SOI
        // rescan wire format shared with the pre-existing Streamer.java parser.
        // This test only asserts the source degrades safely (surfaces an error,
        // does not hang or throw uncaught), not that the frame after the garbage
        // is recovered - it deliberately is not guaranteed to be.
        byte[] validJpeg1 = jpeg(8, 8);
        byte[] noSoiGarbage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] validJpeg2 = jpeg(8, 8);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(mjpegFrame(validJpeg1));
        stream.write(mjpegFrame(noSoiGarbage));
        stream.write(mjpegFrame(validJpeg2));

        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("desync-test");
        BioService bioService = FakeBioService.returning(() -> new ByteArrayInputStream(stream.toByteArray()));

        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULLFACE",
                5000L, 1, 0, 0L);

        List<Frame> frames = new CopyOnWriteArrayList<>();
        List<Object[]> errors = new CopyOnWriteArrayList<>();
        CountDownLatch firstFrame = new CountDownLatch(1);

        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); firstFrame.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.add(new Object[]{code, recoverable});
            }
        });

        boolean gotFirst = firstFrame.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        source.stop();

        assertTrue("the first valid frame (before the desyncing garbage) is still delivered normally", gotFirst);
        assertTrue("some error must eventually be surfaced rather than hanging forever", !errors.isEmpty());
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
