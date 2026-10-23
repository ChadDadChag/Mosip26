package io.mosip.registration.liveness.core;

import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.scenario.ScriptedFrameSource;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;
import io.mosip.registration.liveness.core.testing.Fa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real service with real threads and the real clock (the few tests that are about threading itself). */
class DefaultLivenessServiceTest {

    static final class MapConfig implements ConfigProvider {
        final Map<String, String> m = new HashMap<String, String>();
        @Override public String get(String k) { return m.get(k); }
    }

    static final class Recorder implements LivenessListener {
        final List<Object> timeline = Collections.synchronizedList(new ArrayList<Object>());
        final CountDownLatch done = new CountDownLatch(1);
        volatile LivenessResult result;
        volatile int completions;
        volatile String thread;
        @Override public void onEvent(LivenessEvent e) { timeline.add(e.type); thread = Thread.currentThread().getName(); }
        @Override public void onComplete(LivenessResult r) { result = r; completions++; timeline.add(r.decision); done.countDown(); }
    }

    /** Analyzer returning a high passive score for every frame; optionally blocks inside analyze(). */
    static final class SimpleAnalyzer implements FaceAnalyzer {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch block;
        volatile boolean closed;
        @Override public FrameAnalysis analyze(Frame f) throws LivenessEngineException {
            entered.countDown();
            CountDownLatch b = block;
            if (b != null) {
                try { b.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            FrameAnalysis a = Fa.at(f.timestampMs(), f.sequence()).score(0.95f).build();
            return new FrameAnalysis(f, 1, a.faceBox, a.landmarks, 0, 0, 0, a.qualityIssues, 0.95f, null, 5);
        }
        @Override public String modelVersion() { return "simple-1"; }
        @Override public void close() { closed = true; }
    }

    private final ScheduledExecutorService camera = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopCamera() {
        camera.shutdownNow();
    }

    /** Pushes a frame every 33 ms of real time while the source is running. */
    private void stream(final ScriptedFrameSource source) {
        final AtomicLong seq = new AtomicLong();
        camera.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                source.push(System.nanoTime() / 1000000L, seq.incrementAndGet());
            }
        }, 0, 33, TimeUnit.MILLISECONDS);
    }

    private static DefaultLivenessService service(final FaceAnalyzer analyzer, ConfigProvider cfg) {
        return DefaultLivenessService.builder().config(cfg).analyzerFactory(new FaceAnalyzerFactory() {
            @Override public FaceAnalyzer create() { return analyzer; }
        }).build();
    }

    @Test
    void passivePassEndToEndOnRealThreads() throws Exception {
        SimpleAnalyzer analyzer = new SimpleAnalyzer();
        DefaultLivenessService svc = service(analyzer, new MapConfig());
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        ScriptedFrameSource source = new ScriptedFrameSource();
        Recorder rec = new Recorder();
        s.start(source, rec);
        stream(source);
        assertThat(rec.done.await(10, TimeUnit.SECONDS)).isTrue();
        io.mosip.registration.liveness.core.metrics.LivenessMetrics.Snapshot m = svc.metrics().snapshot();
        assertThat(m.sessions).isEqualTo(1);
        assertThat(m.passedPassive).isEqualTo(1);
        assertThat(m.framesAnalyzed).isGreaterThanOrEqualTo(10);
        assertThat(m.avgProcessingMs()).isEqualTo(5.0);
        LivenessResult r = rec.result;
        assertThat(r.passed()).isTrue();
        assertThat(r.passPath).isEqualTo(LivenessResult.PassPath.PASSIVE_ONLY);
        assertThat(r.modelVersion).isEqualTo("simple-1");
        assertThat(r.sessionId).isEqualTo(s.sessionId());
        assertThat(rec.thread).startsWith("liveness-events-");
        assertThat(rec.timeline).containsExactly(LivenessEvent.Type.STARTED, LivenessEvent.Type.CHECKING_PASSIVE,
                LivenessEvent.Type.PASSED, LivenessResult.Decision.PASSED);
        // Worker closes the analyzer and stops the source after the result.
        for (int i = 0; i < 200 && !analyzer.closed; i++) Thread.yield();
        assertThat(source.stops.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cancelIsImmediateEvenWhileTheAnalyzerIsBusy() throws Exception {
        SimpleAnalyzer analyzer = new SimpleAnalyzer();
        analyzer.block = new CountDownLatch(1);
        LivenessSession s = service(analyzer, new MapConfig()).newSession(WorkflowType.OPERATOR);
        ScriptedFrameSource source = new ScriptedFrameSource();
        Recorder rec = new Recorder();
        s.start(source, rec);
        stream(source);
        assertThat(analyzer.entered.await(5, TimeUnit.SECONDS)).isTrue(); // worker is stuck inside analyze()

        long t0 = System.nanoTime();
        s.cancel();
        assertThat(rec.done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat((System.nanoTime() - t0) / 1000000L).isLessThan(1000);
        assertThat(rec.result.decision).isEqualTo(LivenessResult.Decision.CANCELLED);
        assertThat(source.isRunning()).isFalse();

        int size = rec.timeline.size();
        analyzer.block.countDown(); // let the worker finish its frame: nothing more may reach the listener
        s.cancel();
        s.retry();
        for (int i = 0; i < 1000 && !analyzer.closed; i++) Thread.yield();
        assertThat(rec.timeline).hasSize(size);
        assertThat(rec.completions).isEqualTo(1);
    }

    @Test
    void analyzerFactoryFailureEndsTheSessionWithModelIntegrityFailed() throws Exception {
        LivenessSession s = DefaultLivenessService.builder().analyzerFactory(new FaceAnalyzerFactory() {
            @Override public FaceAnalyzer create() throws LivenessEngineException {
                throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED, "bad signature");
            }
        }).build().newSession(WorkflowType.SUPERVISOR);
        Recorder rec = new Recorder();
        s.start(new ScriptedFrameSource(), rec);
        assertThat(rec.done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(rec.result.failureCode).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED);
    }

    @Test
    void serviceBasics() {
        MapConfig cfg = new MapConfig();
        cfg.m.put(LivenessPolicy.PREFIX + "supervisor.enabled", "false");
        DefaultLivenessService svc = service(new SimpleAnalyzer(), cfg);
        assertThat(svc.newSession(WorkflowType.SUPERVISOR)).isNull();
        assertThat(svc.policy(WorkflowType.OPERATOR).minChallenges).isEqualTo(1);
        svc.policy(WorkflowType.RESIDENT).maxRetries = 0;
        assertThat(svc.policy(WorkflowType.RESIDENT).maxRetries).isEqualTo(3);
        svc.reloadConfig(new MapConfig());
        assertThat(svc.newSession(WorkflowType.SUPERVISOR)).isNotNull();
        assertThatThrownBy(() -> DefaultLivenessService.builder().build()).isInstanceOf(IllegalStateException.class);
        LivenessSession s = svc.newSession(WorkflowType.RESIDENT);
        assertThatThrownBy(() -> s.start(null, new Recorder())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelBeforeStartNeverOpensTheCamera() throws Exception {
        LivenessSession s = service(new SimpleAnalyzer(), new MapConfig()).newSession(WorkflowType.RESIDENT);
        s.cancel();
        ScriptedFrameSource source = new ScriptedFrameSource();
        Recorder rec = new Recorder();
        s.start(source, rec);
        assertThat(rec.done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(rec.result.decision).isEqualTo(LivenessResult.Decision.CANCELLED);
        assertThat(source.starts.get()).isZero();
        assertThatThrownBy(() -> s.start(source, rec)).isInstanceOf(IllegalStateException.class);
    }
}
