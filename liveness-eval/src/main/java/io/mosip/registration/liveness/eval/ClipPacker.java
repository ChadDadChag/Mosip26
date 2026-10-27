package io.mosip.registration.liveness.eval;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Packs a recorded frame folder ({@code frame_*.jpg} or {@code .png}, in name order) into an MJPEG clip for Part 1's
 * {@code MockFrameSource.ofVideoFile} and {@code RecordedVideoE2ETest} (development only):
 * <pre>
 *   ClipPacker &lt;framesDir&gt; &lt;out.mjpeg&gt; [--max-bytes 2000000]
 * </pre>
 * The framing is {@code MjpegRecorder}'s, which is the SBI {@code /stream} framing: per frame
 * {@code --BoundaryString}, {@code Content-type: image/jpeg}, {@code Content-Length: n}, a blank line, the JPEG and
 * two CRLFs. Every frame is re-encoded at the highest quality in {@link #QUALITIES} that keeps the whole clip at or
 * under {@code --max-bytes}, because committed clips are kept small. Used for the F16 regression clips, which show
 * only the public-domain NASA portrait and must be checked frame by frame before they are committed.
 */
public final class ClipPacker {

    static final float[] QUALITIES = {0.90f, 0.85f, 0.80f, 0.75f, 0.70f, 0.65f, 0.60f, 0.55f, 0.50f};
    static final long DEFAULT_MAX_BYTES = 2_000_000L;

    private ClipPacker() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println("usage: ClipPacker <framesDir> <out.mjpeg> [--max-bytes " + DEFAULT_MAX_BYTES + "]");
            return 2;
        }
        try {
            ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 2);
            long max = a.has("--max-bytes") ? Long.parseLong(a.single("--max-bytes")) : DEFAULT_MAX_BYTES;
            Result r = pack(new File(args[0]), new File(args[1]), max);
            out.println(String.format(Locale.ROOT, "wrote %s: %d frames, JPEG quality %.2f, %d bytes", args[1], r.frames,
                    r.quality, r.bytes));
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    /** What was written. */
    static final class Result {
        final int frames;
        final float quality;
        final long bytes;

        Result(int frames, float quality, long bytes) {
            this.frames = frames;
            this.quality = quality;
            this.bytes = bytes;
        }
    }

    static Result pack(File framesDir, File out, long maxBytes) throws IOException {
        List<BufferedImage> images = new ArrayList<BufferedImage>();
        for (File f : frames(framesDir)) {
            BufferedImage img = ImageIO.read(f);
            if (img == null) throw new IOException("unreadable frame " + f);
            images.add(toRgb(img));
        }
        if (images.isEmpty()) throw new IOException("no frame_*.jpg or frame_*.png in " + framesDir);
        for (float q : QUALITIES) {
            List<byte[]> jpegs = new ArrayList<byte[]>();
            long total = 0;
            for (BufferedImage img : images) {
                byte[] j = jpeg(img, q);
                jpegs.add(j);
                total += header(j.length).length + j.length + 4;
                if (total > maxBytes) break;
            }
            if (total > maxBytes) continue;
            OutputStream o = new BufferedOutputStream(new FileOutputStream(out));
            try {
                for (byte[] j : jpegs) {
                    o.write(header(j.length));
                    o.write(j);
                    o.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            } finally {
                o.close();
            }
            return new Result(jpegs.size(), q, out.length());
        }
        throw new IOException(images.size() + " frames do not fit in " + maxBytes + " bytes even at JPEG quality "
                + QUALITIES[QUALITIES.length - 1]);
    }

    /** frame_*.jpg / frame_*.png in name order. */
    static List<File> frames(File dir) {
        File[] files = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && n.startsWith("frame_") && (n.endsWith(".jpg") || n.endsWith(".png"))) out.add(f);
        }
        return out;
    }

    private static byte[] header(int length) {
        return ("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: " + length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(img, 0, 0, null);
        return rgb;
    }

    static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            ImageOutputStream ios = ImageIO.createImageOutputStream(bytes);
            try {
                w.setOutput(ios);
                w.write(null, new IIOImage(img, null, null), p);
            } finally {
                ios.close();
            }
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }
}
