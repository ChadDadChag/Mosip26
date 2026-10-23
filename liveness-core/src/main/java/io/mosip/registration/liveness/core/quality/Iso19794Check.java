package io.mosip.registration.liveness.core.quality;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.FaceGeometry;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * How one analysed frame measures against the ISO/IEC 19794-5 frontal-image requirements that can be judged from
 * a single frame: head pose (yaw, pitch), roll, resolution (pixels between the eye centres), open eyes, neutral
 * expression and the analyzer's own quality checks (lighting, sharpness, centring, size). Only scalars and enums,
 * so it can go to audit.
 *
 * <p>COMPLIANT needs every limit measured and met. A broken limit makes the frame NOT_COMPLIANT; with nothing
 * broken but something not measurable (the analyzer gave no pose, roll or landmarks) it is UNKNOWN. This never
 * decides pass/fail: it ranks best-frame candidates and is recorded with the result.
 */
public final class Iso19794Check {

    public enum Status { COMPLIANT, NOT_COMPLIANT, UNKNOWN }

    public enum Issue { QUALITY, POSE, ROLL, EYE_DISTANCE, EYES_CLOSED, EXPRESSION }

    public final Status status;
    public final Set<Issue> issues;
    /** As reported or measured; NaN = not available. */
    public final float yawDeg, pitchDeg, rollDeg, eyeDistancePx;

    private Iso19794Check(Status status, Set<Issue> issues, float yawDeg, float pitchDeg, float rollDeg,
                          float eyeDistancePx) {
        this.status = status;
        this.issues = issues;
        this.yawDeg = yawDeg;
        this.pitchDeg = pitchDeg;
        this.rollDeg = rollDeg;
        this.eyeDistancePx = eyeDistancePx;
    }

    /**
     * @param neutralExpression false when the frame is known not to show the user's neutral expression (a fallback
     *                          frame that failed the hold's neutral check); offered frames are neutral by construction
     */
    public static Iso19794Check of(FrameAnalysis a, EngineTuning t, boolean neutralExpression) {
        Set<Issue> issues = EnumSet.noneOf(Issue.class);
        boolean unknown = false;
        if (!a.usable() || a.faceBox == null) issues.add(Issue.QUALITY);

        if (!FaceGeometry.hasPose(a)) unknown = true;
        if (!Float.isNaN(a.yawDeg) && Math.abs(a.yawDeg) > t.isoMaxYawDeg
                || !Float.isNaN(a.pitchDeg) && Math.abs(a.pitchDeg) > t.isoMaxPitchDeg) issues.add(Issue.POSE);

        if (Float.isNaN(a.rollDeg)) unknown = true;
        else if (Math.abs(a.rollDeg) > t.isoMaxRollDeg) issues.add(Issue.ROLL);

        float eyeDistance = FaceGeometry.interOcular(a.landmarks);
        if (Float.isNaN(eyeDistance)) unknown = true;
        else if (eyeDistance < t.isoMinEyeDistancePx) issues.add(Issue.EYE_DISTANCE);

        float ear = FaceGeometry.meanEar(a.landmarks);
        if (Float.isNaN(ear)) unknown = true;
        else if (ear < t.bestFrameMinEar) issues.add(Issue.EYES_CLOSED);

        if (!neutralExpression) issues.add(Issue.EXPRESSION);
        else if (Float.isNaN(FaceGeometry.mouthWidthRatio(a.landmarks))) unknown = true;

        Status status = !issues.isEmpty() ? Status.NOT_COMPLIANT : unknown ? Status.UNKNOWN : Status.COMPLIANT;
        return new Iso19794Check(status, Collections.unmodifiableSet(issues), a.yawDeg, a.pitchDeg, a.rollDeg,
                eyeDistance);
    }

    /** Ranking tier: compliant frames first, then frames not known to break a limit, then the rest. */
    public int tier() {
        return status == Status.COMPLIANT ? 2 : status == Status.UNKNOWN ? 1 : 0;
    }
}
