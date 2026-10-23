package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;

/**
 * Verifies one facial action against a per-user {@link Baseline}. Pure and time-based (frame rates vary).
 *
 * <p>Rules shared by every detector:
 * <ol>
 *   <li>Thresholds are relative to the user's own neutral baseline, never absolute.</li>
 *   <li>Only movement after the prompt plus the minimum human reaction time counts, and only once the signal has
 *       been seen near neutral after that point ("armed"): motion already under way at the prompt never counts.</li>
 *   <li>The action must be held/completed over several frames; a single-frame spike is not an action.</li>
 *   <li>Turns and looks must return towards neutral before they count.</li>
 *   <li>A clearly opposite movement returns {@link DetectorStatus#WRONG_ACTION}.</li>
 * </ol>
 * Frames without the data a detector needs (e.g. no landmarks) are ignored, which ends in a timeout.
 */
public interface ActionDetector {

    ChallengeType type();

    /** Starts a new challenge. {@code promptAtMs} is when the prompt was shown. */
    void reset(Baseline baseline, long promptAtMs);

    DetectorStatus update(FrameAnalysis a, long nowMs);

    /** 0..1 for the UI progress indicator. */
    float progress();
}
