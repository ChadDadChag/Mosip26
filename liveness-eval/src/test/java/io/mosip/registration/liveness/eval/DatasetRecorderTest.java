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
        DatasetRecorder.Take take = new DatasetRecorder.Take("bona_fide", "s01", 1, 1, 5, root);
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
                "\"consent\": true", "\"deviceId\": \"fake:0\"", "recordedUtc", "fast 'fake'");
        // A take is never overwritten.
        assertThatThrownBy(() -> DatasetRecorder.record(new FastSource(), take, null)).hasMessageContaining("already exists");
    }

    @Test
    void attacksGoUnderTheirSpecies(@TempDir File root) throws Exception {
        File dir = DatasetRecorder.record(new FastSource(), new DatasetRecorder.Take("screen_replay", "s02", 3, 1, 2, root), null);
        assertThat(dir).isEqualTo(new File(new File(new File(new File(root, "attack"), "screen_replay"), "s02"), "3"));
        assertThat(dir.list()).contains("meta.json", "frame_00000.png", "frame_00001.png");
    }
}
