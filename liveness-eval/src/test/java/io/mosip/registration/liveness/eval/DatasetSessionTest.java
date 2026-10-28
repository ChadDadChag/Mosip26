package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.FrameAnalysis;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DatasetSessionTest {

    private static List<DatasetSession.Step> select(String... only) {
        return DatasetSession.select(DatasetSession.plan(), only.length == 0 ? null : Arrays.asList(only));
    }

    @Test
    void theProtocolStepsAreUnchangedAndTheExtraStepsRunOnlyWhenNamed() {
        List<DatasetSession.Step> all = select();
        assertThat(all).hasSize(12 + 6 + 12 + 6 + 8 + 12);
        assertThat(all).allSatisfy(s -> {
            assertThat(s.group).isNull();
            assertThat(s.seconds).isEqualTo(6);
            assertThat(s.fps).isEqualTo(10.0);
            assertThat(s.format).isEqualTo("jpg");
        });
        // New subjects record bona fide only: the 12 protocol takes, never the banding take of the subject's face.
        assertThat(select("bona_fide")).hasSize(12).allSatisfy(s -> assertThat(s.group).isNull());
        assertThat(select("print_flat", "print_curved")).hasSize(12);
        assertThat(DatasetSession.cameraFps(all)).isEqualTo(15.0);
    }

    @Test
    void theF5RetakeHoldsTheSecondScreenAtRealFaceSizeUnderTheSameBlockName() {
        List<DatasetSession.Step> replay = select("screen_replay_monitor");
        assertThat(replay).extracting(st -> st.take).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(replay.subList(0, 4)).allSatisfy(st -> assertThat(st.condition).isEqualTo("selfie_video"));
        assertThat(replay.subList(4, 8)).allSatisfy(st -> {
            assertThat(st.condition).isEqualTo("video_realsize");
            assertThat(st.prompt).contains("AS BIG AS A REAL FACE", "face size 20-35%", "OUT of view", "S = skip");
            assertThat(st.take("s01", new File("eval-data")).dir())
                    .isEqualTo(new File("eval-data", "attack/screen_replay_monitor/s01/" + st.take));
        });
    }

    @Test
    void f16ClipsShowOnlyTheNasaPortraitAndGoOutsideTheProtocolLayout() {
        List<DatasetSession.Step> clips = select(DatasetSession.F16);
        assertThat(clips).extracting(s -> s.condition)
                .containsExactly("nasa_print_normal", "nasa_print_close", "nasa_phone_normal", "nasa_phone_close_fill");
        File root = new File("eval-data");
        for (DatasetSession.Step s : clips) {
            assertThat(s.prompt).contains("NASA").contains("anyone else OUT of view");
            assertThat(s.seconds).isEqualTo(5);
            assertThat(s.fps).isEqualTo(15.0);
            assertThat(s.take("s01", root).dir().getParentFile()).isEqualTo(new File(root, "f16-clips"));
        }
        assertThat(clips.get(3).take("s01", root).dir()).isEqualTo(new File(root, "f16-clips/nasa-phone-close-fill"));
        assertThat(DatasetSession.cameraFps(clips)).isEqualTo(22.5);
    }

    @Test
    void bandingTakesAreLosslessAt30Fps() {
        List<DatasetSession.Step> band = select(DatasetSession.BANDING);
        assertThat(band).extracting(s -> s.condition).containsExactly("nasa_fill_pwm30", "nasa_fill_100", "close_whole_30fps");
        for (DatasetSession.Step s : band) {
            assertThat(s.format).isEqualTo("png");
            assertThat(s.fps).isEqualTo(30.0);
            assertThat(s.seconds).isEqualTo(3);
            assertThat(s.take("s01", new File("eval-data")).dir())
                    .isEqualTo(new File("eval-data", "f2-banding/s01/" + s.condition));
        }
        assertThat(band.get(0).prompt).contains("30% BRIGHTNESS");
        assertThat(band.get(1).prompt).contains("100% BRIGHTNESS");
        assertThat(DatasetSession.cameraFps(band)).isEqualTo(30.0);
        assertThat(DatasetSession.select(DatasetSession.plan(), Collections.singletonList("nothing"))).isEmpty();
    }

    @Test
    void manyIdentitiesAreLongContinuousRecordingsForTheSegmenter() {
        List<DatasetSession.Step> show = select(DatasetSession.SLIDESHOW);
        assertThat(show).extracting(s -> s.condition).containsExactly("slideshow", "slideshow_close");
        assertThat(show).allSatisfy(s -> {
            assertThat(s.label).isEqualTo("screen_photo_multi");
            assertThat(s.seconds).isEqualTo(210);
        });
        List<DatasetSession.Step> prints = select(DatasetSession.PRINT_MULTI);
        assertThat(prints).extracting(s -> s.condition).containsExactly("flat", "curved");
        File root = new File("eval-data");
        for (DatasetSession.Step s : prints) {
            assertThat(s.label).isEqualTo("print_multi");
            assertThat(s.seconds).isEqualTo(240);
            assertThat(s.prompt).contains("out of view for 1 s").contains("OUT of view");
            assertThat(s.take("s01", root).dir()).isEqualTo(new File(root, "streams/s01/" + s.condition));
        }
        assertThat(DatasetSession.cameraFps(show)).isEqualTo(15.0);
        // One short recording per curved cut-out (SPACE before each), segmented like the long ones.
        List<DatasetSession.Step> each = select(DatasetSession.CUTOUTS_CURVED);
        assertThat(each).hasSize(12).allSatisfy(s -> {
            assertThat(s.label).isEqualTo("print_multi");
            assertThat(s.seconds).isEqualTo(13);
        });
        assertThat(each.get(0).take("s01", root).dir()).isEqualTo(new File(root, "streams/s01/curved_01"));
        assertThat(each.get(11).prompt).startsWith("CUT-OUT 12 of 12, CURVED");
    }

    @Test
    void theFaceSizeIsTheBoxShareOfThePictureForExactlyOneFace() {
        float[] box = {100, 80, 160, 192}; // 160 x 192 of 640 x 480: 10%
        assertThat(DatasetSession.Readout.areaOf(analysis(1, box), 640, 480)).isCloseTo(0.10f, within(1e-6f));
        assertThat(DatasetSession.Readout.areaOf(analysis(0, null), 640, 480)).isEqualTo(-1f);
        // Two faces: the analyzer still reports a box, but no single face's size.
        assertThat(DatasetSession.Readout.areaOf(analysis(2, box), 640, 480)).isEqualTo(-1f);
    }

    @Test
    void theFaceSizeLabelReadsNormallyOnTheMirroredPreviewAtTheTopLeft() {
        // The preview panel flips image coordinates when mirrored, overlay included. The label is drawn flipped back,
        // so after the panel's flip it is the plain label.
        BufferedImage plain = render(0.25f, false);
        BufferedImage shown = flip(render(0.25f, true));
        int label = 0;
        int differing = 0;
        for (int y = 0; y < plain.getHeight(); y++) {
            for (int x = 0; x < plain.getWidth(); x++) {
                if (plain.getRGB(x, y) != WHITE) label++;
                if (plain.getRGB(x, y) != shown.getRGB(x, y)) differing++;
            }
        }
        assertThat(label).isGreaterThan(5000);
        assertThat(differing).as("pixels that differ (glyph edges may round differently)").isLessThan(label / 50);
        // At the video's top-left, not mirrored to its right.
        assertThat(shown.getRGB(14, 14)).isNotEqualTo(WHITE);
        assertThat(shown.getRGB(625, 14)).isEqualTo(WHITE);
        // Green inside the 20-35% target, orange outside.
        assertThat(count(plain, GREEN)).isGreaterThan(100);
        assertThat(count(render(0.12f, false), ORANGE)).isGreaterThan(100);
        assertThat(count(render(0.12f, false), GREEN)).isZero();
    }

    @Test
    void theFaceSizeLabelJudgesEachStepAgainstTheTargetItsPromptStates() {
        // Not the F5 retake's 20-35% on every step (task 29, E6): close_whole asks for 25-40%, the fill and close
        // takes for 20% or more, and a step that states no size gets no colour judgement.
        assertThat(target("video_realsize")).containsExactly(0.20f, 0.35f);
        assertThat(target("close_whole")).containsExactly(0.25f, 0.40f);
        assertThat(target("selfie_fill")).containsExactly(0.20f, 1f);
        assertThat(target("nasa_close")).containsExactly(0.20f, 1f);
        assertThat(target("normal")).isNull();
        assertThat(DatasetSession.sizeTarget(SessionDemo.SECOND_SCREEN_REALSIZE)).containsExactly(0.20f, 0.35f);

        float[] whole = target("close_whole");
        assertThat(count(render(0.38f, false, whole), GREEN)).as("38% is inside close_whole's 25-40%").isGreaterThan(100);
        assertThat(count(render(0.22f, false, whole), ORANGE)).as("22% is below it").isGreaterThan(100);
        assertThat(count(render(0.50f, false, target("selfie_fill")), GREEN)).as("20% or more").isGreaterThan(100);
        BufferedImage none = render(0.30f, false, null);
        assertThat(count(none, GREEN) + count(none, ORANGE)).as("no target, no judgement").isZero();
    }

    /** The size target of the first protocol step with this condition. */
    private static float[] target(String condition) {
        for (DatasetSession.Step s : DatasetSession.plan()) {
            if (s.condition.equals(condition)) return DatasetSession.sizeTarget(s.prompt);
        }
        throw new AssertionError("no step " + condition);
    }

    private static final int WHITE = Color.WHITE.getRGB();
    private static final Color GREEN = new Color(80, 220, 80);
    private static final Color ORANGE = new Color(255, 160, 0);

    private static FrameAnalysis analysis(int faces, float[] box) {
        return new FrameAnalysis(null, faces, box, null, 0, 0, 0, null, 0.9f, null, 1);
    }

    /** The overlay on a white 640 x 480 picture, without a box: the label alone, judged against the F5 retake's target. */
    private static BufferedImage render(float area, boolean mirrored) {
        return render(area, mirrored, new float[] {0.20f, 0.35f});
    }

    private static BufferedImage render(float area, boolean mirrored, float[] target) {
        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 640, 480);
        DatasetSession.Readout.sizeOverlay(null, area, 640, mirrored, target).paint(g);
        g.dispose();
        return img;
    }

    /** What the mirrored preview shows: the picture flipped left to right. */
    private static BufferedImage flip(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                out.setRGB(img.getWidth() - 1 - x, y, img.getRGB(x, y));
            }
        }
        return out;
    }

    private static int count(BufferedImage img, Color c) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) == c.getRGB()) n++;
            }
        }
        return n;
    }
}
