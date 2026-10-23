package io.mosip.registration.liveness.analyzer.quality;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class QualityAssessorTest {

    private static final int W = 640, H = 480;

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    void matchesTheReference(String name) throws Exception {
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(golden, "primary"));
        QualityReport q = new QualityAssessor(AnalyzerConfig.defaults()).assess(img.rgb, img.width, img.height, primary);
        Map<String, Object> want = MiniJson.getObject(golden, "quality");
        double worst = 0;
        worst = Math.max(worst, rel(q.lumaMean, MiniJson.getDouble(want, "lumaMean")));
        worst = Math.max(worst, rel(q.lapVar, MiniJson.getDouble(want, "lapVar")));
        worst = Math.max(worst, rel(q.areaFrac, MiniJson.getDouble(want, "areaFrac")));
        worst = Math.max(worst, rel(q.faceWidthPx, MiniJson.getDouble(want, "faceWidthPx")));
        worst = Math.max(worst, rel(q.centreDx, MiniJson.getDouble(want, "centreDx")));
        worst = Math.max(worst, rel(q.centreDy, MiniJson.getDouble(want, "centreDy")));
        assertThat(worst).isLessThan(1e-3);
        assertThat(q.cutOff).isEqualTo(MiniJson.getBoolean(want, "cutOff"));
        List<String> issues = new ArrayList<String>();
        for (QualityIssue i : q.issues()) issues.add(i.name());
        assertThat(issues).isEqualTo(MiniJson.getArray(want, "issues"));
        if (name.startsWith("portrait")) assertThat(q.issues()).isEmpty();
        System.out.printf("Quality parity %-26s max relative err %.2e  lapVar %.2f  issues %s%n", name, worst, q.lapVar, issues);
    }

    private static double rel(double got, double want) {
        return Math.abs(got - want) / Math.max(1e-9, Math.abs(want));
    }

    /** A W x H frame of uniform noise in [lo, hi]. */
    private static byte[] noise(int lo, int hi, long seed) {
        byte[] rgb = new byte[W * H * 3];
        Random r = new Random(seed);
        for (int i = 0; i < rgb.length; i++) rgb[i] = (byte) (lo + r.nextInt(hi - lo + 1));
        return rgb;
    }

    private static FaceDetection box(double x, double y, double w, double h) {
        return new FaceDetection(x, y, w, h, 0.9, new double[10]);
    }

    private static QualityReport assess(byte[] rgb, FaceDetection face) {
        return new QualityAssessor(AnalyzerConfig.defaults()).assess(rgb, W, H, face);
    }

    @Test
    void aCleanCentredTexturedFaceHasNoIssues() {
        assertThat(assess(noise(60, 200, 1), box(220, 120, 200, 240)).issues()).isEmpty();
    }

    @Test
    void eachSyntheticFrameRaisesExactlyOneIssue() {
        FaceDetection centred = box(220, 120, 200, 240);
        assertThat(assess(noise(5, 35, 2), centred).issues()).containsExactly(QualityIssue.TOO_DARK);
        assertThat(assess(noise(225, 255, 3), centred).issues()).containsExactly(QualityIssue.TOO_BRIGHT);
        byte[] flat = new byte[W * H * 3];
        java.util.Arrays.fill(flat, (byte) 128);
        assertThat(assess(flat, centred).issues()).containsExactly(QualityIssue.BLURRY);
        byte[] tex = noise(60, 200, 4);
        assertThat(assess(tex, box(290, 205, 60, 70)).issues()).containsExactly(QualityIssue.FACE_TOO_SMALL);
        assertThat(assess(tex, box(40, 30, 560, 420)).issues()).containsExactly(QualityIssue.FACE_TOO_LARGE);
        assertThat(assess(tex, box(430, 120, 200, 240)).issues()).containsExactly(QualityIssue.FACE_OFF_CENTER);
        assertThat(assess(tex, box(220, -10, 200, 480)).issues()).containsExactly(QualityIssue.FACE_CUT_OFF);
    }

    @Test
    void reportExposesMetricsAndCopies() {
        QualityReport q = assess(noise(60, 200, 5), box(220, 120, 200, 240));
        assertThat(q.areaFrac).isCloseTo(200.0 * 240 / (W * H), within(1e-12));
        assertThat(q.faceWidthPx).isEqualTo(200);
        assertThat(q.centreDx).isCloseTo(0, within(1e-12));
        assertThat(q.issuesCopy()).isEmpty();
        assertThatThrownBy(() -> q.issues().add(QualityIssue.BLURRY)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new QualityAssessor(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
