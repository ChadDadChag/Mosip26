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

    public static EngineTuning defaults() {
        return new EngineTuning();
    }

    public EngineTuning copy() {
        EngineTuning t = new EngineTuning();
        t.startTimeoutMs = startTimeoutMs; t.deviceGraceMs = deviceGraceMs; t.frameStallMs = frameStallMs;
        t.invalidFrameLimit = invalidFrameLimit; t.minFrameIntervalMs = minFrameIntervalMs;
        t.engineErrorFrames = engineErrorFrames; t.activeMaxFps = activeMaxFps;
        t.maxPassivePoseDeg = maxPassivePoseDeg; t.multiFaceFailMs = multiFaceFailMs;
        t.hintPersistMs = hintPersistMs; t.hintMinIntervalMs = hintMinIntervalMs;
        t.padMonitorFrames = padMonitorFrames;
        t.bestFrameMaxPoseDeg = bestFrameMaxPoseDeg; t.bestFrameMinEar = bestFrameMinEar;
        return t;
    }
}
