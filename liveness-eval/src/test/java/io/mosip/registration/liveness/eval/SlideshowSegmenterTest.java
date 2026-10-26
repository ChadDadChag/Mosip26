package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SlideshowSegmenterTest {

    private static final int N = SlideshowSegmenter.NOBODY;
    private static final File GOLDEN = new File("../liveness-analyzer/src/test/resources/golden");

    @Test
    void runsAllowShortGapsAndEndAtAnotherPerson() {
        List<SlideshowSegmenter.Run> runs = SlideshowSegmenter.runs(new int[] {0, 0, N, 0, 0, N, N, N, 0, 1, 1, N, 1}, 2);
        assertThat(runs).hasSize(3);
        assertThat(runs.get(0).person).isEqualTo(0);
        assertThat(runs.get(0).first).isEqualTo(0);
        assertThat(runs.get(0).last).as("a gap of 3 ends the run").isEqualTo(4);
        assertThat(runs.get(1).first).isEqualTo(8);
        assertThat(runs.get(1).last).as("another person ends it at once").isEqualTo(8);
        assertThat(runs.get(2).person).isEqualTo(1);
        assertThat(runs.get(2).length()).isEqualTo(4);
        assertThat(SlideshowSegmenter.runs(new int[] {N, N}, 2)).isEmpty();
    }

    @Test
    void aFrameBelongsToAPersonOnlyWithAClearMatch() {
        SlideshowSegmenter.Settings s = new SlideshowSegmenter.Settings(0.45, 0.05, 1, 0, 0);
        List<float[]> photos = Arrays.asList(new float[] {1, 0, 0}, new float[] {0.8f, 0.6f, 0}, new float[] {0, 0, 1});
        List<Integer> owner = Arrays.asList(0, 0, 1);
        assertThat(SlideshowSegmenter.classify(new float[] {1, 0, 0}, photos, owner, s)).isEqualTo(0);
        assertThat(SlideshowSegmenter.classify(new float[] {0, 0, 1}, photos, owner, s)).isEqualTo(1);
        assertThat(SlideshowSegmenter.classify(new float[] {0, 1, 0}, photos, owner, s)).as("best 0.6 is person 0").isEqualTo(0);
        assertThat(SlideshowSegmenter.classify(new float[] {-1, 0, 0}, photos, owner, s)).as("below --match").isEqualTo(N);
        float[] between = {0.7071f, 0, 0.7071f}; // 0.707 to both people: no margin
        assertThat(SlideshowSegmenter.classify(between, photos, owner, s)).isEqualTo(N);
    }

    @Test
    void labelsSkipRowsWithoutAPersonAndAcceptQuotes(@TempDir File dir) throws Exception {
        File csv = new File(dir, "labels.csv");
        Files.write(csv.toPath(), ("﻿file,person_id,faces\n\"a, b.jpeg\",p01,1\nc.jpeg,,0\n\nd.jpeg,p02,2\n")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> m = SlideshowSegmenter.readLabels(csv);
        assertThat(m).containsExactly(entry("a, b.jpeg", "p01"), entry("d.jpeg", "p02"));
        assertThat(SlideshowSegmenter.fields("x,\"say \"\"hi\"\"\",")).containsExactly("x", "say \"hi\"", "");
    }

    private static BufferedImage onCanvas(BufferedImage picture, int height) {
        BufferedImage c = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = c.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, 640, 480);
        if (picture != null) {
            int w = picture.getWidth() * height / picture.getHeight();
            g.drawImage(picture, (640 - w) / 2, (480 - height) / 2, w, height, null);
        }
        g.dispose();
        return c;
    }

    @Test
    void aRecordingIsCutIntoOneTakePerPresentation(@TempDir File dir) throws Exception {
        BufferedImage nasa = ImageIO.read(new File(GOLDEN, "portrait_frontal.png"));
        BufferedImage crew = ImageIO.read(new File(GOLDEN, "crew_multi.png")).getSubimage(160, 90, 150, 200);
        File photos = new File(dir, "photos");
        assertThat(photos.mkdirs()).isTrue();
        ImageIO.write(nasa, "png", new File(photos, "a.png"));
        ImageIO.write(crew, "png", new File(photos, "b.png"));
        Files.write(new File(photos, "labels.csv").toPath(),
                "file,person_id,faces\na.png,p01,1\nb.png,p02,1\n".getBytes(StandardCharsets.UTF_8));
        File stream = new File(dir, "stream");
        assertThat(stream.mkdirs()).isTrue();
        int n = 0;
        BufferedImage[] sequence = new BufferedImage[12 + 4 + 12 + 2 + 3];
        for (int i = 0; i < 12; i++) sequence[n++] = onCanvas(nasa, 300);
        for (int i = 0; i < 4; i++) sequence[n++] = onCanvas(null, 0);
        for (int i = 0; i < 12; i++) sequence[n++] = onCanvas(crew, 320);
        for (int i = 0; i < 2; i++) sequence[n++] = onCanvas(null, 0);
        for (int i = 0; i < 3; i++) sequence[n++] = onCanvas(nasa, 300);
        for (int i = 0; i < sequence.length; i++) {
            ImageIO.write(sequence[i], "png", new File(stream, String.format("frame_%05d.png", i)));
        }
        Files.write(new File(stream, "meta.json").toPath(), ("{\n  \"label\": \"screen_photo_multi\",\n  \"fps\": 10.0,\n"
                + "  \"format\": \"png\",\n  \"width\": 640,\n  \"height\": 480,\n  \"device\": {\"deviceId\": \"webcam:0\","
                + " \"maxFps\": 15},\n  \"consent\": true\n}\n").getBytes(StandardCharsets.UTF_8));

        File root = new File(dir, "eval-data");
        SlideshowSegmenter.Settings s = new SlideshowSegmenter.Settings(0.45, 0.05, 6, 1, 2);
        SlideshowSegmenter.Result dry = SlideshowSegmenter.segment(stream, new File(photos, "labels.csv"),
                "screen_photo_multi", "slideshow", root, s, true);
        assertThat(dry.photos).isEqualTo(2);
        assertThat(dry.matched).isEqualTo(27);
        assertThat(dry.noFace).isEqualTo(6);
        assertThat(dry.kept).hasSize(2);
        assertThat(dry.dropped).as("the last 3 frames, 1 after trimming").hasSize(1);
        assertThat(root).doesNotExist();
        assertThat(dry.summary(true)).contains("people covered: 2 of 2", "dry run").doesNotContain(".png");

        SlideshowSegmenter.Result r = SlideshowSegmenter.segment(stream, new File(photos, "labels.csv"),
                "screen_photo_multi", "slideshow", root, s, false);
        assertThat(r.written).hasSize(2);
        List<EvalDataset.TakeRef> takes = EvalDataset.scan(root);
        assertThat(takes).hasSize(2);
        assertThat(takes).extracting(t -> t.species).containsOnly("screen_photo_multi");
        assertThat(takes).extracting(t -> t.subject).containsExactly("p01", "p02");
        File first = r.written.get(0);
        assertThat(first.list()).contains("frame_00000.png", "frame_00009.png").doesNotContain("frame_00010.png");
        String meta = new String(Files.readAllBytes(new File(first, "meta.json").toPath()), StandardCharsets.UTF_8);
        assertThat(meta).contains("\"subject\": \"p01\"", "\"frames\": 10", "\"durationMs\": 900", "\"condition\": \"slideshow\"",
                "\"deviceId\": \"webcam:0\"", "\"segmentedFrom\": \"stream\"", "\"firstFrame\": 1", "\"consent\": true");
        // A second recording of the same people gets the next take numbers.
        SlideshowSegmenter.segment(stream, new File(photos, "labels.csv"), "screen_photo_multi", "slideshow", root, s, false);
        assertThat(EvalDataset.scan(root)).hasSize(4);
        assertThat(new File(root, "attack/screen_photo_multi/p02/2")).isDirectory();
    }

    @Test
    void badArgumentsAreRejected() {
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream e = new java.io.PrintStream(err, true);
        java.io.PrintStream o = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
        assertThat(SlideshowSegmenter.run(new String[] {"--stream", "x"}, o, e)).isEqualTo(2);
        assertThat(SlideshowSegmenter.run(new String[] {"--stream", "x", "--labels", "y", "--species", "bona_fide"}, o, e))
                .isEqualTo(2);
        assertThat(new String(err.toByteArray(), StandardCharsets.UTF_8)).contains("usage", "attack species");
    }
}
