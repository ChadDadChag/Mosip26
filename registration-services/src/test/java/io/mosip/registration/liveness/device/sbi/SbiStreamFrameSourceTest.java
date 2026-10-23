package io.mosip.registration.liveness.device.sbi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mosip.registration.exception.RegBaseCheckedException;
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
import java.io.SequenceInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import org.junit.Test;

public class SbiStreamFrameSourceTest {

    @Test
    public void convertsStreamFramesIntoLivenessFrames() throws Exception {
        byte[] jpeg1 = jpeg(2, 1);
        byte[] jpeg2 = jpeg(1, 2);
        byte[] twoFrames = concat(frame(jpeg1), frame(jpeg2));

        BioService bioService = mock(BioService.class);
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("device-1");
        device.setSerialNumber("serial-1");
        device.setDeviceMake("Vendor");
        device.setDeviceModel("Model");
        device.setSpecVersion("1.0");
        device.setCertification("L1");

        // After the two real frames, block instead of hitting EOF - this models
        // "the device just hasn't sent frame 3 yet" rather than "the stream
        // closed", so stop() deterministically interrupts a blocked read rather
        // than racing an already-in-flight EOF/disconnect error.
        InputStream stream = new SequenceInputStream(
                new ByteArrayInputStream(twoFrames), new NeverEndingBlockingStream());

        when(bioService.getStream(device, "FACE_FULL FACE")).thenReturn(stream);

        SbiStreamFrameSource source = new SbiStreamFrameSource(
                bioService, device, "FACE_FULL FACE", 5000L);

        CountDownLatch frames = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger lastWidth = new AtomicInteger();

        source.start(new FrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                lastWidth.set(frame.width());
                frames.countDown();
            }

            @Override
            public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.incrementAndGet();
            }
        });

        assertTrue(frames.await(5, TimeUnit.SECONDS));
        source.stop();
        Thread.sleep(200); // let the worker observe the stop-induced close, if any

        assertEquals(1, lastWidth.get());
        assertEquals("device-1", source.info().deviceId);
        assertEquals("L1", source.info().certificationLevel);
        // MDS declares only the wire format; resolutions are observed from the stream.
        assertEquals(java.util.Collections.singletonList("MJPEG"), source.info().capabilities.streamFormats);
        assertEquals(io.mosip.registration.liveness.api.DeviceCapabilities.Origin.OBSERVED,
                source.info().capabilities.origin);
        assertEquals(2, source.info().capabilities.resolutions.size());
        assertEquals(2, source.info().maxWidth * source.info().maxHeight);
        // Deterministic now: stop() sets `stopping` before closing the stream, so
        // the resulting IOException on the blocked read is always suppressed.
        assertEquals(0, errors.get());
    }

    @Test
    public void listenerExceptionFromOnFrameDoesNotStopTheStreamOrGetMisreported() throws Exception {
        byte[] repeatingFrame = frame(jpeg(2, 2));
        byte[] threeFrames = concat(concat(repeatingFrame, repeatingFrame), repeatingFrame);

        BioService bioService = mock(BioService.class);
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("device-1");
        when(bioService.getStream(device, "FACE_FULL FACE"))
                .thenReturn(FakeBioService.thenBlock(threeFrames));

        // maxReconnectAttempts=0: this test isolates listener-exception handling,
        // which is orthogonal to the reconnect feature (see
        // SbiStreamFrameSourceReconnectTest) - without disabling it, the finite
        // 3-frame stream naturally hitting EOF right after the 3rd delivery would
        // also trigger a legitimate DEVICE_DISCONNECTED/reconnect notification,
        // which is correct new behavior but not what this specific test checks.
        SbiStreamFrameSource source = new SbiStreamFrameSource(bioService, device, "FACE_FULL FACE",
                5000L, 1, 0, 0L);

        CountDownLatch attempts = new CountDownLatch(3);
        List<ErrorCode> errors = new CopyOnWriteArrayList<>();

        source.start(new FrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                attempts.countDown();
                throw new RuntimeException("simulated bug in a downstream (engine) listener");
            }

            @Override
            public void onError(ErrorCode code, String detail, boolean recoverable) {
                errors.add(code);
            }
        });

        assertTrue("stream must keep delivering frames even though the listener always throws",
                attempts.await(5, TimeUnit.SECONDS));
        // The fixture blocks after its frames (no EOF), so no unrelated disconnect
        // can race this snapshot.
        List<ErrorCode> errorsAtThreeFrames = new java.util.ArrayList<>(errors);
        source.stop();

        assertTrue("a listener bug must never be reported as a device/frame ErrorCode",
                errorsAtThreeFrames.isEmpty());
    }

    @Test
    public void factoryFailsFastWhenDeviceIsNoLongerAvailable() throws Exception {
        io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory deviceFactory =
                mock(io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory.class);
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("device-1");
        when(deviceFactory.getDeviceInfoByModality("FACE_FULL FACE")).thenReturn(device);
        when(deviceFactory.isDeviceAvailable(device)).thenReturn(false);

        SbiStreamFrameSourceFactory factory =
                new SbiStreamFrameSourceFactory(deviceFactory, mock(BioService.class));

        try {
            factory.create("FACE_FULL FACE");
            org.junit.Assert.fail("expected RegBaseCheckedException when the device is unavailable");
        } catch (RegBaseCheckedException expected) {
            assertEquals("REG-MDS-001", expected.getErrorCode());
        }
    }

    @Test
    public void factoryCreatesSourceWhenDeviceIsAvailable() throws Exception {
        io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory deviceFactory =
                mock(io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory.class);
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("device-1");
        when(deviceFactory.getDeviceInfoByModality("FACE_FULL FACE")).thenReturn(device);
        when(deviceFactory.isDeviceAvailable(device)).thenReturn(true);

        SbiStreamFrameSourceFactory factory =
                new SbiStreamFrameSourceFactory(deviceFactory, mock(BioService.class));

        FrameSource source = factory.create("FACE_FULL FACE");
        assertNotNull(source);
        assertFalse(source.isRunning());
    }

    @Test
    public void factoryPassesTheConfiguredFrameStrideToTheSource() throws Exception {
        io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory deviceFactory =
                mock(io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory.class);
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("device-1");
        when(deviceFactory.getDeviceInfoByModality("FACE_FULL FACE")).thenReturn(device);
        when(deviceFactory.isDeviceAvailable(device)).thenReturn(true);

        byte[] oneFrame = frame(jpeg(2, 2));
        byte[] fourFrames = concat(concat(oneFrame, oneFrame), concat(oneFrame, oneFrame));
        BioService bioService = FakeBioService.returning(() -> FakeBioService.thenBlock(fourFrames));

        FrameSource source = new SbiStreamFrameSourceFactory(deviceFactory, bioService, 5000L, 2, 0, 0L)
                .create("FACE_FULL FACE");
        List<Long> sequences = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { sequences.add(frame.sequence()); two.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        });
        assertTrue(two.await(5, TimeUnit.SECONDS));
        source.stop();
        assertEquals(java.util.Arrays.asList(0L, 2L), sequences);
    }

    @Test
    public void immediateStopAfterStartDoesNotLeaveTheSourceRunning() throws Exception {
        // Regression test for the start()/stop() race: stop() called before the
        // blocking getStream() call even returns must not leave the source
        // running, and must not leak the worker thread.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread accepter = new Thread(() -> {
                try {
                    Socket socket = serverSocket.accept();
                    Thread.sleep(3000); // slow "device" - connects but is in no hurry to respond
                    socket.close();
                } catch (Exception ignored) {
                    // test teardown
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            BioService slowBioService = mock(BioService.class);
            MdmBioDevice device = new MdmBioDevice();
            device.setDeviceId("device-1");
            when(slowBioService.getStream(device, "FACE_FULL FACE")).thenAnswer(invocation -> {
                Socket socket = new Socket("127.0.0.1", port);
                return socket.getInputStream();
            });

            SbiStreamFrameSource source = new SbiStreamFrameSource(slowBioService, device, "FACE_FULL FACE", 5000L);
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) { }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });
            source.stop();

            Thread.sleep(500);
            assertFalse("source must not be running after an immediate stop()", source.isRunning());
            accepter.interrupt();
        }
    }

    /** Never reaches EOF and never throws on its own - only closing it produces an IOException. */
    private static final class NeverEndingBlockingStream extends InputStream {
        private volatile boolean closed;

        @Override
        public int read() throws IOException {
            while (!closed) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while blocked", e);
                }
            }
            throw new IOException("stream closed");
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private static byte[] frame(byte[] jpeg) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("--BoundaryString\r\nContent-type: image/jpeg\r\n".getBytes("UTF-8"));
        output.write(("Content-Length: " + jpeg.length + "\r\n\r\n").getBytes("UTF-8"));
        output.write(jpeg);
        output.write("\r\n\r\n".getBytes("UTF-8"));
        return output.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
