package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Records one PAD-evaluation take (development only, with the subject's consent; see
 * {@code docs/part2-dataset-protocol.md}):
 * <pre>
 *   DatasetRecorder --consent --label bona_fide|screen_photo|screen_photo_close|screen_replay|...
 *                   --subject sNN --take N [--condition normal] [--seconds 6] [--fps 10] [--format jpg|png]
 *                   [--out eval-data] [--camera 0]
 * </pre>
 * Writes {@code <out>/bona_fide/<subject>/<take>/frame_00000.jpg ...} (attacks under {@code attack/<species>/}) and
 * {@code meta.json}. JPEG frames are quality 95 with 4:4:4 chroma (no colour subsampling, which would blur the
 * screen and print textures PAD looks at). Frames are paced to {@code --fps} by their timestamps, kept in memory while
 * recording and written afterwards (so PNG at 30 fps does not slow the camera down); {@code meta.json} counts the
 * frames identical to the one before ({@code duplicates}: a camera slower than {@code --fps} repeats frames).
 * {@code eval-data/} is gitignored: frames of real people are never committed. Subjects are codes (sNN), never names.
 * Refuses to run without {@code --consent}.
 */
public final class DatasetRecorder {

    /** ISO/IEC 30107-3 presentation classes: bona fide, then the attack species (PAI species) of the protocol. */
    public static final List<String> LABELS = Arrays.asList("bona_fide", "screen_photo", "screen_photo_close",
            "screen_replay", "screen_replay_monitor", "print_flat", "print_curved");
    public static final List<String> FORMATS = Arrays.asList("jpg", "png");
    static final float JPEG_QUALITY = 0.95f;
    private static final Pattern SUBJECT = Pattern.compile("^s\\d{2,3}$");
    private static final Pattern CONDITION = Pattern.compile("^[a-z0-9_]{1,24}$");

    private DatasetRecorder() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ModelBundleTool.Args a;
        try {
            a = ModelBundleTool.Args.parse(args, 0, Arrays.asList("--force", "--consent"));
            if (!a.has("--consent")) {
                err.println("REFUSED: recording a person requires their consent; pass --consent once they have agreed.");
                return 1;
            }
            Take t = new Take(a.required("--label"), a.required("--subject"), Integer.parseInt(a.required("--take")),
                    Integer.parseInt(a.has("--seconds") ? a.single("--seconds") : "6"),
                    Double.parseDouble(a.has("--fps") ? a.single("--fps") : "10"),
                    new File(a.has("--out") ? a.single("--out") : "eval-data"),
                    a.has("--format") ? a.single("--format") : "jpg",
                    a.has("--condition") ? a.single("--condition") : null);
            int camera = Integer.parseInt(a.has("--camera") ? a.single("--camera") : "0");
            File dir = record(new WebcamFrameSource(camera, 640, 480, Math.min(30, t.fps * 1.5)), t, out);
            out.println("wrote " + dir);
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println("usage: DatasetRecorder --consent --label " + LABELS + " --subject sNN --take N [--condition c]"
                    + " [--seconds 6] [--fps 10] [--format jpg|png] [--out eval-data] [--camera 0]");
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            return 1;
        }
    }

    /** One take's settings, validated. */
    static final class Take {
        final String label, subject, format, condition;
        final int take, seconds;
        final double fps;
        final File root;
        private File dirOverride;

        Take(String label, String subject, int take, int seconds, double fps, File root, String format, String condition) {
            if (!LABELS.contains(label)) throw new IllegalArgumentException("--label must be one of " + LABELS);
            if (!SUBJECT.matcher(subject).matches()) throw new IllegalArgumentException("--subject must be a code like s01 (no names)");
            if (take < 1 || take > 99) throw new IllegalArgumentException("--take must be 1..99");
            if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("--seconds must be 1..30");
            if (!(fps > 0 && fps <= 30)) throw new IllegalArgumentException("--fps must be in (0, 30]");
            if (!FORMATS.contains(format)) throw new IllegalArgumentException("--format must be one of " + FORMATS);
            if (condition != null && !CONDITION.matcher(condition).matches()) {
                throw new IllegalArgumentException("--condition must be a short code like normal or selfie");
            }
            this.label = label;
            this.subject = subject;
            this.take = take;
            this.seconds = seconds;
            this.fps = fps;
            this.root = root;
            this.format = format;
            this.condition = condition;
        }

        /**
         * Records into {@code dir} instead of the protocol layout (regression clips and banding takes, which
         * PadEvaluation must not pick up).
         */
        Take in(File dir) {
            this.dirOverride = dir;
            return this;
        }

        File dir() {
            if (dirOverride != null) return dirOverride;
            File base = label.equals("bona_fide") ? new File(root, "bona_fide") : new File(new File(root, "attack"), label);
            return new File(new File(base, subject), String.valueOf(take));
        }
    }

    /** Records the take from the source; refuses to overwrite an existing take. */
    static File record(FrameSource source, final Take t, PrintStream out) throws IOException, InterruptedException {
        File dir = t.dir();
        if (dir.exists()) throw new IOException(dir + " already exists; choose another --take");
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        final int wanted = (int) Math.round(t.seconds * t.fps);
        final double intervalMs = 1000.0 / t.fps;
        final List<Frame> frames = new ArrayList<Frame>();
        final AtomicReference<String> failure = new AtomicReference<String>();
        final CountDownLatch done = new CountDownLatch(1);
        if (out != null) out.println("recording " + wanted + " frames to " + dir);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame f) {
                synchronized (frames) {
                    int n = frames.size();
                    if (n >= wanted) return;
                    // Pace by timestamps: frame n is due at first + n * interval; a faster camera's extra frames are dropped.
                    long first = n == 0 ? 0 : frames.get(0).timestampMs();
                    if (n > 0 && f.timestampMs() - first < Math.round(n * intervalMs) - intervalMs / 4) return;
                    frames.add(f);
                    if (frames.size() >= wanted) done.countDown();
                }
            }

            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                if (!recoverable) {
                    failure.set(code + ": " + detail);
                    done.countDown();
                }
            }
        });
        try {
            done.await(t.seconds * 3L + 15, TimeUnit.SECONDS);
        } finally {
            source.stop();
        }
        if (failure.get() != null) throw new IOException("recording failed: " + failure.get());
        List<Frame> kept;
        synchronized (frames) {
            kept = new ArrayList<Frame>(frames);
        }
        List<Long> times = new ArrayList<Long>();
        int[] size = new int[2];
        int duplicates = 0;
        for (int n = 0; n < kept.size(); n++) {
            Frame f = kept.get(n);
            File file = new File(dir, String.format("frame_%05d.%s", n, t.format));
            BufferedImage img = toImage(f);
            if (t.format.equals("jpg")) writeJpeg(img, file, JPEG_QUALITY);
            else if (!ImageIO.write(img, "png", file)) throw new IOException("no PNG writer");
            if (n > 0 && Arrays.equals(f.rgb(), kept.get(n - 1).rgb())) duplicates++;
            size[0] = f.width();
            size[1] = f.height();
            times.add(f.timestampMs());
        }
        Files.write(new File(dir, "meta.json").toPath(),
                meta(t, source.info(), times, size, duplicates).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    static String meta(Take t, FrameSource.DeviceInfo info, List<Long> times, int[] size, int duplicates) {
        long duration = times.size() < 2 ? 0 : times.get(times.size() - 1) - times.get(0);
        return "{\n"
                + "  \"label\": \"" + t.label + "\",\n"
                + "  \"kind\": \"" + (t.label.equals("bona_fide") ? "bona_fide" : "attack") + "\",\n"
                + "  \"subject\": \"" + t.subject + "\",\n"
                + "  \"take\": " + t.take + ",\n"
                + (t.condition == null ? "" : "  \"condition\": \"" + t.condition + "\",\n")
                + "  \"fps\": " + t.fps + ",\n"
                + "  \"frames\": " + times.size() + ",\n"
                + "  \"duplicates\": " + duplicates + ",\n"
                + "  \"durationMs\": " + duration + ",\n"
                + "  \"format\": \"" + t.format + "\",\n"
                + (t.format.equals("jpg") ? "  \"jpegQuality\": " + JPEG_QUALITY + ",\n  \"chroma\": \"4:4:4\",\n" : "")
                + "  \"width\": " + size[0] + ",\n"
                + "  \"height\": " + size[1] + ",\n"
                + "  \"device\": {\"deviceId\": \"" + json(info.deviceId) + "\", \"vendor\": \"" + json(info.vendor)
                + "\", \"model\": \"" + json(info.model) + "\", \"certificationLevel\": \"" + json(info.certificationLevel)
                + "\", \"maxWidth\": " + info.maxWidth + ", \"maxHeight\": " + info.maxHeight + ", \"maxFps\": " + info.maxFps + "},\n"
                + "  \"recordedUtc\": \"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\",\n"
                + "  \"consent\": true\n"
                + "}\n";
    }

    private static String json(String s) {
        return s == null ? "" : s.replace("\\", "/").replace("\"", "'");
    }

    /** Baseline JPEG at the given quality with 4:4:4 chroma (every component sampled 1x1). */
    static void writeJpeg(BufferedImage img, File file, float quality) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            IIOMetadata md = w.getDefaultImageMetadata(new ImageTypeSpecifier(img), p);
            String format = "javax_imageio_jpeg_image_1.0";
            Element tree = (Element) md.getAsTree(format);
            NodeList components = tree.getElementsByTagName("componentSpec");
            for (int i = 0; i < components.getLength(); i++) {
                Element c = (Element) components.item(i);
                c.setAttribute("HsamplingFactor", "1");
                c.setAttribute("VsamplingFactor", "1");
            }
            md.setFromTree(format, tree);
            if (file.exists() && !file.delete()) throw new IOException("cannot replace " + file);
            ImageOutputStream out = ImageIO.createImageOutputStream(file);
            try {
                w.setOutput(out);
                w.write(null, new IIOImage(img, null, md), p);
            } finally {
                out.close();
            }
        } finally {
            w.dispose();
        }
    }

    static BufferedImage toImage(Frame f) {
        byte[] rgb = f.rgb();
        BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
        int[] px = new int[f.width() * f.height()];
        for (int i = 0; i < px.length; i++) {
            px[i] = ((rgb[3 * i] & 0xFF) << 16) | ((rgb[3 * i + 1] & 0xFF) << 8) | (rgb[3 * i + 2] & 0xFF);
        }
        img.setRGB(0, 0, f.width(), f.height(), px, 0, f.width());
        return img;
    }
}
