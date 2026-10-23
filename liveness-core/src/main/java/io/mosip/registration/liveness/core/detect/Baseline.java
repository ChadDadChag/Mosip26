package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.passive.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * A user's neutral face, as medians over a few stable frames taken just before the prompt. Absolute thresholds
 * break across faces, glasses and cameras; everything is measured relative to this. Landmark-derived values are
 * NaN when the analyzer gave no landmarks.
 */
public final class Baseline {
    public final float ear;
    public final float mouthWidth;
    public final float cornerLift;
    public final float yaw;
    public final float pitch;
    public final int frames;

    public Baseline(float ear, float mouthWidth, float cornerLift, float yaw, float pitch, int frames) {
        this.ear = ear; this.mouthWidth = mouthWidth; this.cornerLift = cornerLift;
        this.yaw = yaw; this.pitch = pitch; this.frames = frames;
    }

    public static Baseline of(List<FrameAnalysis> frames) {
        List<Float> ear = new ArrayList<Float>(), mouth = new ArrayList<Float>(), lift = new ArrayList<Float>();
        List<Float> yaw = new ArrayList<Float>(), pitch = new ArrayList<Float>();
        for (FrameAnalysis a : frames) {
            ear.add(FaceGeometry.meanEar(a.landmarks));
            mouth.add(FaceGeometry.mouthWidthRatio(a.landmarks));
            lift.add(FaceGeometry.cornerLift(a.landmarks));
            yaw.add(FaceGeometry.userYaw(a));
            pitch.add(FaceGeometry.userPitch(a));
        }
        return new Baseline(Stats.median(ear), Stats.median(mouth), Stats.median(lift), Stats.median(yaw),
                Stats.median(pitch), frames.size());
    }
}
