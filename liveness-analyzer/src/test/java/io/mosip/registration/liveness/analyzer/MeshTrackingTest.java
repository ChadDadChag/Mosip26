package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.mesh.FaceMeshLandmarker;
import io.mosip.registration.liveness.analyzer.mesh.MeshResult;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Landmark tracking (F7) vs {@code reference_pipeline.session_mesh} ({@code golden/tracking.json}). */
class MeshTrackingTest {

    private static OnnxFaceAnalyzer analyzer(AnalyzerConfig cfg) throws Exception {
        return new OnnxFaceAnalyzer(TestModels.shared(), cfg, "test-version");
    }

    private static Frame frame(String name) throws Exception {
        return GoldenFixtures.frame(GoldenFixtures.image(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped"})
    @SuppressWarnings("unchecked")
    void theSameFrameTwiceIsTrackedLikeTheReference(String name) throws Exception {
        Map<String, Object> golden = MiniJson.getObject(GoldenFixtures.json("tracking"), name);
        Frame f = frame(name);
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            a.analyze(f);
            assertThat(a.stats().framesTracked()).as("the first frame uses the YuNet ROI").isZero();
            FrameAnalysis r = a.analyze(f);
            assertThat(a.stats().framesTracked()).isEqualTo(1);
            assertThat(a.stats().trackingFallbacks()).isZero();
            Map<String, Object> want = MiniJson.getObject(golden, "landmarks");
            assertClose(r.landmarks.leftEye, GoldenFixtures.points(MiniJson.getArray(want, "leftEye")));
            assertClose(r.landmarks.rightEye, GoldenFixtures.points(MiniJson.getArray(want, "rightEye")));
            assertClose(new float[][] {r.landmarks.noseTip, r.landmarks.chin, r.landmarks.mouthLeft, r.landmarks.mouthRight},
                    new double[][] {MiniJson.toDoubles(MiniJson.getArray(want, "noseTip")),
                            MiniJson.toDoubles(MiniJson.getArray(want, "chin")),
                            MiniJson.toDoubles(MiniJson.getArray(want, "mouthLeft")),
                            MiniJson.toDoubles(MiniJson.getArray(want, "mouthRight"))});
            assertThat((double) r.yawDeg).isCloseTo(MiniJson.getDouble(golden, "yawDeg"), within(0.1));
            assertThat((double) r.pitchDeg).isCloseTo(MiniJson.getDouble(golden, "pitchDeg"), within(0.1));
            assertThat((double) r.rollDeg).isCloseTo(MiniJson.getDouble(golden, "rollDeg"), within(0.1));
        }
    }

    @Test
    void theTrackingRoiMatchesTheReference() throws Exception {
        // The ROI tracked from the single-frame golden mesh is tracking.json's second-frame ROI.
        for (String name : new String[] {"portrait_frontal", "portrait_frontal_flipped"}) {
            double[][] pts = GoldenFixtures.points(MiniJson.getArray(GoldenFixtures.json(name), "meshPoints"));
            float[][] points = new float[MeshResult.POINTS][2];
            for (int i = 0; i < points.length; i++) {
                points[i][0] = (float) pts[i][0];
                points[i][1] = (float) pts[i][1];
            }
            double[] roi = FaceMeshLandmarker.trackingRoi(points, 1.5);
            Map<String, Object> want = MiniJson.getObject(MiniJson.getObject(GoldenFixtures.json("tracking"), name), "roi");
            assertThat(roi[0]).isCloseTo(MiniJson.getDouble(want, "cx"), within(1e-3));
            assertThat(roi[1]).isCloseTo(MiniJson.getDouble(want, "cy"), within(1e-3));
            assertThat(roi[2]).isCloseTo(MiniJson.getDouble(want, "S"), within(1e-3));
            assertThat(roi[3]).isCloseTo(MiniJson.getDouble(want, "theta"), within(1e-5));
        }
    }

    @Test
    void aLostTrackFallsBackToTheYuNetRoiInTheSameFrame() throws Exception {
        Frame f = frame("portrait_frontal");
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            FrameAnalysis first = a.analyze(f);
            // A bad source: every point in a small cluster in the corner; the box still overlaps, so tracking runs.
            float[][] corner = new float[MeshResult.POINTS][2];
            for (float[] p : corner) {
                p[0] = 5;
                p[1] = 5;
            }
            corner[33] = new float[] {4, 5};
            corner[263] = new float[] {6, 5};
            a.trackFrom(corner, box(first.faceBox));
            FrameAnalysis r = a.analyze(f);
            assertThat(a.stats().framesTracked()).isEqualTo(1);
            assertThat(a.stats().trackingFallbacks()).isEqualTo(1);
            assertThat(r.landmarks).as("landmarks from the YuNet ROI").isNotNull();
            assertThat(r.landmarks.noseTip[0]).isCloseTo(first.landmarks.noseTip[0], within(0.01f));
        }
    }

    @Test
    void aFaceThatMovedFarIsNotTracked() throws Exception {
        GoldenFixtures.Image img = GoldenFixtures.image("portrait_frontal");
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            a.analyze(GoldenFixtures.frame(img));
            FrameAnalysis moved = a.analyze(GoldenFixtures.frame(shiftLeft(img, 220)));
            assertThat(moved.faceCount).isEqualTo(1);
            assertThat(moved.landmarks).isNotNull();
            assertThat(a.stats().framesTracked()).as("IoU < 0.3").isZero();
        }
    }

    @Test
    void trackingCanBeSwitchedOffAndAFaceLossDropsTheSource() throws Exception {
        Frame portrait = frame("portrait_frontal");
        try (OnnxFaceAnalyzer off = analyzer(AnalyzerConfig.builder().meshTracking(false).build())) {
            off.analyze(portrait);
            off.analyze(portrait);
            assertThat(off.stats().framesTracked()).isZero();
        }
        byte[] grey = new byte[320 * 240 * 3];
        Arrays.fill(grey, (byte) 128);
        try (OnnxFaceAnalyzer a = analyzer(AnalyzerConfig.defaults())) {
            a.analyze(portrait);
            a.analyze(new Frame(grey, 320, 240, 0L, 0L, "test"));
            a.analyze(portrait);
            assertThat(a.stats().framesTracked()).isZero();
            a.analyze(frame("crew_multi"));
            a.analyze(portrait);
            assertThat(a.stats().framesTracked()).isZero();
            a.analyze(portrait);
            assertThat(a.stats().framesTracked()).isEqualTo(1);
        }
    }

    private static FaceDetection box(float[] b) {
        return new FaceDetection(b[0], b[1], b[2], b[3], 1.0, new double[10]);
    }

    private static GoldenFixtures.Image shiftLeft(GoldenFixtures.Image img, int dx) {
        byte[] out = new byte[img.rgb.length];
        Arrays.fill(out, (byte) 128);
        for (int y = 0; y < img.height; y++) {
            System.arraycopy(img.rgb, (y * img.width + dx) * 3, out, y * img.width * 3, (img.width - dx) * 3);
        }
        return GoldenFixtures.raw(out, img.width, img.height);
    }

    private static void assertClose(float[][] got, double[][] want) {
        assertThat(got.length).isEqualTo(want.length);
        for (int i = 0; i < got.length; i++) {
            assertThat((double) got[i][0]).isCloseTo(want[i][0], within(0.1));
            assertThat((double) got[i][1]).isCloseTo(want[i][1], within(0.1));
        }
    }
}
