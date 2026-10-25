package io.mosip.registration.liveness.analyzer.mesh;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HeadPoseEstimatorTest {

    /**
     * MediaPipe canonical face model, mediapipe/modules/face_geometry/data/canonical_face_model.obj at commit
     * a908d668c730da128dfa8d9f6bd25d519d006692 (vertex n is on line n + 1). Axes: x = subject's left, y = up,
     * z = toward the viewer.
     */
    private static final double[] NOSE_1 = {0.000000, -1.126865, 7.475604};       // line 2
    private static final double[] FOREHEAD_10 = {0.000000, 8.261778, 4.481535};   // line 11
    private static final double[] R_EYE_33 = {-4.445859, 2.663991, 3.173422};     // line 34
    private static final double[] CHIN_152 = {0.000000, -9.403378, 4.264492};     // line 153
    private static final double[] L_EYE_263 = {4.445859, 2.663991, 3.173422};     // line 264

    private enum Axis { YAW, PITCH, ROLL }

    /** Canonical points rotated by one angle, mapped to image coordinates X = 10x + 300, Y = -10y + 200, Z = -10z + 50. */
    private static float[][] mesh(Axis axis, double deg) {
        float[][] pts = new float[MeshResult.POINTS][3];
        put(pts, MeshIndices.NOSE_TIP, NOSE_1, axis, deg);
        put(pts, MeshIndices.FOREHEAD, FOREHEAD_10, axis, deg);
        put(pts, MeshIndices.RIGHT_EYE_OUTER, R_EYE_33, axis, deg);
        put(pts, MeshIndices.CHIN, CHIN_152, axis, deg);
        put(pts, MeshIndices.LEFT_EYE_OUTER, L_EYE_263, axis, deg);
        return pts;
    }

    private static void put(float[][] pts, int i, double[] v, Axis axis, double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        double x = v[0], y = v[1], z = v[2];
        switch (axis) {
            case YAW: { double x2 = x * c + z * s; z = -x * s + z * c; x = x2; break; }   // nose (+z) toward +x
            case PITCH: { double y2 = y * c + z * s; z = -y * s + z * c; y = y2; break; } // nose toward +y (up)
            default: { double x2 = x * c - y * s; y = x * s + y * c; x = x2; break; }    // left eye (+x) up
        }
        pts[i][0] = (float) (10 * x + 300);
        pts[i][1] = (float) (-10 * y + 200);
        pts[i][2] = (float) (-10 * z + 50);
    }

    private static float[] pose(float[][] pts) {
        float[] out = new float[3];
        HeadPoseEstimator.yawPitchRoll(pts, 0f, out);
        return out;
    }

    @Test
    void recoversSingleAxisRotationsOfTheCanonicalFace() {
        float[] base = pose(mesh(Axis.YAW, 0));
        assertThat((double) base[0]).isCloseTo(0, within(1e-4));
        assertThat((double) base[2]).isCloseTo(0, within(1e-4));
        double p0 = base[1]; // the canonical face's own forehead/chin tilt, about -0.7 deg
        assertThat(p0).isCloseTo(-0.7, within(0.1));
        for (double a : new double[] {-40, -25, -10, 10, 25, 40}) {
            float[] y = pose(mesh(Axis.YAW, a));
            assertThat((double) y[0]).as("yaw %s", a).isCloseTo(a, within(0.5));
            assertThat((double) y[1]).isCloseTo(p0, within(0.5));
            assertThat((double) y[2]).isCloseTo(0, within(0.5));

            float[] p = pose(mesh(Axis.PITCH, a));
            assertThat((double) p[1]).as("pitch %s", a).isCloseTo(p0 + a, within(0.5));
            assertThat((double) p[0]).isCloseTo(0, within(0.5));
            assertThat((double) p[2]).isCloseTo(0, within(0.5));

            // Rolling tilts the canonical normal (elevation p0) sideways, so yaw and pitch move a little:
            double g = Math.toRadians(a), sp = Math.sin(Math.toRadians(p0)), cp = Math.cos(Math.toRadians(p0));
            double ezx = -Math.sin(g) * sp, ezy = Math.cos(g) * sp;
            float[] r = pose(mesh(Axis.ROLL, a));
            assertThat((double) r[2]).as("roll %s", a).isCloseTo(a, within(0.5));
            assertThat((double) r[0]).isCloseTo(Math.toDegrees(Math.atan2(ezx, cp)), within(0.5));
            assertThat((double) r[1]).isCloseTo(Math.toDegrees(Math.atan2(ezy, Math.hypot(ezx, cp))), within(0.5));
        }
    }

    @Test
    void signsFollowTheSubjectsFrame() {
        float[][] straight = mesh(Axis.YAW, 0), left = mesh(Axis.YAW, 25), up = mesh(Axis.PITCH, 25);
        // Turning to the subject's own left moves the nose toward image +x, and yaw is positive.
        assertThat(left[MeshIndices.NOSE_TIP][0]).isGreaterThan(straight[MeshIndices.NOSE_TIP][0]);
        assertThat(pose(left)[0]).isPositive();
        // Looking up moves the nose up in the image (smaller Y), and pitch is positive.
        assertThat(up[MeshIndices.NOSE_TIP][1]).isLessThan(straight[MeshIndices.NOSE_TIP][1]);
        assertThat(pose(up)[1]).isPositive();
    }

    @Test
    void degenerateInputGivesNaN() {
        float[] out = {1, 2, 3};
        HeadPoseEstimator.yawPitchRoll(null, 0f, out);
        assertThat(out).containsOnly(Float.NaN);
        HeadPoseEstimator.yawPitchRoll(new float[10][3], 0f, out);
        assertThat(out).containsOnly(Float.NaN);
        HeadPoseEstimator.yawPitchRoll(new float[MeshResult.POINTS][3], 0f, out); // all points coincide
        assertThat(out).containsOnly(Float.NaN);
        float[][] flat = mesh(Axis.YAW, 0);
        flat[MeshIndices.FOREHEAD] = flat[MeshIndices.CHIN].clone(); // no vertical axis
        HeadPoseEstimator.yawPitchRoll(flat, 0f, out);
        assertThat(out).containsOnly(Float.NaN);
        flat[MeshIndices.FOREHEAD] = null;
        HeadPoseEstimator.yawPitchRoll(flat, 0f, out);
        assertThat(out).containsOnly(Float.NaN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"})
    void matchesTheGoldenPose(String name) throws Exception {
        OnnxModels models = TestModels.shared();
        GoldenFixtures.Image img = GoldenFixtures.image(name);
        Map<String, Object> golden = GoldenFixtures.json(name);
        FaceDetection primary = GoldenFixtures.detection(MiniJson.getObject(golden, "primary"));
        AnalyzerConfig cfg = AnalyzerConfig.defaults();
        MeshResult r = new FaceMeshLandmarker(models.env(), models.session(ModelRole.LANDMARKS), cfg)
                .estimate(img.rgb, img.width, img.height, primary);
        float[] ypr = new float[3], raw = new float[3];
        HeadPoseEstimator.yawPitchRoll(r.points, (float) cfg.pitchBiasDeg, ypr);
        HeadPoseEstimator.yawPitchRoll(r.points, 0f, raw);
        double dYaw = Math.abs(ypr[0] - MiniJson.getDouble(golden, "yawDeg"));
        double dPitch = Math.abs(ypr[1] - MiniJson.getDouble(golden, "pitchDeg"));
        double dRoll = Math.abs(ypr[2] - MiniJson.getDouble(golden, "rollDeg"));
        assertThat(dYaw).isLessThan(0.05);
        assertThat(dPitch).isLessThan(0.05);
        assertThat(dRoll).isLessThan(0.05);
        assertThat((double) raw[1]).isCloseTo(MiniJson.getDouble(golden, "pitchRaw"), within(0.05));
        assertThat((double) (raw[1] - (float) cfg.pitchBiasDeg)).isCloseTo(ypr[1], within(1e-4));
        System.out.printf("Pose parity %-26s |dyaw| %.2e |dpitch| %.2e |droll| %.2e deg%n", name, dYaw, dPitch, dRoll);
    }
}
