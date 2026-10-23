package io.mosip.registration.liveness.device.sbi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.BioService;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.Test;

/** Verifies configurable every-Nth-frame processing: N=1, N&gt;1, and invalid N. */
public class SbiStreamFrameSourceStrideTest {

    @Test
    public void strideOfOneDeliversEveryFrameWithNoSequenceGaps() throws Exception {
        byte[] stream = buildStream(6);
        BioService bioService = FakeBioService.returning(() -> new ByteArrayInputStream(stream));
        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device(), "FACE_FULLFACE", 5000L, 1);

        List<Frame> frames = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(6);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); latch.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        source.stop();

        for (int i = 0; i < 6; i++) {
            assertEquals((long) i, frames.get(i).sequence());
        }
    }

    @Test
    public void strideOfThreeDeliversEveryThirdPhysicalFrame() throws Exception {
        byte[] stream = buildStream(9); // 9 physical frames -> delivered sequence 0, 3, 6
        BioService bioService = FakeBioService.returning(() -> new ByteArrayInputStream(stream));
        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device(), "FACE_FULLFACE", 5000L, 3);

        List<Frame> frames = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); latch.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        source.stop();

        assertEquals(3, frames.size());
        assertEquals(0L, frames.get(0).sequence());
        assertEquals(3L, frames.get(1).sequence());
        assertEquals(6L, frames.get(2).sequence());
    }

    @Test
    public void rejectsZeroFrameStride() throws Exception {
        BioService bioService = FakeBioService.returning(() -> new ByteArrayInputStream(new byte[0]));
        try {
            new SbiStreamFrameSource(bioService, device(), "FACE_FULLFACE", 5000L, 0);
            fail("expected IllegalArgumentException for frameStride 0");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }

    @Test
    public void rejectsNegativeFrameStride() throws Exception {
        BioService bioService = FakeBioService.returning(() -> new ByteArrayInputStream(new byte[0]));
        try {
            new SbiStreamFrameSource(bioService, device(), "FACE_FULLFACE", 5000L, -3);
            fail("expected IllegalArgumentException for a negative frameStride");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }

    private static MdmBioDevice device() {
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("stride-test-device");
        return device;
    }

    private static byte[] buildStream(int frameCount) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frameCount; i++) {
            out.write(mjpegFrame(jpeg(8, 8)));
        }
        return out.toByteArray();
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
