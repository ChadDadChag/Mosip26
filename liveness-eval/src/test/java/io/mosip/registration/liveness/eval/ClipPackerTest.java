package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipPackerTest {

    private static void frames(File dir, int n, String ext) throws IOException {
        Random rnd = new Random(3);
        for (int i = 0; i < n; i++) {
            BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, rnd.nextInt(0x1000000));
            ImageIO.write(img, ext, new File(dir, String.format("frame_%05d.%s", i, ext.equals("jpeg") ? "jpg" : ext)));
        }
    }

    /** The JPEGs of an MJPEG file, read by the framing MjpegRecorder writes. */
    static List<byte[]> read(File mjpeg) throws IOException {
        byte[] all = Files.readAllBytes(mjpeg.toPath());
        String text = new String(all, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: (\\d+)\r\n\r\n")
                .matcher(text);
        List<byte[]> out = new ArrayList<byte[]>();
        int pos = 0;
        while (m.find(pos)) {
            assertThat(m.start()).isEqualTo(pos);
            int len = Integer.parseInt(m.group(1));
            byte[] j = new byte[len];
            System.arraycopy(all, m.end(), j, 0, len);
            out.add(j);
            assertThat(text.substring(m.end() + len, m.end() + len + 4)).isEqualTo("\r\n\r\n");
            pos = m.end() + len + 4;
        }
        assertThat(pos).isEqualTo(all.length);
        return out;
    }

    @Test
    void packsEveryFrameInOrderWithTheSbiStreamFraming(@TempDir File dir) throws Exception {
        frames(dir, 5, "png");
        File out = new File(dir, "clip.mjpeg");
        ClipPacker.Result r = ClipPacker.pack(dir, out, ClipPacker.DEFAULT_MAX_BYTES);
        assertThat(r.frames).isEqualTo(5);
        assertThat(r.quality).isEqualTo(0.90f);
        assertThat(r.bytes).isEqualTo(out.length());
        List<byte[]> jpegs = read(out);
        assertThat(jpegs).hasSize(5);
        for (byte[] j : jpegs) {
            assertThat(j[0] & 0xFF).isEqualTo(0xFF);
            assertThat(j[1] & 0xFF).isEqualTo(0xD8);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(j));
            assertThat(img.getWidth()).isEqualTo(64);
            assertThat(img.getHeight()).isEqualTo(48);
        }
    }

    @Test
    void lowersTheQualityUntilTheClipFits(@TempDir File dir) throws Exception {
        frames(dir, 4, "jpeg");
        File out = new File(dir, "clip.mjpeg");
        long full = ClipPacker.pack(dir, out, ClipPacker.DEFAULT_MAX_BYTES).bytes;
        ClipPacker.Result r = ClipPacker.pack(dir, out, full - 1);
        assertThat(r.quality).isLessThan(0.90f);
        assertThat(r.bytes).isLessThanOrEqualTo(full - 1);
        assertThat(read(out)).hasSize(4);
        assertThatThrownBy(() -> ClipPacker.pack(dir, new File(dir, "tiny.mjpeg"), 100)).hasMessageContaining("do not fit");
    }

    @Test
    void refusesAFolderWithoutFramesAndBadArguments(@TempDir File dir) {
        assertThatThrownBy(() -> ClipPacker.pack(dir, new File(dir, "x.mjpeg"), 1000)).hasMessageContaining("no frame_");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream e = new PrintStream(err, true);
        PrintStream o = new PrintStream(new ByteArrayOutputStream());
        assertThat(ClipPacker.run(new String[] {dir.getPath()}, o, e)).isEqualTo(2);
        assertThat(ClipPacker.run(new String[] {dir.getPath(), new File(dir, "x.mjpeg").getPath()}, o, e)).isEqualTo(1);
        assertThat(new String(err.toByteArray(), StandardCharsets.UTF_8)).contains("usage", "no frame_");
    }
}
