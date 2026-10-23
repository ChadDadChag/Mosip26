package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WebcamFrameSource with a fake grabber (no camera needed). */
class WebcamFrameSourceTest {

    /** 4 x 3 images whose first pixel encodes the grab count; can fail to open or fail some grabs. */
    static final class FakeGrabber implements WebcamFrameSource.Grabber {
        final AtomicInteger grabs = new AtomicInteger(), opens = new AtomicInteger(), closes = new AtomicInteger();
        volatile boolean failOpen;
        volatile int failEvery; // every Nth grab throws (0 = never)

        @Override public void open(int width, int height) throws IOException {
            opens.incrementAndGet();
            if (failOpen) throw new IOException("no such camera");
        }

        @Override public BufferedImage grab() throws IOException {
            int n = grabs.incrementAndGet();
            if (failEvery > 0 && n % failEvery == 0) throw new IOException("dropped");
            BufferedImage img = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, (n & 0xFF) << 16 | 0x00AB00 | 0x0000CD);
            return img;
        }

        @Override public void close() {
            closes.incrementAndGet();
        }

        @Override public String name() {
            return "fake camera";
        }
    }

    /** Collects frames and errors. */
    static final class Sink implements FrameSource.Listener {
        final List<Frame> frames = Collections.synchronizedList(new ArrayList<Frame>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch enough;

        Sink(int wanted) {
            enough = new CountDownLatch(wanted);
        }

        @Override public void onFrame(Frame f) {
            frames.add(f);
            enough.countDown();
        }

        @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
            errors.add(code + "/" + recoverable);
            if (!recoverable) while (enough.getCount() > 0) enough.countDown();
        }
    }

    @Test
    void deliversRgbFramesWithIncreasingSequenceAndTimestamps() throws Exception {
        FakeGrabber g = new FakeGrabber();
        WebcamFrameSource src = new WebcamFrameSource(g, 3, 4, 3, 200);
        final List<Frame> previews = Collections.synchronizedList(new ArrayList<Frame>());
        src.setPreviewListener(new WebcamFrameSource.PreviewListener() {
            @Override public void onPreview(Frame f) { previews.add(f); }
        });
        Sink sink = new Sink(10);
        src.start(sink);
        src.start(sink); // a second start while running is ignored
        assertThat(sink.enough.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(src.isRunning()).isTrue();
        src.stop();
        assertThat(src.isRunning()).isFalse();
        List<Frame> frames = new ArrayList<Frame>(sink.frames);
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            assertThat(f.sequence()).isEqualTo(i);
            assertThat(f.deviceId()).isEqualTo("webcam:3");
            assertThat(f.width()).isEqualTo(4);
            assertThat(f.height()).isEqualTo(3);
            if (i > 0) assertThat(f.timestampMs()).isGreaterThan(frames.get(i - 1).timestampMs());
        }
        byte[] rgb = frames.get(0).rgb();
        assertThat(rgb[1] & 0xFF).isEqualTo(0xAB);
        assertThat(rgb[2] & 0xFF).isEqualTo(0xCD);
        assertThat(previews).isNotEmpty();
        assertThat(g.opens.get()).isEqualTo(1);
        assertThat(g.closes.get()).isEqualTo(1);
    }

    @Test
    void stopIsIdempotentAndARestartBeginsAFreshStream() throws Exception {
        FakeGrabber g = new FakeGrabber();
        WebcamFrameSource src = new WebcamFrameSource(g, 0, 4, 3, 200);
        src.stop(); // before start: no-op
        Sink first = new Sink(3);
        src.start(first);
        assertThat(first.enough.await(5, TimeUnit.SECONDS)).isTrue();
        src.stop();
        src.stop();
        int delivered = first.frames.size();
        Thread.sleep(100);
        assertThat(first.frames).hasSize(delivered); // nothing after stop()
        Sink second = new Sink(3);
        src.start(second);
        assertThat(second.enough.await(5, TimeUnit.SECONDS)).isTrue();
        src.stop();
        assertThat(second.frames.get(0).sequence()).isZero();
        assertThat(g.opens.get()).isEqualTo(2);
    }

    @Test
    void aMissingCameraIsANonRecoverableError() throws Exception {
        FakeGrabber g = new FakeGrabber();
        g.failOpen = true;
        WebcamFrameSource src = new WebcamFrameSource(g, 7, 4, 3, 30);
        Sink sink = new Sink(1);
        src.start(sink);
        assertThat(sink.enough.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.errors).containsExactly("CAMERA_UNAVAILABLE/false");
        assertThat(sink.frames).isEmpty();
        Thread.sleep(50);
        assertThat(src.isRunning()).isFalse();
        src.stop();
    }

    @Test
    void aFailedGrabIsRecoverableAndTheStreamContinues() throws Exception {
        FakeGrabber g = new FakeGrabber();
        g.failEvery = 3;
        WebcamFrameSource src = new WebcamFrameSource(g, 0, 4, 3, 300);
        Sink sink = new Sink(8);
        src.start(sink);
        assertThat(sink.enough.await(5, TimeUnit.SECONDS)).isTrue();
        src.stop();
        assertThat(sink.errors).isNotEmpty().allMatch(e -> e.equals("INVALID_FRAME/true"));
        List<Frame> frames = new ArrayList<Frame>(sink.frames);
        for (int i = 0; i < frames.size(); i++) assertThat(frames.get(i).sequence()).isEqualTo(i); // no gaps
    }

    @Test
    void infoAndArgumentChecks() {
        WebcamFrameSource src = new WebcamFrameSource(new FakeGrabber(), 2, 640, 480, 15);
        FrameSource.DeviceInfo info = src.info();
        assertThat(info.deviceId).isEqualTo("webcam:2");
        assertThat(info.certificationLevel).isEqualTo("NONE");
        assertThat(info.maxWidth).isEqualTo(640);
        assertThat(info.maxHeight).isEqualTo(480);
        assertThat(info.maxFps).isEqualTo(15);
        assertThat(info.model).isEqualTo("fake camera");
        assertThatThrownBy(() -> new WebcamFrameSource(new FakeGrabber(), 0, 0, 480, 15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebcamFrameSource(new FakeGrabber(), 0, 640, 480, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> src.start(null)).isInstanceOf(IllegalArgumentException.class);
        // The public constructors build without touching a camera (TraceRecorder instantiates reflectively).
        assertThat(new WebcamFrameSource().info().deviceId).isEqualTo("webcam:0");
    }
}
