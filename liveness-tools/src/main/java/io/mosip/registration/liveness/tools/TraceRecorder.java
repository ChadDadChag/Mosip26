package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records a labelled trace: runs any {@link FrameSource} (Part 1: webcam relay, video file, SBI) and any
 * {@link FaceAnalyzer} (Part 2) and keeps only {@link TraceFrame} features. Nothing image-like is written.
 *
 * <pre>
 * java -cp liveness-tools.jar:part1.jar:part2.jar io.mosip.registration.liveness.tools.TraceRecorder \
 *     --source com.example.WebcamFrameSource --analyzer com.example.OnnxFaceAnalyzerFactory \
 *     --label blink --seconds 6 --prompt-ms 2000 --out traces/alice-blink-1.jsonl
 * </pre>
 * The console says "Get ready", then prints the action at {@code --prompt-ms}; the person performs it then.
 */
public final class TraceRecorder {

    private TraceRecorder() { }

    public static Trace record(FrameSource source, FaceAnalyzer analyzer, String label, long promptAtMs,
                               long durationMs, int fps, PrintStream console) throws InterruptedException {
        final AtomicReference<Frame> latest = new AtomicReference<Frame>();
        final AtomicReference<String> error = new AtomicReference<String>();
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame frame) { latest.set(frame); }
            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                if (!recoverable) error.set(code + ": " + detail);
            }
        });
        List<TraceFrame> frames = new ArrayList<TraceFrame>();
        long start = System.nanoTime() / 1000000L;
        long t0 = -1;
        boolean prompted = false;
        if (console != null) console.println("Get ready: look at the camera and keep still.");
        try {
            while (true) {
                long elapsed = System.nanoTime() / 1000000L - start;
                if (elapsed >= durationMs || error.get() != null) break;
                if (!prompted && elapsed >= promptAtMs) {
                    prompted = true;
                    if (console != null) console.println(">>> NOW: " + label.toUpperCase().replace('_', ' '));
                }
                Frame f = latest.getAndSet(null);
                if (f != null) {
                    try {
                        FrameAnalysis a = analyzer.analyze(f);
                        if (t0 < 0) t0 = f.timestampMs() - elapsed;
                        frames.add(TraceFrame.of(a, t0));
                    } catch (LivenessEngineException e) {
                        if (console != null) console.println("frame skipped: " + e.code());
                    } catch (RuntimeException e) {
                        if (console != null) console.println("frame skipped: " + e);
                    }
                }
                Thread.sleep(Math.max(1, 1000 / Math.max(1, fps)));
            }
        } finally {
            source.stop();
        }
        if (error.get() != null) throw new IllegalStateException("device error: " + error.get());
        String device = null;
        try {
            device = source.info() != null ? source.info().deviceId : null;
        } catch (RuntimeException ignored) {
            // optional
        }
        return new Trace(label, promptAtMs, Trace.header(label, promptAtMs, analyzer.modelVersion(), device, false), frames);
    }

    public static void main(String[] args) throws Exception {
        Args a = new Args(args);
        FrameSource source = (FrameSource) Class.forName(a.required("--source")).getDeclaredConstructor().newInstance();
        Object an = Class.forName(a.required("--analyzer")).getDeclaredConstructor().newInstance();
        FaceAnalyzer analyzer = an instanceof FaceAnalyzerFactory ? ((FaceAnalyzerFactory) an).create() : (FaceAnalyzer) an;
        try {
            Trace t = record(source, analyzer, a.required("--label"), Long.parseLong(a.get("--prompt-ms", "2000")),
                    Long.parseLong(a.get("--seconds", "6")) * 1000L, Integer.parseInt(a.get("--fps", "15")), System.out);
            File out = new File(a.required("--out"));
            t.write(out);
            System.out.println("Wrote " + t.frames.size() + " frames to " + out);
        } finally {
            analyzer.close();
        }
    }
}
