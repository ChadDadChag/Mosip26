package io.mosip.registration.liveness.device;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shared helpers for the device + engine integration tests (Part 1 with the Part 3 engine).
 *
 * <p>Test frames are solid colours so their meaning survives JPEG compression: GREEN = a live face, RED = a
 * presentation attack, BLUE = no face. {@link ColourAnalyzer} turns that into a {@link FrameAnalysis}: a
 * deterministic stand-in for Part 2's analyzer, so these tests check device and engine behaviour, not a model.</p>
 */
public final class LivenessTestSupport {

    public enum Scene { LIVE, ATTACK, NO_FACE }

    private LivenessTestSupport() {
    }

    public static byte[] jpeg(Scene scene) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(scene == Scene.LIVE ? new Color(30, 200, 40) : scene == Scene.ATTACK ? new Color(210, 30, 30)
                : new Color(30, 40, 210));
        g.fillRect(0, 0, 320, 240);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** The /stream wire format (and MjpegRecorder file format) for one JPEG. */
    public static byte[] part(byte[] jpeg) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg);
        out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    public static byte[] recording(Scene scene, int frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p = part(jpeg(scene));
        for (int i = 0; i < frames; i++) out.write(p);
        return out.toByteArray();
    }

    /**
     * A device stream that delivers {@code part} every {@code periodMs} like a live camera, up to {@code maxFrames}
     * (then EOF, i.e. the cable is pulled), or forever when {@code maxFrames < 0}, until closed.
     */
    public static InputStream liveStream(final byte[] part, final long periodMs, final int maxFrames) {
        return new InputStream() {
            private int frame = 0;
            private int pos = part.length; // forces a pause before the first frame
            private volatile boolean closed;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (closed) throw new IOException("closed");
                if (pos >= part.length) {
                    if (maxFrames >= 0 && frame >= maxFrames) return -1;
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                    if (closed) throw new IOException("closed");
                    frame++;
                    pos = 0;
                }
                int n = Math.min(len, part.length - pos);
                System.arraycopy(part, pos, b, off, n);
                pos += n;
                return n;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    /** Deterministic stand-in for Part 2: classifies a frame by its dominant colour. */
    public static final class ColourAnalyzer implements FaceAnalyzer {
        @Override
        public FrameAnalysis analyze(Frame f) {
            byte[] rgb = f.rgb();
            long r = 0, g = 0, b = 0;
            int step = Math.max(3, (rgb.length / 3 / 500) * 3);
            for (int i = 0; i + 2 < rgb.length; i += step) {
                r += rgb[i] & 0xFF;
                g += rgb[i + 1] & 0xFF;
                b += rgb[i + 2] & 0xFF;
            }
            float[] box = {f.width() * 0.3f, f.height() * 0.2f, f.width() * 0.4f, f.height() * 0.6f};
            if (b > r && b > g) {
                return new FrameAnalysis(f, 0, null, null, Float.NaN, Float.NaN, Float.NaN, null, Float.NaN, null, 3);
            }
            float score = g > r ? 0.95f : 0.08f;
            Map<String, Float> pad = Collections.singletonMap("colour", score);
            return new FrameAnalysis(f, 1, box, null, 0f, 0f, 0f, null, score, pad, 3);
        }

        @Override
        public String modelVersion() {
            return "colour-test-1";
        }

        @Override
        public void close() {
        }
    }

    public static DefaultLivenessService service(final Map<String, String> config, FaceAnalyzerFactory factory,
                                                 AuditSink audit) {
        return DefaultLivenessService.builder().config(new ConfigProvider() {
            @Override
            public String get(String key) {
                return config.get(key);
            }
        }).analyzerFactory(factory).auditSink(audit).build();
    }

    public static DefaultLivenessService colourService(Map<String, String> config, AuditSink audit) {
        return service(config, new FaceAnalyzerFactory() {
            @Override
            public FaceAnalyzer create() {
                return new ColourAnalyzer();
            }
        }, audit);
    }

    public static Map<String, String> config(String... keyValues) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(keyValues[i], keyValues[i + 1]);
        return m;
    }

    /** Records events in order and waits for the single final result. */
    public static class Recorder implements LivenessListener {
        public final List<LivenessEvent> events = Collections.synchronizedList(new ArrayList<LivenessEvent>());
        public final CountDownLatch done = new CountDownLatch(1);
        public volatile LivenessResult result;
        public volatile int completions;

        @Override
        public void onEvent(LivenessEvent e) {
            events.add(e);
        }

        @Override
        public void onComplete(LivenessResult r) {
            result = r;
            completions++;
            done.countDown();
        }

        public LivenessResult await(long seconds) throws InterruptedException {
            if (!done.await(seconds, TimeUnit.SECONDS)) {
                throw new AssertionError("no result within " + seconds + " s; events " + describe());
            }
            return result;
        }

        public List<LivenessEvent.Type> types() {
            List<LivenessEvent.Type> t = new ArrayList<>();
            synchronized (events) {
                for (LivenessEvent e : events) t.add(e.type);
            }
            return t;
        }

        /** Events with their error codes, for assertion messages. */
        public String describe() {
            StringBuilder b = new StringBuilder();
            synchronized (events) {
                for (LivenessEvent e : events) {
                    b.append(e.type);
                    if (e.error != null) b.append('(').append(e.error).append(e.recoverable ? ",retry" : ",final").append(')');
                    b.append(' ');
                }
            }
            return b.toString().trim();
        }

        public LivenessEvent first(LivenessEvent.Type type) {
            synchronized (events) {
                for (LivenessEvent e : events) if (e.type == type) return e;
            }
            return null;
        }
    }

    /** Collects audit records (to read lastCause and check that nothing image-like is audited). */
    public static final class Audit implements AuditSink {
        public final List<Map<String, String>> finals = Collections.synchronizedList(new ArrayList<Map<String, String>>());
        public final List<Map<String, String>> all = Collections.synchronizedList(new ArrayList<Map<String, String>>());

        @Override
        public void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details) {
            all.add(details);
            if (event == Event.LIVENESS_FAILED_FINAL) finals.add(details);
        }
    }
}
