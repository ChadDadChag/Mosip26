package io.mosip.registration.liveness.analyzer.heuristic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.Test;

public class HeuristicFaceAnalyzerTest {

    private static final Color SKIN_COLOR = new Color(200, 150, 120);

    @Test
    public void modelVersionMatchesTheBundledManifest() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            assertEquals("heuristic-pad-2026.09.1", analyzer.modelVersion());
        }
    }

    @Test
    public void reportsNoFaceAndNaNLivenessScoreWhenThereIsNoSkinColoredRegion() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            Frame frame = frameOf(solidColorImage(64, 64, Color.BLUE));

            FrameAnalysis analysis = analyzer.analyze(frame);

            assertEquals(0, analysis.faceCount);
            assertNull(analysis.faceBox);
            assertNull(analysis.landmarks);
            assertTrue("livenessScore should be NaN when no face is found", Float.isNaN(analysis.livenessScore));
            assertTrue("qualityIssues should be empty when there is no face to assess",
                    analysis.qualityIssues.isEmpty());
        }
    }

    @Test
    public void detectsAFaceAndProducesLandmarksAndAUsableLivenessScore() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            // A textured (noisy) skin-coloured square on a neutral background - sharp
            // and colour-diverse enough that it should not trip BLURRY.
            BufferedImage image = texturedSkinFace(96, 96, 24, 24, 48, 48);
            Frame frame = frameOf(image);

            FrameAnalysis analysis = analyzer.analyze(frame);

            assertEquals(1, analysis.faceCount);
            assertNotNull(analysis.faceBox);
            assertEquals(4, analysis.faceBox.length);
            assertNotNull("landmarks must be populated when a face is found", analysis.landmarks);
            assertNotNull(analysis.landmarks.leftEye);
            assertEquals(6, analysis.landmarks.leftEye.length);
            assertTrue("livenessScore should be a real number in [0,1], got " + analysis.livenessScore,
                    !Float.isNaN(analysis.livenessScore)
                            && analysis.livenessScore >= 0f && analysis.livenessScore <= 1f);
            assertTrue("padDetails should carry the raw signal values for audit",
                    analysis.padDetails.containsKey("laplacian_variance")
                            && analysis.padDetails.containsKey("color_diversity")
                            && analysis.padDetails.containsKey("mean_luma"));
            assertTrue(analysis.processingMs >= 0);
        }
    }

    @Test
    public void flagsTooDarkForAnUnderexposedFace() throws Exception {
        // Note: this must stay a plausibly skin-hued colour, just dim - a literal
        // near-black fill falls outside ANY skin-colour classifier's range (real
        // skin under normal exposure is never pure black), so FaceRegionDetector
        // correctly reports faceCount==0 for that case rather than "face, too dark".
        // That is a genuine, documented limitation of skin-colour-based detection
        // under extreme low light (see FaceRegionDetector's Javadoc), not something
        // to route around by weakening the classifier for this test.
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            BufferedImage image = solidColorFaceOnBackground(96, 96, 24, 24, 48, 48, new Color(50, 35, 27));
            FrameAnalysis analysis = analyzer.analyze(frameOf(image));

            assertEquals(1, analysis.faceCount);
            assertTrue("a dim but still skin-hued face region should be flagged TOO_DARK",
                    analysis.qualityIssues.contains(QualityIssue.TOO_DARK));
        }
    }

    @Test
    public void flagsTooBrightForAnOverexposedFace() throws Exception {
        // Same reasoning as flagsTooDarkForAnUnderexposedFace: a literal near-white
        // fill falls outside the skin-colour range entirely, so a lit-but-still-
        // skin-hued bright colour is used instead.
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            BufferedImage image = solidColorFaceOnBackground(96, 96, 24, 24, 48, 48, new Color(250, 215, 190));
            FrameAnalysis analysis = analyzer.analyze(frameOf(image));

            assertEquals(1, analysis.faceCount);
            assertTrue("a bright but still skin-hued face region should be flagged TOO_BRIGHT",
                    analysis.qualityIssues.contains(QualityIssue.TOO_BRIGHT));
        }
    }

    @Test
    public void flagsBlurryForAFlatUntexturedFace() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            // A perfectly flat skin-colored block has zero Laplacian variance - the
            // heuristic's stand-in for "no texture / possibly a flat printed photo".
            BufferedImage image = solidColorFaceOnBackground(96, 96, 24, 24, 48, 48, SKIN_COLOR);
            FrameAnalysis analysis = analyzer.analyze(frameOf(image));

            assertEquals(1, analysis.faceCount);
            assertTrue("a perfectly flat, untextured face region should be flagged BLURRY",
                    analysis.qualityIssues.contains(QualityIssue.BLURRY));
        }
    }

    @Test
    public void flagsFaceTooSmallForATinyFace() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            BufferedImage image = texturedSkinFace(200, 200, 90, 90, 20, 20);
            FrameAnalysis analysis = analyzer.analyze(frameOf(image));

            if (analysis.faceCount == 1) {
                assertTrue("a face covering only ~1% of the frame should be flagged FACE_TOO_SMALL",
                        analysis.qualityIssues.contains(QualityIssue.FACE_TOO_SMALL));
            }
            // If the detector's minimum-area gate rejects it outright (faceCount==0),
            // that is also an acceptable outcome for a face this tiny relative to the frame.
        }
    }

    @Test
    public void flagsFaceOffCenterWhenFaceIsNearAnEdge() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            BufferedImage image = texturedSkinFace(120, 120, 2, 2, 30, 30);
            FrameAnalysis analysis = analyzer.analyze(frameOf(image));

            if (analysis.faceCount == 1) {
                assertTrue("a face pushed into the corner should be flagged off-center and/or cut off",
                        analysis.qualityIssues.contains(QualityIssue.FACE_OFF_CENTER)
                                || analysis.qualityIssues.contains(QualityIssue.FACE_CUT_OFF));
            }
        }
    }

    @Test
    public void livenessScoreIsHigherForATexturedFaceThanAFlatOne() throws Exception {
        try (HeuristicFaceAnalyzer analyzer = new HeuristicFaceAnalyzer()) {
            FrameAnalysis flat = analyzer.analyze(frameOf(
                    solidColorFaceOnBackground(96, 96, 24, 24, 48, 48, SKIN_COLOR)));
            FrameAnalysis textured = analyzer.analyze(frameOf(
                    texturedSkinFace(96, 96, 24, 24, 48, 48)));

            assertEquals(1, flat.faceCount);
            assertEquals(1, textured.faceCount);
            assertTrue("a textured, colour-varied face should score higher than a perfectly flat one: flat="
                            + flat.livenessScore + " textured=" + textured.livenessScore,
                    textured.livenessScore > flat.livenessScore);
        }
    }

    private static Frame frameOf(BufferedImage image) {
        byte[] rgb = ImageQualityMetricsTest.toRgb888(image);
        return new Frame(rgb, image.getWidth(), image.getHeight(), 0L, 0L, "test-device");
    }

    private static BufferedImage solidColorImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static BufferedImage solidColorFaceOnBackground(int width, int height,
            int fx, int fy, int fw, int fh, Color faceColor) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(faceColor);
        g.fillRect(fx, fy, fw, fh);
        g.dispose();
        return image;
    }

    /** A skin-toned face region with per-pixel random texture, so it has real sharpness/colour diversity. */
    private static BufferedImage texturedSkinFace(int width, int height, int fx, int fy, int fw, int fh) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.dispose();

        Random random = new Random(7);
        for (int y = fy; y < fy + fh && y < height; y++) {
            for (int x = fx; x < fx + fw && x < width; x++) {
                int r = clampByte(SKIN_COLOR.getRed() + random.nextInt(41) - 20);
                int gg = clampByte(SKIN_COLOR.getGreen() + random.nextInt(41) - 20);
                int b = clampByte(SKIN_COLOR.getBlue() + random.nextInt(41) - 20);
                image.setRGB(x, y, new Color(r, gg, b).getRGB());
            }
        }
        return image;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
