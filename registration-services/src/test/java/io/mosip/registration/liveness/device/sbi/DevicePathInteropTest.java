package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.device.LivenessTestSupport;
import io.mosip.registration.liveness.device.LivenessTestSupport.Scene;
import io.mosip.registration.liveness.device.mock.MockFrameSource;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Interoperability: the same recording through the mock device (MockFrameSource video mode) and through the SBI
 * adapter (SbiStreamFrameSource reading the identical bytes from an MDS /stream) must give the same frames and the
 * same liveness decision. The live MockMDS check (patched MockMDS video mode + SbiStreamProbe) is documented in
 * mock-mds-patch/README.md.
 */
public class DevicePathInteropTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A recording whose frames all differ, so frame order can be compared. */
    private static byte[] distinctRecording(int frames) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(30, 120 + i * 4, 40 + i * 3));
            g.fillRect(0, 0, 64, 48);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(i * 2, 10, 4, 20);
            g.dispose();
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "jpg", jpeg);
            out.write(LivenessTestSupport.part(jpeg.toByteArray()));
        }
        return out.toByteArray();
    }

    private static List<String> collect(FrameSource source, int n) throws Exception {
        final List<String> hashes = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch latch = new CountDownLatch(n);
        source.start(new FrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                if (hashes.size() < n) {
                    hashes.add(frame.width() + "x" + frame.height() + ":" + sha(frame.rgb()));
                    latch.countDown();
                }
            }

            @Override
            public void onError(ErrorCode code, String detail, boolean recoverable) {
            }
        });
        assertTrue("frames should arrive", latch.await(10, TimeUnit.SECONDS));
        source.stop();
        return new ArrayList<>(hashes.subList(0, n));
    }

    private static String sha(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i++) s.append(String.format("%02x", d[i]));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void mockDeviceAndSbiAdapterDeliverIdenticalFramesInTheSameOrder() throws Exception {
        final byte[] recording = distinctRecording(20);
        File file = tmp.newFile("clip.mjpeg");
        Files.write(file.toPath(), recording);

        List<String> viaMock = collect(MockFrameSource.ofVideoFile(file.toPath(), 60.0, false), 20);
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("interop");
        SbiStreamFrameSource sbi = new SbiStreamFrameSource(
                FakeBioService.returning(() -> FakeBioService.thenBlock(recording)), device, "FACE_FULLFACE");
        List<String> viaSbi = collect(sbi, 20);

        assertEquals(20, new java.util.HashSet<>(viaMock).size());
        assertEquals("both device paths must deliver byte-identical RGB frames in order", viaMock, viaSbi);
    }

    private static LivenessResult decide(FrameSource source, LivenessTestSupport.Audit audit) throws Exception {
        Map<String, String> cfg = LivenessTestSupport.config(LivenessPolicy.PREFIX + "default.maxRetries", "0");
        DefaultLivenessService svc = LivenessTestSupport.colourService(cfg, audit);
        LivenessTestSupport.Recorder rec = new LivenessTestSupport.Recorder();
        svc.newSession(WorkflowType.RESIDENT).start(source, rec);
        return rec.await(20);
    }

    @Test
    public void bothDevicePathsReachTheSameLivenessDecision() throws Exception {
        for (Scene scene : new Scene[] {Scene.LIVE, Scene.ATTACK}) {
            File file = tmp.newFile(scene + ".mjpeg");
            Files.write(file.toPath(), LivenessTestSupport.recording(scene, 30));
            final byte[] part = LivenessTestSupport.part(LivenessTestSupport.jpeg(scene));

            LivenessTestSupport.Audit auditMock = new LivenessTestSupport.Audit();
            LivenessResult viaMock = decide(MockFrameSource.ofVideoFile(file.toPath(), 30.0, true), auditMock);

            MdmBioDevice device = new MdmBioDevice();
            device.setDeviceId("interop");
            LivenessTestSupport.Audit auditSbi = new LivenessTestSupport.Audit();
            LivenessResult viaSbi = decide(new SbiStreamFrameSource(
                    FakeBioService.returning(() -> LivenessTestSupport.liveStream(part, 33, -1)), device,
                    "FACE_FULLFACE"), auditSbi);

            assertEquals(scene + ": decision", viaMock.decision, viaSbi.decision);
            assertEquals(scene + ": failure code", viaMock.failureCode, viaSbi.failureCode);
            assertEquals(scene + ": pass path", viaMock.passPath, viaSbi.passPath);
            if (scene == Scene.LIVE) {
                assertTrue(viaMock.passed());
            } else {
                assertEquals(ErrorCode.MAX_RETRIES_EXCEEDED, viaMock.failureCode);
                assertEquals("PAD_FAILURE", auditMock.finals.get(0).get("lastCause"));
                assertEquals("PAD_FAILURE", auditSbi.finals.get(0).get("lastCause"));
            }
        }
    }

    @Test
    public void recordingsAreReadableByBothParsers() throws Exception {
        byte[] recording = distinctRecording(3);
        ByteArrayInputStream in = new ByteArrayInputStream(recording);
        for (int i = 0; i < 3; i++) {
            assertTrue(io.mosip.registration.liveness.device.util.MjpegFrameReader.readNextFrame(in).length > 0);
        }
    }
}
