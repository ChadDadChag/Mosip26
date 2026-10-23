package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.integrity.VerifiedBundle;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestBundles.MapSource;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through Part 3's {@code DefaultLivenessService} with the real analyzer. A static digital still passing
 * the passive check is EXPECTED here: this is a pipeline test, not a PAD test.
 */
class Part3IntegrationTest {

    /** Delivers one RGB888 image at ~15 fps on its own thread until stopped. */
    static final class StillFrameSource implements FrameSource {
        private final GoldenFixtures.Image img;
        private volatile boolean running;
        private Thread thread;

        StillFrameSource(GoldenFixtures.Image img) {
            this.img = img;
        }

        @Override
        public DeviceInfo info() {
            return new DeviceInfo("test-still", "test", "golden portrait", "none", "NONE", img.width, img.height, 15);
        }

        @Override
        public synchronized void start(final Listener listener) {
            running = true;
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    long seq = 0, lastTs = 0;
                    while (running) {
                        long ts = Math.max(lastTs + 1, System.currentTimeMillis());
                        lastTs = ts;
                        Frame f = new Frame(img.rgb, img.width, img.height, ts, seq++, "test-still");
                        if (!running) break;
                        listener.onFrame(f);
                        try {
                            Thread.sleep(66);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "still-frame-source");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public synchronized void stop() {
            running = false;
            if (thread != null) thread.interrupt();
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /** Records events and the result. */
    static final class Recorder implements LivenessListener {
        final List<LivenessEvent> events = new ArrayList<LivenessEvent>();
        final AtomicInteger completions = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        volatile LivenessResult result;

        @Override public synchronized void onEvent(LivenessEvent event) {
            events.add(event);
        }

        @Override public void onComplete(LivenessResult r) {
            completions.incrementAndGet();
            result = r;
            done.countDown();
        }

        /** Event types in order, consecutive repeats collapsed ("GUIDANCE(NO_FACE) x3"). */
        synchronized String summary() {
            StringBuilder sb = new StringBuilder();
            String last = null;
            int n = 0;
            for (LivenessEvent e : events) {
                String t = e.type + (e.guidance != null ? "(" + e.guidance + ")" : "");
                if (t.equals(last)) {
                    n++;
                    continue;
                }
                if (last != null) sb.append(last).append(n > 1 ? " x" + n : "").append(" -> ");
                last = t;
                n = 1;
            }
            if (last != null) sb.append(last).append(n > 1 ? " x" + n : "");
            return sb.toString();
        }
    }

    private static Recorder runSession(FaceAnalyzerFactory factory) throws Exception {
        LivenessService service = DefaultLivenessService.builder().config(key -> null).analyzerFactory(factory).build();
        StillFrameSource source = new StillFrameSource(GoldenFixtures.image("portrait_frontal"));
        Recorder rec = new Recorder();
        service.newSession(WorkflowType.RESIDENT).start(source, rec);
        assertThat(rec.done.await(15, TimeUnit.SECONDS)).as("onComplete within 15 s").isTrue();
        Thread.sleep(500); // nothing may follow onComplete
        source.stop();
        return rec;
    }

    @Test
    void aResidentSessionPassesPassivelyWithTheRealAnalyzer() throws Exception {
        try (OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory()) {
            Recorder rec = runSession(factory);
            LivenessResult r = rec.result;
            System.out.printf("Part 3 integration: %s passPath %s passiveScore %.4f duration %d ms modelVersion %s attempts %d%n"
                    + "  events: %s%n", r.decision, r.passPath, r.passiveScore, r.durationMs, r.modelVersion, r.attempts,
                    rec.summary());
            assertThat(rec.completions.get()).isEqualTo(1);
            assertThat(r.decision).isEqualTo(LivenessResult.Decision.PASSED);
            assertThat(r.passPath).isEqualTo(LivenessResult.PassPath.PASSIVE_ONLY);
            assertThat(r.bestFrame).isNotNull();
            assertThat(r.modelVersion).isEqualTo(BundledModelsTest.VERSION);
            assertThat(r.passiveScore).isGreaterThanOrEqualTo(0.85f);
        }
    }

    @Test
    void aTamperedBundleEndsTheSessionWithModelIntegrityFailed() throws Exception {
        ClasspathBundleSource cp = new ClasspathBundleSource();
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        files.put(VerifiedBundle.MANIFEST, cp.read(VerifiedBundle.MANIFEST));
        files.put(VerifiedBundle.SIGNATURE, cp.read(VerifiedBundle.SIGNATURE));
        for (ModelRole role : ModelRole.values()) files.put(role.fileName(), cp.read(role.fileName()));
        byte[] pad = files.get(ModelRole.PAD_V1SE.fileName()).clone();
        pad[pad.length / 2] ^= 0x01;
        files.put(ModelRole.PAD_V1SE.fileName(), pad);
        try (OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory(new MapSource(files), null,
                TrustedKeys.builtIn(), AnalyzerConfig.defaults())) {
            Recorder rec = runSession(factory);
            LivenessResult r = rec.result;
            System.out.printf("Part 3 integration (tampered bundle): %s failureCode %s duration %d ms%n  events: %s%n",
                    r.decision, r.failureCode, r.durationMs, rec.summary());
            assertThat(rec.completions.get()).isEqualTo(1);
            assertThat(r.decision).isEqualTo(LivenessResult.Decision.FAILED);
            assertThat(r.failureCode).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED);
        }
    }
}
