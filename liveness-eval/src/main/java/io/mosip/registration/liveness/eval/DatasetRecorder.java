package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Records one PAD-evaluation take (development only, with the subject's consent):
 * <pre>
 *   DatasetRecorder --consent --label bona_fide|print_flat|print_curved|screen_photo|screen_replay
 *                   --subject sNN --take N [--seconds 6] [--fps 15] [--out eval-data] [--camera 0]
 * </pre>
 * Writes {@code <out>/bona_fide/<subject>/<take>/frame_00000.png ...} (attacks under {@code attack/<species>/}) and
 * {@code meta.json}. {@code eval-data/} is gitignored: frames of real people are never committed. Subjects are codes
 * (sNN), never names. Refuses to run without {@code --consent}.
 */
public final class DatasetRecorder {

    public static final List<String> LABELS = Arrays.asList("bona_fide", "print_flat", "print_curved", "screen_photo", "screen_replay");
    private static final Pattern SUBJECT = Pattern.compile("^s\\d{2,3}$");

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
                    Double.parseDouble(a.has("--fps") ? a.single("--fps") : "15"),
                    new File(a.has("--out") ? a.single("--out") : "eval-data"));
            int camera = Integer.parseInt(a.has("--camera") ? a.single("--camera") : "0");
            File dir = record(new WebcamFrameSource(camera, 640, 480, t.fps), t, out);
            out.println("wrote " + dir);
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println("usage: DatasetRecorder --consent --label " + LABELS + " --subject sNN --take N"
                    + " [--seconds 6] [--fps 15] [--out eval-data] [--camera 0]");
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            return 1;
        }
    }

    /** One take's settings, validated. */
    static final class Take {
        final String label, subject;
        final int take, seconds;
        final double fps;
        final File root;

        Take(String label, String subject, int take, int seconds, double fps, File root) {
            if (!LABELS.contains(label)) throw new IllegalArgumentException("--label must be one of " + LABELS);
            if (!SUBJECT.matcher(subject).matches()) throw new IllegalArgumentException("--subject must be a code like s01 (no names)");
            if (take < 1 || take > 99) throw new IllegalArgumentException("--take must be 1..99");
            if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("--seconds must be 1..30");
            if (!(fps > 0 && fps <= 30)) throw new IllegalArgumentException("--fps must be in (0, 30]");
            this.label = label;
            this.subject = subject;
            this.take = take;
            this.seconds = seconds;
            this.fps = fps;
            this.root = root;
        }

        File dir() {
            File base = label.equals("bona_fide") ? new File(root, "bona_fide") : new File(new File(root, "attack"), label);
            return new File(new File(base, subject), String.valueOf(take));
        }
    }

    /** Records the take from the source; refuses to overwrite an existing take. */
    static File record(FrameSource source, Take t, PrintStream out) throws IOException, InterruptedException {
        File dir = t.dir();
        if (dir.exists()) throw new IOException(dir + " already exists; choose another --take");
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        final int wanted = (int) Math.round(t.seconds * t.fps);
        final AtomicInteger written = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<String>();
        final CountDownLatch done = new CountDownLatch(1);
        final File target = dir;
        if (out != null) out.println("recording " + wanted + " frames to " + dir);
        source.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame f) {
                int n = written.get();
                if (n >= wanted) return;
                try {
                    ImageIO.write(toImage(f), "png", new File(target, String.format("frame_%05d.png", n)));
                    if (written.incrementAndGet() >= wanted) done.countDown();
                } catch (IOException e) {
                    failure.set(e.toString());
                    done.countDown();
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
        FrameSource.DeviceInfo info = source.info();
        String meta = "{\n"
                + "  \"label\": \"" + t.label + "\",\n"
                + "  \"subject\": \"" + t.subject + "\",\n"
                + "  \"take\": " + t.take + ",\n"
                + "  \"fps\": " + t.fps + ",\n"
                + "  \"frames\": " + written.get() + ",\n"
                + "  \"device\": {\"deviceId\": \"" + info.deviceId + "\", \"model\": \"" + info.model.replace("\"", "'")
                + "\", \"width\": " + info.maxWidth + ", \"height\": " + info.maxHeight + "},\n"
                + "  \"recordedUtc\": \"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\",\n"
                + "  \"consent\": true\n"
                + "}\n";
        Files.write(new File(dir, "meta.json").toPath(), meta.getBytes(StandardCharsets.UTF_8));
        return dir;
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
