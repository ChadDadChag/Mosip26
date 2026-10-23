package io.mosip.registration.liveness.core;

/**
 * Engine thresholds that are NOT in {@code LivenessPolicy} (the contract). Every value is a guess until the
 * Phase 8 tuning report has run on real traces; all are marked TUNE. Mutable public fields so tests and the tuning
 * tools can sweep them; production uses {@link #defaults()}. Never share one instance between tuning runs.
 */
public final class EngineTuning {

    // ---- device / frame stream
    /** TUNE: no frame at all this long after start → DEVICE_UNAVAILABLE (recoverable). */
    public long startTimeoutMs = 5000;
    /** TUNE: a recoverable device error auto-recovers if frames resume within this. */
    public long deviceGraceMs = 3000;
    /** TUNE: device problems per session that still offer Retry; the next one ends the session. */
    public int maxDeviceErrors = 3;
    /** TUNE: frames stop arriving mid-attempt for this long → treated as DEVICE_DISCONNECTED (recoverable). */
    public long frameStallMs = 2000;
    /** TUNE: this many non-increasing/implausible frames in one attempt → INVALID_FRAME. */
    public int invalidFrameLimit = 3;
    /** TUNE: analysed frames closer than this are implausible (> ~200 fps) → counted as invalid. */
    public long minFrameIntervalMs = 5;
    /** TUNE: consecutive analyzer failures before the attempt fails with ENGINE_ERROR. */
    public int engineErrorFrames = 3;
    /** TUNE: analysis rate cap while a challenge runs (policy.analyzeFps covers passive; see proposal P5). */
    public int activeMaxFps = 15;

    // ---- passive
    /** TUNE: frames with |yaw| or |pitch| above this are not eligible for the passive window. */
    public float maxPassivePoseDeg = 25f;
    /** TUNE: head tilted sideways (|roll|) more than this in passive → LOOK_AT_CAMERA hint (hint only, see P7). */
    public float maxPassiveRollDeg = 20f;
    /** TUNE: more than one face for this long in passive → MULTIPLE_FACES. */
    public long multiFaceFailMs = 1500;

    // ---- guidance
    /** TUNE: a hint must persist this long before it is shown. */
    public long hintPersistMs = 300;
    /** TUNE: the shown hint changes at most this often. */
    public long hintMinIntervalMs = 500;

    // ---- continuous PAD during challenges
    /** TUNE: rolling median over this many recent scores during challenges/finalising. */
    public int padMonitorFrames = 3;

    // ---- best frame
    /** TUNE: best frame must be this frontal. */
    public float bestFrameMaxPoseDeg = 10f;
    /** TUNE: absolute EAR below this = eyes not open enough for the capture frame. */
    public float bestFrameMinEar = 0.18f;
    /** TUNE: Laplacian variance that counts as "half sharp" when ranking best-frame candidates. */
    public float bestFrameSharpnessHalfPoint = 100f;

    // ---- ISO/IEC 19794-5 frontal-image limits (best-frame ranking and audit only; never pass/fail)
    /** ISO/IEC 19794-5: |yaw| at most this for a compliant frontal image. */
    public float isoMaxYawDeg = 5f;
    /** ISO/IEC 19794-5: |pitch| at most this. */
    public float isoMaxPitchDeg = 5f;
    /** ISO/IEC 19794-5: |roll| (head tilted sideways) at most this. */
    public float isoMaxRollDeg = 8f;
    /** ISO/IEC 19794-5: at least this many pixels between the eye centres (resolution of the face). */
    public float isoMinEyeDistancePx = 90f;

    // ---- challenge flow
    /** TUNE: random delay before each prompt, 0..this (makes replayed video timing harder). */
    public int maxPromptJitterMs = 400;
    /** TUNE: neutral frames needed for a per-user baseline. */
    public int baselineFrames = 5;
    /** TUNE: baseline frames must be at least this frontal. */
    public float baselineMaxPoseDeg = 15f;
    /** TUNE: baseline frames must be this stable (max-min of yaw and of pitch). */
    public float baselineMaxSpreadDeg = 6f;
    /** TUNE: no stable baseline this long before a prompt → CHALLENGE_TIMEOUT. */
    public long baselineTimeoutMs = 4000;
    /** TUNE: motion within this long after the prompt cannot be a reaction to it and is not counted. */
    public long minReactionMs = 150;
    /** TUNE: a detector arms once the signal is within this fraction of its threshold of neutral. */
    public float armNeutralFraction = 0.3f;
    /** TUNE: re-send progress this often without change ("Please continue"). */
    public long progressHeartbeatMs = 1500;
    /** TUNE: face missing this long during a challenge → FACE_NOT_DETECTED. */
    public long faceLostMs = 1000;
    /** TUNE: this many consecutive multi-face frames during a challenge → MULTIPLE_FACES. */
    public int multiFaceChallengeFrames = 2;
    /** TUNE: final neutral hold for the best frame: at least this long... */
    public long holdMinMs = 400;
    /** TUNE: ...and at most this long (then the best frame seen so far is used). */
    public long holdMaxMs = 2000;

    // ---- face continuity (face swap / photo switch)
    /** TUNE: consecutive face boxes must overlap at least this much (IoU). */
    public float continuityMinIou = 0.3f;
    /** TUNE: centre may move at most this fraction of the face width between consecutive frames. */
    public float continuityMaxCentreJump = 0.5f;
    /** TUNE: face area may change at most by this factor between consecutive frames. */
    public float continuityMaxSizeRatio = 1.5f;
    /** TUNE: frames further apart than this get the relaxed overlap check below. */
    public long continuityGapMs = 250;
    /** TUNE: minimum IoU across a gap (face briefly lost or frames dropped). */
    public float continuityMinIouAfterGap = 0.1f;

    // ---- blink
    /** TUNE: eyes count as closed below this fraction of the baseline EAR. */
    public float blinkClosedRatio = 0.6f;
    /** TUNE: eyes count as reopened above this fraction of the baseline EAR. */
    public float blinkReopenRatio = 0.85f;
    /**
     * TUNE: closures longer than this are not blinks (eyes shut, closed-eye photo). Was 400 ms. Part 2 traces (single
     * subject) showed deliberate blinks on request take 400-600 ms to reopen to blinkReopenRatio; replay at 600 ms
     * detected 3/3 with the fewest extra firings.
     */
    public long blinkMaxClosedMs = 600;

    /**
     * TUNE: during BLINK/SMILE, frames whose |yaw| or |pitch| differ from baseline by more than this are not counted;
     * a closure or smile in progress is cancelled. Tilting a photo fakes EAR and mouth changes by perspective (a
     * phone photo passed BLINK and SMILE that way in Part 2's tests); a real blink or smile barely moves the head.
     * NaN disables.
     */
    public float actionMaxPoseDriftDeg = 12f;

    // ---- smile
    /** TUNE: mouth width / IOD must rise this much over baseline... */
    public float smileWidthIncrease = 0.18f;
    /** TUNE: ...or rise this much together with the corner lift below. */
    public float smileWidthIncreaseWithLift = 0.10f;
    /** TUNE: corner lift increase (in IOD units) that supports a smaller width increase. */
    public float smileLiftIncrease = 0.03f;

    // ---- head turn / look up-down
    /** TUNE: yaw change from baseline for a turn. */
    public float turnMinDeg = 20f;
    /** TUNE: yaw within this of baseline counts as returned. */
    public float turnReturnDeg = 10f;
    /** TUNE: pitch change from baseline for look up/down. */
    public float lookMinDeg = 13f;
    /** TUNE: pitch within this of baseline counts as returned. */
    public float lookReturnDeg = 8f;

    /**
     * TUNE: true restores the original turn/look rule (move, hold, then come back within turnReturnDeg /
     * lookReturnDeg). Default false: detected when the hold completes, then LOOK_AT_CAMERA asks the user to face the
     * camera again. Part 2 review: with the product prompt alone 0/9 turns passed; users held 52-63 deg and never
     * came back.
     */
    public boolean axisRequireReturn = false;

    // ---- shared hold rule (smile, turn, look)
    /** TUNE: an action must be held at least this long... */
    public long actionHoldMs = 200;
    /** TUNE: ...and over at least this many frames: a single-frame spike is not an action. */
    public int actionHoldFrames = 3;

    public static EngineTuning defaults() {
        return new EngineTuning();
    }
}
