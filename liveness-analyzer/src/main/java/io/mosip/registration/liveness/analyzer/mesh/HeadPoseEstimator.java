package io.mosip.registration.liveness.analyzer.mesh;

/**
 * Head pose from the 3-D mesh ({@code reference_pipeline.head_pose}), in double precision internally.
 * <pre>
 *   u_n = (X_n, -Y_n, -Z_n)                      x right, y up, z toward the camera
 *   ex  = normalize(u[263] - u[33])              from the subject's right outer eye corner to the left one
 *   t   = u[10] - u[152];  ey = normalize(t - (t.ex) ex)    from the chin to the forehead
 *   ez  = ex x ey
 *   yaw   = deg(atan2(ez.x, ez.z))                         + = subject turned to THEIR left
 *   pitch = deg(atan2(ez.y, sqrt(ez.x^2 + ez.z^2))) - bias  + = subject looks UP
 *   roll  = deg(atan2(ex.y, ex.x))                         + = subject's left eye higher
 * </pre>
 */
public final class HeadPoseEstimator {

    private HeadPoseEstimator() { }

    /** Writes {yaw, pitch, roll} in degrees to out3; all NaN when the points are missing or degenerate. */
    public static void yawPitchRoll(float[][] pts, float pitchBiasDeg, float[] out3) {
        out3[0] = out3[1] = out3[2] = Float.NaN;
        if (pts == null || pts.length <= MeshIndices.LEFT_EYE_OUTER) return;
        float[] l = pts[MeshIndices.LEFT_EYE_OUTER], r = pts[MeshIndices.RIGHT_EYE_OUTER];
        float[] top = pts[MeshIndices.FOREHEAD], bottom = pts[MeshIndices.CHIN];
        if (l == null || r == null || top == null || bottom == null) return;
        double exX = (double) l[0] - r[0], exY = -((double) l[1] - r[1]), exZ = -((double) l[2] - r[2]);
        double exN = Math.sqrt(exX * exX + exY * exY + exZ * exZ);
        if (!(exN > 0)) return;
        exX /= exN; exY /= exN; exZ /= exN;
        double tX = (double) top[0] - bottom[0], tY = -((double) top[1] - bottom[1]), tZ = -((double) top[2] - bottom[2]);
        double dot = tX * exX + tY * exY + tZ * exZ;
        double eyX = tX - dot * exX, eyY = tY - dot * exY, eyZ = tZ - dot * exZ;
        double eyN = Math.sqrt(eyX * eyX + eyY * eyY + eyZ * eyZ);
        if (!(eyN > 0)) return;
        eyX /= eyN; eyY /= eyN; eyZ /= eyN;
        double ezX = exY * eyZ - exZ * eyY;
        double ezY = exZ * eyX - exX * eyZ;
        double ezZ = exX * eyY - exY * eyX;
        double yaw = Math.toDegrees(Math.atan2(ezX, ezZ));
        double pitch = Math.toDegrees(Math.atan2(ezY, Math.sqrt(ezX * ezX + ezZ * ezZ)));
        double roll = Math.toDegrees(Math.atan2(exY, exX));
        if (Double.isNaN(yaw) || Double.isNaN(pitch) || Double.isNaN(roll)) return;
        out3[0] = (float) yaw;
        out3[1] = (float) (pitch - pitchBiasDeg);
        out3[2] = (float) roll;
    }
}
