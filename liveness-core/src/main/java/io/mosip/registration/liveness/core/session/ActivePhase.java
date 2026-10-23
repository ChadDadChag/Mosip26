package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;

import java.util.List;
import java.util.Set;

/**
 * The active-challenge part of one attempt: pre-prompt hold, prompts, action detection, continuous PAD, face
 * count/continuity checks, timeouts and the final neutral hold for the best frame. Pure logic, time passed in,
 * owned by the session worker. Implemented by {@code challenge.ActiveChallengePhase}.
 */
public interface ActivePhase {

    /** Challenge types this phase can verify (intersected with the policy pool by the selector). */
    Set<ChallengeType> supported();

    /**
     * Starts a sequence.
     * @param seedFrames recent eligible passive-window frames (oldest first): a head start for the first baseline
     */
    void start(List<ChallengeType> sequence, List<FrameAnalysis> seedFrames, long nowMs);

    void onFrame(FrameAnalysis a, long nowMs, List<ActiveStep> out);

    void onTick(long nowMs, List<ActiveStep> out);
}
