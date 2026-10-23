package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.FaceLandmarks;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;

/**
 * Rebuilds a {@link FrameAnalysis} from trace features: a generic face whose landmark geometry has exactly the
 * recorded EAR, mouth-width ratio and corner lift, at the recorded (normalised) box. Detectors only use those
 * ratios, so replay is faithful without storing anyone's real landmarks.
 */
public final class SyntheticFace {
    static final int W = 640, H = 480;
    private static final byte[] PIXELS = new byte[W * H * 3];

    private SyntheticFace() { }

    public static FrameAnalysis analysis(TraceFrame f, long timeBaseMs) {
        Frame frame = new Frame(PIXELS, W, H, timeBaseMs + f.t, f.seq, "trace");
        if (f.faces == 0 || f.box == null) {
            return new FrameAnalysis(frame, f.faces, null, null, 0, 0, 0, f.issues, Float.NaN, null, f.processingMs);
        }
        float[] box = {f.box[0] * W, f.box[1] * H, f.box[2] * W, f.box[3] * H};
        return new FrameAnalysis(frame, f.faces, box, landmarks(box, f), f.yaw, f.pitch, f.roll, f.issues, f.score,
                null, f.processingMs);
    }

    private static FaceLandmarks landmarks(float[] box, TraceFrame f) {
        if (Float.isNaN(f.ear) && Float.isNaN(f.mouthWidth)) return null;
        float cx = box[0] + box[2] / 2, cy = box[1] + box[3] / 2;
        float iod = box[2] * 0.45f;
        float eyeY = cy - box[3] * 0.14f;
        float ear = Float.isNaN(f.ear) ? 0.3f : f.ear;
        float[][] left = eye(cx + iod / 2, eyeY, iod * 0.4f, ear);
        float[][] right = eye(cx - iod / 2, eyeY, iod * 0.4f, ear);
        float mouthY = cy + box[3] * 0.21f;
        float half = (Float.isNaN(f.mouthWidth) ? 0.9f : f.mouthWidth) * iod / 2;
        float lift = (Float.isNaN(f.cornerLift) ? 0f : f.cornerLift) * iod;
        return new FaceLandmarks(left, right, new float[] {cx - half, mouthY - lift}, new float[] {cx + half, mouthY - lift},
                new float[] {cx, mouthY - 2}, new float[] {cx, mouthY + 2}, new float[] {cx, cy},
                new float[] {cx, box[1] + box[3]}, null, null);
    }

    private static float[][] eye(float ex, float ey, float w, float ear) {
        float h = ear * w;
        return new float[][] {{ex - w / 2, ey}, {ex - w / 6, ey - h / 2}, {ex + w / 6, ey - h / 2}, {ex + w / 2, ey},
                {ex + w / 6, ey + h / 2}, {ex - w / 6, ey + h / 2}};
    }
}
