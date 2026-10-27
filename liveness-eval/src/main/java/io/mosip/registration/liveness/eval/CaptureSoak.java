package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F13 capture soak test (development only): continuous webcam capture plus the real analyzer for a fixed time, to
 * compare the capture backends (sarxos ended long sessions with a native exit, code 116).
 * <pre>
 *   CaptureSoak [--capture opencv|sarxos] [--minutes 30] [--fps 15] [--camera 0]
 * </pre>
 * The newest frame is analysed whenever the analyzer is free (older ones are dropped, as in the demo). Frames stay in
 * memory and are never written; the camera may see the room. One line per minute and a summary, numbers only:
 * frames captured and analysed, both rates, grab errors, heap used and the process's committed memory.
 */
final class CaptureSoak {

    private CaptureSoak() { }

    public static void main(String[] args) throws Exception {
        ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0, Collections.<String>emptyList());
        String backend = a.has("--capture") ? a.single("--capture") : "opencv";
        double minutes = Double.parseDouble(a.has("--minutes") ? a.single("--minutes") : "30");
        double fps = Double.parseDouble(a.has("--fps") ? a.single("--fps") : "15");
        int camera = Integer.parseInt(a.has("--camera") ? a.single("--camera") : "0");

        OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory(new ClasspathBundleSource(), AnalyzerConfig.defaults());
        factory.preload();
        final AtomicReference<Frame> latest = new AtomicReference<Frame>();
        final AtomicLong captured = new AtomicLong(), errors = new AtomicLong();
        final AtomicReference<String> fatal = new AtomicReference<String>();
        WebcamFrameSource source = new WebcamFrameSource(backend, camera, 640, 480, fps);
        long t0 = System.currentTimeMillis(), end = t0 + Math.round(minutes * 60_000);
        long analysed = 0, faces = 0, analysisNs = 0, lastCaptured = 0, lastAnalysed = 0, lastT = t0;
        long nextReport = t0 + 60_000, maxCommitted = 0, maxHeap = 0;
        String device = "?";
        System.out.printf(Locale.ROOT, "soak: capture %s, camera %d, %.0f fps, %.1f min; frames are never written%n",
                backend, camera, fps, minutes);
        try (FaceAnalyzer analyzer = factory.create()) {
            source.start(new FrameSource.Listener() {
                @Override public void onFrame(Frame f) {
                    captured.incrementAndGet();
                    latest.set(f);
                }

                @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                    errors.incrementAndGet();
                    if (!recoverable) fatal.set(code + " " + detail);
                }
            });
            while (System.currentTimeMillis() < end && fatal.get() == null) {
                Frame f = latest.getAndSet(null);
                if (f == null) {
                    Thread.sleep(2);
                } else {
                    long s = System.nanoTime();
                    if (analyzer.analyze(f).faceCount > 0) faces++;
                    analysisNs += System.nanoTime() - s;
                    analysed++;
                    if ("?".equals(device)) device = source.info().model;
                }
                long now = System.currentTimeMillis();
                if (now >= nextReport) {
                    long committed = committedBytes(), heap = heapBytes();
                    maxCommitted = Math.max(maxCommitted, committed);
                    maxHeap = Math.max(maxHeap, heap);
                    double dt = (now - lastT) / 1000.0;
                    System.out.printf(Locale.ROOT, "%5.1f min: captured %d (%.1f fps), analysed %d (%.1f fps), grab errors"
                            + " %d, heap %d MB, committed %d MB%n", (now - t0) / 60_000.0, captured.get(),
                            (captured.get() - lastCaptured) / dt, analysed, (analysed - lastAnalysed) / dt, errors.get(),
                            heap >> 20, committed >> 20);
                    lastCaptured = captured.get();
                    lastAnalysed = analysed;
                    lastT = now;
                    nextReport += 60_000;
                }
            }
        } finally {
            source.stop();
            factory.close();
        }
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        System.out.printf(Locale.ROOT, "soak %s: device %s; %.1f min; captured %d (%.1f fps); analysed %d (%.1f fps, %.1f ms"
                + " mean); frames with a face %d; grab errors %d; fatal %s; max heap %d MB; max committed %d MB%n",
                fatal.get() == null ? "COMPLETED" : "STOPPED", device, secs / 60, captured.get(), captured.get() / secs,
                analysed, analysed / secs, analysed == 0 ? 0 : analysisNs / 1e6 / analysed, faces, errors.get(),
                fatal.get() == null ? "none" : fatal.get(), maxHeap >> 20, maxCommitted >> 20);
        System.exit(fatal.get() == null ? 0 : 1);
    }

    private static long heapBytes() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /** The process's committed virtual memory (Windows: private bytes), or -1 when the JVM does not expose it. */
    private static long committedBytes() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean
                ? ((com.sun.management.OperatingSystemMXBean) os).getCommittedVirtualMemorySize() : -1;
    }
}
