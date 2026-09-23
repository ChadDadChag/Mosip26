package io.mosip.registration.liveness.api;

import java.util.List;

/** Final outcome of a session. The ONLY thing Desktop/Android flows use to accept or reject a face. */
public final class LivenessResult {
    public enum Decision { PASSED, FAILED, CANCELLED }
    public enum PassPath { PASSIVE_ONLY, ACTIVE_CHALLENGE, NONE }

    public final Decision decision;
    public final PassPath passPath;
    public final ErrorCode failureCode;          // null when PASSED
    /** Best frontal, sharp frame seen after liveness was established; hand this to the existing capture/auth flow. */
    public final Frame bestFrame;
    public final float passiveScore;             // median over the passive window
    public final List<ChallengeType> challengesIssued;
    public final int attempts;
    public final long durationMs;
    public final String sessionId;               // UUID, links UI, audit and metrics
    public final String modelVersion;

    public LivenessResult(Decision decision, PassPath passPath, ErrorCode failureCode, Frame bestFrame,
                          float passiveScore, List<ChallengeType> challengesIssued, int attempts, long durationMs,
                          String sessionId, String modelVersion) {
        this.decision = decision; this.passPath = passPath; this.failureCode = failureCode; this.bestFrame = bestFrame;
        this.passiveScore = passiveScore; this.challengesIssued = challengesIssued; this.attempts = attempts;
        this.durationMs = durationMs; this.sessionId = sessionId; this.modelVersion = modelVersion;
    }
    public boolean passed() { return decision == Decision.PASSED; }
}
