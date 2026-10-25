package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetRecorderTest {

    /** Delivers small frames as fast as possible until stopped. */
    static final class FastSource implements FrameSource {
        private volatile boolean running;

        @Override public DeviceInfo info() {
            return new DeviceInfo("fake:0", "test", "fast \"fake\"", "none", "NONE", 8, 6, 30);
        }

        @Override public void start(final Listener l) {
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    long seq = 0;
                    while (running) {
                        byte[] rgb = new byte[8 * 6 * 3];
                        rgb[0] = (byte) 200; // first pixel red
                        l.onFrame(new Frame(rgb, 8, 6, seq + 1, seq, "fake:0"));
                        seq++;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        @Override public void stop() {
            running = false;
        }

        @Override public boolean isRunning() {
            return running;
        }
    }

    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int run(String... args) {
        errBuf.reset();
        return DatasetRecorder.run(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errBuf, true));
    }

    @Test
    void refusesWithoutConsent(@TempDir File root) {
        assertThat(run("--label", "bona_fide", "--subject", "s01", "--take", "1", "--out", root.getPath())).isEqualTo(1);
        assertThat(new String(errBuf.toByteArray(), StandardCharsets.UTF_8)).contains("consent");
        assertThat(root.list()).isEmpty();
    }

    @Test
    void rejectsBadArguments(@TempDir File root) {
        String out = root.getPath();
        assertThat(run("--consent", "--label", "mask", "--subject", "s01", "--take", "1", "--out", out)).isEqualTo(2);
        assertThat(run("--consent", "--label", "bona_fide", "--subject", "Alice", "--take", "1", "--out", out)).isEqualTo(2);
        assertThat(run("--consent", "--label", "bona_fide", "--subject", "s01", "--take", "0", "--out", out)).isEqualTo(2);
        assertThat(run("--consent", "--label", "bona_fide", "--subject", "s01", "--take", "1", "--seconds", "99", "--out", out)).isEqualTo(2);
        assertThat(run("--consent", "--label", "bona_fide")).isEqualTo(2);
        assertThat(root.list()).isEmpty();
    }

    @Test
    void writesTheBonaFideLayoutFramesAndMeta(@TempDir File root) throws Exception {
        DatasetRecorder.Take take = new DatasetRecorder.Take("bona_fide", "s01", 1, 1, 5, root, "png", "normal");
        File dir = DatasetRecorder.record(new FastSource(), take, null);
        assertThat(dir).isEqualTo(new File(new File(new File(root, "bona_fide"), "s01"), "1"));
        assertThat(new File(dir, "frame_00000.png")).exists();
        assertThat(new File(dir, "frame_00004.png")).exists();
        assertThat(new File(dir, "frame_00005.png")).doesNotExist();
        BufferedImage img = ImageIO.read(new File(dir, "frame_00000.png"));
        assertThat(img.getWidth()).isEqualTo(8);
        assertThat((img.getRGB(0, 0) >> 16) & 0xFF).isEqualTo(200);
        String meta = new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8);
        assertThat(meta).contains("\"label\": \"bona_fide\"", "\"subject\": \"s01\"", "\"take\": 1", "\"frames\": 5",
                "\"consent\": true", "\"deviceId\": \"fake:0\"", "recordedUtc", "fast 'fake'",
                "\"condition\": \"normal\"", "\"kind\": \"bona_fide\"", "\"format\": \"png\"", "\"width\": 8");
        // A take is never overwritten.
        assertThatThrownBy(() -> DatasetRecorder.record(new FastSource(), take, null)).hasMessageContaining("already exists");
    }

    @Test
    void jpegFramesAreQuality95WithFullChromaAndPacedToTheFrameRate(@TempDir File root) throws Exception {
        // FastSource stamps frames 1 ms apart: at 5 fps frame n is due 200 n ms after the first (minus a quarter).
        File dir = DatasetRecorder.record(new FastSource(),
                new DatasetRecorder.Take("screen_photo_close", "s01", 4, 1, 5, root, "jpg", "nasa"), null);
        assertThat(dir.list()).contains("frame_00000.jpg", "frame_00004.jpg").doesNotContain("frame_00005.jpg");
        String meta = new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8);
        assertThat(meta).contains("\"durationMs\": 750", "\"jpegQuality\": 0.95", "\"chroma\": \"4:4:4\"",
                "\"kind\": \"attack\"", "\"condition\": \"nasa\"", "\"frames\": 5");

        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xC83214);
        File f = new File(root, "probe.jpg");
        DatasetRecorder.writeJpeg(img, f, DatasetRecorder.JPEG_QUALITY);
        javax.imageio.ImageReader r = ImageIO.getImageReadersByFormatName("jpeg").next();
        javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(f);
        try {
            r.setInput(in);
            org.w3c.dom.NodeList comps = ((org.w3c.dom.Element) r.getImageMetadata(0)
                    .getAsTree("javax_imageio_jpeg_image_1.0")).getElementsByTagName("componentSpec");
            assertThat(comps.getLength()).isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                org.w3c.dom.Element c = (org.w3c.dom.Element) comps.item(i);
                assertThat(c.getAttribute("HsamplingFactor")).isEqualTo("1");
                assertThat(c.getAttribute("VsamplingFactor")).isEqualTo("1");
            }
            int px = r.read(0).getRGB(8, 8);
            assertThat((px >> 16) & 0xFF).isBetween(0xC8 - 4, 0xC8 + 4);
            assertThat((px >> 8) & 0xFF).isBetween(0x32 - 4, 0x32 + 4);
            assertThat(px & 0xFF).isBetween(0x14 - 4, 0x14 + 4);
        } finally {
            in.close();
            r.dispose();
        }
    }

    @Test
    void rejectsUnknownFormatsAndFreeTextConditions(@TempDir File root) {
        String out = root.getPath();
        assertThat(run("--consent", "--label", "bona_fide", "--subject", "s01", "--take", "1", "--format", "bmp", "--out", out)).isEqualTo(2);
        assertThat(run("--consent", "--label", "bona_fide", "--subject", "s01", "--take", "1", "--condition", "Alice at home",
                "--out", out)).isEqualTo(2);
        assertThat(root.list()).isEmpty();
    }

    @Test
    void attacksGoUnderTheirSpecies(@TempDir File root) throws Exception {
        File dir = DatasetRecorder.record(new FastSource(), new DatasetRecorder.Take("screen_replay", "s02", 3, 1, 2, root, "png", null), null);
        assertThat(dir).isEqualTo(new File(new File(new File(new File(root, "attack"), "screen_replay"), "s02"), "3"));
        assertThat(dir.list()).contains("meta.json", "frame_00000.png", "frame_00001.png");
    }
}
