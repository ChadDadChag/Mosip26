package io.mosip.registration.liveness.device.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.mosip.registration.liveness.api.DeviceCapabilities;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.device.util.CapabilityProbe;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.Test;

/** Recording fixtures, mock capabilities/certification, and capability probing. */
public class MjpegRecorderTest {

    @Test
    public void imagesConvertedToARecordingPlayBackThroughTheVideoMode() throws Exception {
        Path dir = Files.createTempDirectory("mjpeg-recorder-images");
        Path recording = dir.resolve("clip.mjpeg");
        try {
            writeSolid(dir.resolve("a.jpg"), 16, 12, Color.RED, "jpg");
            writeSolid(dir.resolve("b.png"), 16, 12, Color.GREEN, "png");

            assertEquals(2, MjpegRecorder.fromImages(dir, recording));

            MockFrameSource playback = MockFrameSource.ofVideoFile(recording, 50.0, false);
            List<Frame> frames = collect(playback, 2);
            assertEquals(2, frames.size());
            assertEquals(16, frames.get(0).width());
            assertEquals(12, frames.get(0).height());
            assertTrue("first frame should be red-ish", (frames.get(0).rgb()[0] & 0xFF) > 200);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void recordsAnyFrameSourceAndTheRecordingReplaysIdenticalDimensions() throws Exception {
        Path dir = Files.createTempDirectory("mjpeg-recorder-source");
        try {
            writeSolid(dir.resolve("face.jpg"), 20, 10, Color.BLUE, "jpg");
            MockFrameSource live = new MockFrameSource(dir.resolve("face.jpg"), 50.0);
            Path recording = dir.resolve("recorded.mjpeg");

            assertEquals(5, MjpegRecorder.record(live, recording, 5, 5000L));
            assertTrue(!live.isRunning());

            MockFrameSource playback = MockFrameSource.ofVideoFile(recording, 50.0, false);
            assertEquals("MJPEG", playback.info().capabilities.streamFormats.get(0));
            List<Frame> frames = collect(playback, 5);
            assertEquals(5, frames.size());
            for (Frame f : frames) {
                assertEquals(20, f.width());
                assertEquals(10, f.height());
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void rejectsBytesThatAreNotAJpeg() throws Exception {
        try (MjpegRecorder recorder = new MjpegRecorder(new ByteArrayOutputStream())) {
            recorder.writeJpeg(new byte[]{1, 2, 3});
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SOI"));
        }
    }

    @Test
    public void mockDeclaresCapabilitiesAndCanPoseAsAnL1Device() throws Exception {
        Path dir = Files.createTempDirectory("mock-capabilities");
        try {
            writeSolid(dir.resolve("a.jpg"), 32, 24, Color.GRAY, "jpg");
            writeSolid(dir.resolve("b.png"), 64, 48, Color.GRAY, "png");
            MockFrameSource source = new MockFrameSource(dir, 15.0);

            DeviceCapabilities caps = source.info().capabilities;
            assertEquals(DeviceCapabilities.Origin.DECLARED, caps.origin);
            assertEquals(java.util.Arrays.asList("JPEG", "PNG"), caps.streamFormats);
            assertEquals(2, caps.resolutions.size());
            assertEquals(java.util.Collections.singletonList(15), caps.frameRates);
            assertEquals(64, source.info().maxWidth);
            assertEquals(15, source.info().maxFps);
            assertEquals("NONE", source.info().certificationLevel);

            assertEquals("L1", source.asCertificationLevel("L1").info().certificationLevel);
            try {
                source.asCertificationLevel("L9");
                fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // correct
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void webcamModeUsesTheGrabbersDeclaredResolutions() throws Exception {
        MockFrameSource.WebcamFrameGrabber grabber = new MockFrameSource.WebcamFrameGrabber() {
            @Override public BufferedImage grabFrame() {
                return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            }
            @Override public DeviceCapabilities capabilities() {
                return new DeviceCapabilities(null,
                        java.util.Arrays.asList(new DeviceCapabilities.Resolution(640, 480)),
                        null, DeviceCapabilities.Origin.DECLARED);
            }
        };
        MockFrameSource source = MockFrameSource.ofWebcam(grabber, 10.0);
        assertEquals(640, source.info().maxWidth);
        assertEquals(10, source.info().maxFps);
    }

    @Test
    public void probeObservesResolutionAndFrameRateIncludingStride() throws Exception {
        Path dir = Files.createTempDirectory("capability-probe");
        try {
            writeSolid(dir.resolve("a.jpg"), 24, 16, Color.WHITE, "jpg");
            // 40 fps device, every 2nd frame delivered: probe must still report ~40, not ~20.
            MockFrameSource source = new MockFrameSource(dir, 40.0, 2);

            DeviceCapabilities observed = CapabilityProbe.probe(source, 8, 5000L);

            assertEquals(DeviceCapabilities.Origin.OBSERVED, observed.origin);
            assertEquals(java.util.Collections.singletonList(new DeviceCapabilities.Resolution(24, 16)),
                    observed.resolutions);
            assertEquals(java.util.Collections.singletonList("JPEG"), observed.streamFormats);
            int fps = observed.maxFrameRate();
            assertTrue("measured fps should be near 40, was " + fps, fps >= 25 && fps <= 50);
            assertTrue("probe must stop the source", !source.isRunning());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void probeReportsTheDeviceErrorWhenNoFrameArrives() throws Exception {
        MockFrameSource source = MockFrameSource.ofWebcam(() -> {
            throw new IOException("unplugged");
        }, 20.0);
        try {
            CapabilityProbe.probe(source, 3, 2000L);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("CAMERA_UNAVAILABLE"));
        }
    }

    private static List<Frame> collect(FrameSource source, int count) throws InterruptedException {
        List<Frame> frames = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(count);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) {
                if (frames.size() < count) {
                    frames.add(frame);
                    latch.countDown();
                }
            }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        });
        latch.await(5, TimeUnit.SECONDS);
        source.stop();
        return frames;
    }

    private static void writeSolid(Path path, int width, int height, Color color, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ImageIO.write(image, format, path.toFile());
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
