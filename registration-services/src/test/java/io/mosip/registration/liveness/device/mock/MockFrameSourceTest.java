package io.mosip.registration.liveness.device.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

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
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.Test;

public class MockFrameSourceTest {

    @Test
    public void deliversFramesFromASingleImageAtTheConfiguredFps() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-single");
        try {
            writeSolidJpeg(dir.resolve("only.jpg"), 16, 16, Color.RED);

            MockFrameSource source = new MockFrameSource(dir, 20.0); // fast, deterministic test
            assertEquals(16, source.info().maxWidth);
            assertEquals(16, source.info().maxHeight);
            assertTrue(source.info().deviceId.startsWith("mock:"));
            assertEquals("NONE", source.info().certificationLevel);

            List<Frame> frames = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) {
                    frames.add(frame);
                    if (frames.size() <= 3) latch.countDown();
                }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });

            assertTrue("expected at least 3 cycled frames", latch.await(5, TimeUnit.SECONDS));
            source.stop();

            assertFalse(source.isRunning());
            Frame first = frames.get(0);
            assertEquals(16 * 16 * 3, first.rgb().length);
            assertEquals(0L, first.sequence());
            assertEquals(1L, frames.get(1).sequence());
            assertTrue("timestamps should be non-decreasing",
                    frames.get(1).timestampMs() >= frames.get(0).timestampMs());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void cyclesThroughAFolderOfImagesInSortedOrder() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-folder");
        try {
            writeSolidJpeg(dir.resolve("a_red.jpg"), 16, 16, Color.RED);
            writeSolidJpeg(dir.resolve("b_green.jpg"), 16, 16, Color.GREEN);

            MockFrameSource source = new MockFrameSource(dir, 20.0);
            List<Frame> frames = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(4); // two full cycles
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) {
                    frames.add(frame);
                    if (frames.size() <= 4) latch.countDown();
                }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            source.stop();

            // a_red.jpg sorts before b_green.jpg, so frame 0 and 2 should be red-dominant,
            // frame 1 and 3 green-dominant, confirming deterministic sorted cycling.
            assertTrue(isPredominantly(frames.get(0), 0));
            assertTrue(isPredominantly(frames.get(1), 1));
            assertTrue(isPredominantly(frames.get(2), 0));
            assertTrue(isPredominantly(frames.get(3), 1));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void stopIsIdempotentAndLeavesNoRunningWorker() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-stop");
        try {
            writeSolidJpeg(dir.resolve("only.jpg"), 8, 8, Color.BLUE);
            MockFrameSource source = new MockFrameSource(dir, 50.0);
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) { }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });
            Thread.sleep(100);
            source.stop();
            source.stop(); // must not throw
            assertFalse(source.isRunning());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void listenerExceptionDoesNotStopFrameDelivery() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-listener-throws");
        try {
            writeSolidJpeg(dir.resolve("only.jpg"), 8, 8, Color.YELLOW);
            MockFrameSource source = new MockFrameSource(dir, 20.0);

            CountDownLatch attempts = new CountDownLatch(3);
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) {
                    attempts.countDown();
                    throw new RuntimeException("simulated downstream bug");
                }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });

            assertTrue("mock source must keep delivering frames despite a throwing listener",
                    attempts.await(5, TimeUnit.SECONDS));
            source.stop();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void rejectsNonExistentPath() {
        try {
            new MockFrameSource(Path.of("/no/such/path/at/all"), 10.0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // correct
        } catch (IOException e) {
            fail("expected IllegalArgumentException, got IOException: " + e);
        }
    }

    @Test
    public void videoFileModeReplaysARecordedMjpegFileOnceWhenNotLooping() throws Exception {
        Path videoFile = Files.createTempFile("mock-recorded", ".mjpeg");
        try {
            writeMjpegFile(videoFile,
                    solidJpeg(16, 16, Color.RED),
                    solidJpeg(16, 16, Color.GREEN),
                    solidJpeg(16, 16, Color.BLUE));

            MockFrameSource source = MockFrameSource.ofVideoFile(videoFile, 40.0, false);
            List<Frame> frames = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) { frames.add(frame); latch.countDown(); }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // give the non-looping source time to self-stop after frame 3
            assertFalse("a non-looping video-file source must stop itself after the last recorded frame",
                    source.isRunning());
            assertEquals(3, frames.size());
            source.stop();
        } finally {
            Files.deleteIfExists(videoFile);
        }
    }

    @Test
    public void videoFileModeLoopsWhenConfiguredTo() throws Exception {
        Path videoFile = Files.createTempFile("mock-recorded-loop", ".mjpeg");
        try {
            writeMjpegFile(videoFile, solidJpeg(8, 8, Color.RED), solidJpeg(8, 8, Color.GREEN));

            MockFrameSource source = MockFrameSource.ofVideoFile(videoFile, 40.0, true);
            CountDownLatch latch = new CountDownLatch(5); // more than the 2 recorded frames -> proves looping
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) { latch.countDown(); }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });

            assertTrue("looping mode should keep delivering frames past the recorded file's length",
                    latch.await(5, TimeUnit.SECONDS));
            assertTrue("a looping video-file source must still be running", source.isRunning());
            source.stop();
        } finally {
            Files.deleteIfExists(videoFile);
        }
    }

    @Test
    public void videoFileModeRejectsAFileWithNoReadableFrames() throws Exception {
        Path emptyFile = Files.createTempFile("mock-empty", ".mjpeg");
        try {
            try {
                MockFrameSource.ofVideoFile(emptyFile, 10.0, false);
                fail("expected IllegalArgumentException for a file with no frames");
            } catch (IllegalArgumentException expected) {
                // correct
            }
        } finally {
            Files.deleteIfExists(emptyFile);
        }
    }

    @Test
    public void webcamModeDeliversFramesFromAFakeGrabberWithoutTouchingRealHardware() throws Exception {
        AtomicInteger grabCount = new AtomicInteger();
        MockFrameSource.WebcamFrameGrabber fakeGrabber = () -> {
            grabCount.incrementAndGet();
            BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
            Graphics g = image.getGraphics();
            g.setColor(Color.CYAN);
            g.fillRect(0, 0, 12, 12);
            g.dispose();
            return image;
        };

        MockFrameSource source = MockFrameSource.ofWebcam(fakeGrabber, 20.0);
        List<Frame> frames = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { frames.add(frame); latch.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        source.stop();

        assertTrue("the fake grabber should have actually been called", grabCount.get() >= 3);
        assertEquals(12 * 12 * 3, frames.get(0).rgb().length);
    }

    @Test
    public void webcamModeReleasesTheGrabberWhenStopped() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        MockFrameSource.WebcamFrameGrabber grabber = new MockFrameSource.WebcamFrameGrabber() {
            @Override public BufferedImage grabFrame() {
                return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            }
            @Override public void close() { closeCount.incrementAndGet(); }
        };

        MockFrameSource source = MockFrameSource.ofWebcam(grabber, 40.0);
        CountDownLatch first = new CountDownLatch(1);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { first.countDown(); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
        });
        assertTrue(first.await(5, TimeUnit.SECONDS));
        source.stop();
        Thread.sleep(300);

        assertEquals("the webcam must be released after stop()", 1, closeCount.get());
    }

    @Test
    public void webcamModeReportsErrorWhenGrabberThrows() throws Exception {
        MockFrameSource.WebcamFrameGrabber failingGrabber = () -> {
            throw new IOException("simulated camera unplugged");
        };

        MockFrameSource source = MockFrameSource.ofWebcam(failingGrabber, 20.0);
        CountDownLatch errorLatch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ErrorCode> reported = new java.util.concurrent.atomic.AtomicReference<>();
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                reported.set(code);
                errorLatch.countDown();
            }
        });

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertEquals(ErrorCode.CAMERA_UNAVAILABLE, reported.get());
        source.stop();
    }

    @Test
    public void frameStrideOfTwoDeliversEveryOtherFrameWithSequenceGaps() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-stride");
        try {
            writeSolidJpeg(dir.resolve("only.jpg"), 8, 8, Color.MAGENTA);
            MockFrameSource source = new MockFrameSource(dir, 40.0, 2);

            List<Frame> frames = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame frame) { frames.add(frame); latch.countDown(); }
                @Override public void onError(ErrorCode code, String detail, boolean recoverable) { }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            source.stop();

            assertEquals(0L, frames.get(0).sequence());
            assertEquals(2L, frames.get(1).sequence());
            assertEquals(4L, frames.get(2).sequence());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void rejectsInvalidFrameStride() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-invalid-stride");
        try {
            writeSolidJpeg(dir.resolve("only.jpg"), 8, 8, Color.MAGENTA);
            try {
                new MockFrameSource(dir, 10.0, 0);
                fail("expected IllegalArgumentException for frameStride 0");
            } catch (IllegalArgumentException expected) {
                // correct
            }
            try {
                new MockFrameSource(dir, 10.0, -1);
                fail("expected IllegalArgumentException for negative frameStride");
            } catch (IllegalArgumentException expected) {
                // correct
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void rejectsEmptyDirectory() throws Exception {
        Path dir = Files.createTempDirectory("mock-frame-source-empty");
        try {
            new MockFrameSource(dir, 10.0);
            fail("expected IllegalArgumentException for a directory with no supported images");
        } catch (IllegalArgumentException expected) {
            // correct
        } finally {
            deleteRecursively(dir);
        }
    }

    private static boolean isPredominantly(Frame frame, int channelIndex) {
        byte[] rgb = frame.rgb();
        int value = rgb[channelIndex] & 0xFF;
        return value > 150;
    }

    private static byte[] solidJpeg(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** Writes a recorded-MJPEG-file fixture: concatenated Content-Length-delimited JPEG frames. */
    private static void writeMjpegFile(Path path, byte[]... jpegFrames) throws IOException {
        try (java.io.OutputStream out = Files.newOutputStream(path)) {
            for (byte[] jpeg : jpegFrames) {
                out.write(("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: "
                        + jpeg.length + "\r\n\r\n").getBytes());
                out.write(jpeg);
                out.write("\r\n\r\n".getBytes());
            }
        }
    }

    private static void writeSolidJpeg(Path path, int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ImageIO.write(image, "jpg", path.toFile());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
