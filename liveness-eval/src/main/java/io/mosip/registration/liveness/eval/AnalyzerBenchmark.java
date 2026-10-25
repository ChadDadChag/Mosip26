package io.mosip.registration.liveness.eval;

import ai.onnxruntime.OrtEnvironment;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.AnalyzerStats;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Per-stage latency of {@link OnnxFaceAnalyzer} (development only).
 * <pre>
 *   AnalyzerBenchmark [--image &lt;png&gt; | --frames &lt;dir&gt; | --webcam &lt;index&gt;] [--threads 1,2,4]
 *                     [--warmup 20] [--runs 300] [--out liveness-eval/reports] [--cpu "&lt;name&gt;"]
 * </pre>
 * For each intra-op thread count: cold start (factory construction, then preload() = bundle verification + ONNX
 * session creation, then the first analyze()), then {@code warmup} untimed and {@code runs} timed analyze() calls,
 * with p50/p95/p99/mean per stage from {@link AnalyzerStats}. CPU execution provider only: the desktop ONNX Runtime
 * jar has no XNNPACK or NNAPI. Writes benchmark-&lt;yyyyMMdd&gt;.md and .csv (no personal data: timings only).
 */
public final class AnalyzerBenchmark {

    static final String[] STAGES = {"detect", "mesh", "pose", "quality", "pad", "cues", "total"};
    static final String DEFAULT_IMAGE = "liveness-analyzer/src/test/resources/golden/portrait_frontal.png";

    private AnalyzerBenchmark() { }

    /** Latency summary of one series, in milliseconds. */
    static final class Summary {
        final double p50, p95, p99, mean;
        final int n;

        Summary(double[] valuesMs) {
            double[] s = valuesMs.clone();
            Arrays.sort(s);
            n = s.length;
            p50 = percentile(s, 50);
            p95 = percentile(s, 95);
            p99 = percentile(s, 99);
            double sum = 0;
            for (double v : s) sum += v;
            mean = n == 0 ? Double.NaN : sum / n;
        }
    }

    /** Nearest-rank percentile of sorted values (p in (0, 100]). */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }

    /** One thread setting's results. */
    static final class Result {
        final int threads;
        final double constructMs, preloadMs, firstFrameMs;
        final Summary[] stages;

        Result(int threads, double constructMs, double preloadMs, double firstFrameMs, Summary[] stages) {
            this.threads = threads;
            this.constructMs = constructMs;
            this.preloadMs = preloadMs;
            this.firstFrameMs = firstFrameMs;
            this.stages = stages;
        }
    }

    public static void main(String[] args) throws Exception {
        ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0, Collections.<String>emptyList());
        List<Frame> frames;
        String input;
        if (a.has("--frames")) {
            frames = loadDir(new File(a.single("--frames")));
            input = "frames from " + a.single("--frames") + " (" + frames.size() + ")";
        } else if (a.has("--webcam")) {
            int idx = Integer.parseInt(a.single("--webcam"));
            frames = captureWebcam(new WebcamFrameSource(idx, 640, 480, 15), 60);
            input = "60 live frames from webcam " + idx + " (held in memory)";
        } else {
            String image = a.has("--image") ? a.single("--image") : DEFAULT_IMAGE;
            frames = Collections.singletonList(load(new File(image), 0));
            input = image;
        }
        int warmup = Integer.parseInt(a.has("--warmup") ? a.single("--warmup") : "20");
        int runs = Integer.parseInt(a.has("--runs") ? a.single("--runs") : "300");
        List<Result> results = new ArrayList<Result>();
        for (String t : (a.has("--threads") ? a.single("--threads") : "1,2,4").split(",")) {
            results.add(run(frames, Integer.parseInt(t.trim()), warmup, runs));
        }
        String cpu = a.has("--cpu") ? a.single("--cpu") : cpuName();
        File outDir = new File(a.has("--out") ? a.single("--out") : "liveness-eval/reports");
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String md = markdown(results, input, warmup, runs, cpu, frames.get(0));
        System.out.print(md);
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
        Files.write(new File(outDir, "benchmark-" + date + ".md").toPath(), md.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(outDir, "benchmark-" + date + ".csv").toPath(), csv(results).getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + new File(outDir, "benchmark-" + date + ".md") + " and .csv");
        System.exit(0);
    }

    static Result run(List<Frame> frames, int threads, int warmup, int runs) throws Exception {
        AnalyzerConfig cfg = AnalyzerConfig.builder().intraOpThreads(threads).build();
        long t0 = System.nanoTime();
        OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory(new ClasspathBundleSource(), null, TrustedKeys.builtIn(), cfg);
        long t1 = System.nanoTime();
        factory.preload();
        long t2 = System.nanoTime();
        try {
            OnnxFaceAnalyzer analyzer = (OnnxFaceAnalyzer) factory.create();
            long t3 = System.nanoTime();
            analyzer.analyze(frames.get(0));
            long t4 = System.nanoTime();
            for (int i = 0; i < warmup; i++) analyzer.analyze(frames.get(i % frames.size()));
            double[][] ms = new double[STAGES.length][runs];
            for (int i = 0; i < runs; i++) {
                analyzer.analyze(frames.get(i % frames.size()));
                AnalyzerStats s = analyzer.stats();
                long[] ns = {s.lastDetectNs(), s.lastMeshNs(), s.lastPoseNs(), s.lastQualityNs(), s.lastPadNs(),
                        s.lastCuesNs(), s.lastTotalNs()};
                for (int k = 0; k < STAGES.length; k++) ms[k][i] = ns[k] / 1e6;
            }
            Summary[] sums = new Summary[STAGES.length];
            for (int k = 0; k < STAGES.length; k++) sums[k] = new Summary(ms[k]);
            analyzer.close();
            return new Result(threads, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t4 - t3) / 1e6, sums);
        } finally {
            factory.close();
        }
    }

    static String markdown(List<Result> results, String input, int warmup, int runs, String cpu, Frame sample) {
        StringBuilder sb = new StringBuilder();
        sb.append("# OnnxFaceAnalyzer benchmark (").append(LocalDate.now()).append(")\n\n");
        sb.append("Generated by `liveness-eval` `AnalyzerBenchmark`; timings only, no personal data.\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| CPU | ").append(cpu).append(" |\n");
        sb.append("| Logical cores | ").append(Runtime.getRuntime().availableProcessors()).append(" |\n");
        sb.append("| OS | ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(") |\n");
        sb.append("| JDK | ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version")).append(" |\n");
        sb.append("| ONNX Runtime | ").append(ortVersion()).append(", CPU execution provider (the desktop jar has no XNNPACK/NNAPI) |\n");
        sb.append("| Input | ").append(input).append(", ").append(sample.width()).append('x').append(sample.height()).append(" |\n");
        sb.append("| Runs | ").append(warmup).append(" warm-up + ").append(runs).append(" timed analyze() calls per thread setting |\n\n");
        sb.append("## Cold start (ms)\n\n| intra-op threads | factory construction | preload (verify + sessions) | first analyze() |\n|---|---|---|---|\n");
        for (Result r : results) {
            sb.append(String.format("| %d | %.1f | %.1f | %.1f |%n", r.threads, r.constructMs, r.preloadMs, r.firstFrameMs));
        }
        sb.append("\n## Per-stage latency (ms, p50 / p95 / p99 / mean)\n\n| stage |");
        for (Result r : results) sb.append(" ").append(r.threads).append(r.threads == 1 ? " thread |" : " threads |");
        sb.append("\n|---|");
        for (int i = 0; i < results.size(); i++) sb.append("---|");
        sb.append('\n');
        for (int k = 0; k < STAGES.length; k++) {
            sb.append("| ").append(STAGES[k]).append(" |");
            for (Result r : results) {
                Summary s = r.stages[k];
                sb.append(String.format(" %.2f / %.2f / %.2f / %.2f |", s.p50, s.p95, s.p99, s.mean));
            }
            sb.append('\n');
        }
        sb.append("\n\"pad\" covers both MiniFASNet models; \"total\" is the whole analyze() call.\n");
        return sb.toString();
    }

    static String csv(List<Result> results) {
        StringBuilder sb = new StringBuilder("threads,metric,p50_ms,p95_ms,p99_ms,mean_ms,n\n");
        for (Result r : results) {
            sb.append(String.format("%d,cold_construct,%.3f,,,,1%n", r.threads, r.constructMs));
            sb.append(String.format("%d,cold_preload,%.3f,,,,1%n", r.threads, r.preloadMs));
            sb.append(String.format("%d,cold_first_analyze,%.3f,,,,1%n", r.threads, r.firstFrameMs));
            for (int k = 0; k < STAGES.length; k++) {
                Summary s = r.stages[k];
                sb.append(String.format("%d,%s,%.3f,%.3f,%.3f,%.3f,%d%n", r.threads, STAGES[k], s.p50, s.p95, s.p99, s.mean, s.n));
            }
        }
        return sb.toString();
    }

    static String ortVersion() {
        try {
            return "onnxruntime " + OrtEnvironment.getEnvironment().getVersion();
        } catch (RuntimeException e) {
            return "onnxruntime (version unknown)";
        }
    }

    /** CPU model from the OS (Windows registry, /proc/cpuinfo or sysctl); falls back to PROCESSOR_IDENTIFIER. */
    static String cpuName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String out = exec("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString");
                int i = out.indexOf("REG_SZ");
                if (i >= 0) return out.substring(i + 6).trim();
            } else if (os.contains("mac")) {
                return exec("sysctl", "-n", "machdep.cpu.brand_string").trim();
            } else {
                for (String line : Files.readAllLines(new File("/proc/cpuinfo").toPath())) {
                    if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        String id = System.getenv("PROCESSOR_IDENTIFIER");
        return id != null ? id : "unknown";
    }

    private static String exec(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return sb.toString();
    }

    static Frame load(File png, long seq) throws IOException {
        BufferedImage img = ImageIO.read(png);
        if (img == null) throw new IOException("not an image: " + png);
        return new Frame(WebcamFrameSource.toRgb(img), img.getWidth(), img.getHeight(), seq + 1, seq, "file");
    }

    static List<Frame> loadDir(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("not a directory: " + dir);
        Arrays.sort(files);
        List<Frame> out = new ArrayList<Frame>();
        for (File f : files) {
            if (f.getName().toLowerCase().endsWith(".png")) out.add(load(f, out.size()));
        }
        if (out.isEmpty()) throw new IOException("no PNG frames in " + dir);
        return out;
    }

    /** Grabs {@code count} frames into memory, so camera speed does not limit the timing loop. */
    static List<Frame> captureWebcam(FrameSource source, int count) throws InterruptedException, IOException {
        final List<Frame> frames = Collections.synchronizedList(new ArrayList<Frame>());
        final CountDownLatch done = new CountDownLatch(1);
        final int wanted = count;
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame f) {
                if (frames.size() < wanted) frames.add(f);
                if (frames.size() >= wanted) done.countDown();
            }

            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                if (!recoverable) done.countDown();
            }
        });
        try {
            done.await(30, TimeUnit.SECONDS);
        } finally {
            source.stop();
        }
        if (frames.isEmpty()) throw new IOException("no frames from the webcam");
        return new ArrayList<Frame>(frames);
    }
}
