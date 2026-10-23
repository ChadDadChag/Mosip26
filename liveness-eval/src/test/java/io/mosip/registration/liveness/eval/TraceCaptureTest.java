package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** TraceCapture's kept-open camera, with the fake grabber (no camera needed). */
class TraceCaptureTest {

    @Test
    void theCameraOpensOnceAndEachTakeOnlySeesItsOwnFrames() throws Exception {
        WebcamFrameSourceTest.FakeGrabber g = new WebcamFrameSourceTest.FakeGrabber();
        TraceCapture.KeptOpen camera = new TraceCapture.KeptOpen(new WebcamFrameSource(g, 0, 4, 3, 200));
        camera.open();
        assertThat(camera.isRunning()).isFalse(); // open, but no take attached
        for (int take = 0; take < 2; take++) {
            WebcamFrameSourceTest.Sink sink = new WebcamFrameSourceTest.Sink(3);
            camera.start(sink);
            assertThat(camera.isRunning()).isTrue();
            assertThat(sink.enough.await(5, TimeUnit.SECONDS)).isTrue();
            camera.stop();
            int delivered = sink.frames.size();
            Thread.sleep(100);
            assertThat(sink.frames).hasSize(delivered); // nothing after the take's stop()
        }
        camera.close();
        assertThat(g.opens.get()).isEqualTo(1);
        assertThat(g.closes.get()).isEqualTo(1);
    }

    @Test
    void aCameraThatFailedToOpenFailsTheNextTake() throws Exception {
        WebcamFrameSourceTest.FakeGrabber g = new WebcamFrameSourceTest.FakeGrabber();
        g.failOpen = true;
        TraceCapture.KeptOpen camera = new TraceCapture.KeptOpen(new WebcamFrameSource(g, 0, 4, 3, 30));
        camera.open();
        Thread.sleep(200); // the open fails on the grab thread
        WebcamFrameSourceTest.Sink sink = new WebcamFrameSourceTest.Sink(1);
        camera.start(sink);
        assertThat(sink.enough.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.errors).isNotEmpty().allMatch(e -> e.equals("CAMERA_UNAVAILABLE/false"));
        assertThat(sink.frames).isEmpty();
        camera.close();
    }
}
