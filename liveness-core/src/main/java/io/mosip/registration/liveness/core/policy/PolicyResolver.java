package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.WorkflowType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the effective policy for a workflow. For each field the lookup order is:
 * host workflow key → bundled workflow key → host default key → bundled default key → hard-coded default
 * (the contract's loader tries the workflow key before the default key, and each lookup goes through both
 * layers). This is exactly the result of merging liveness-default.properties into the host config, which is
 * what the file tells integrators to do; layering just makes a forgotten merge harmless.
 *
 * <p>Uses the contract's {@link LivenessPolicy#load} (never duplicates it) and adds the checks
 * {@link LivenessPolicy#validate()} does not make. An invalid host config never weakens security: the policy
 * falls back to the bundled defaults (liveness stays enabled) and the error is reported for audit.
 */
public final class PolicyResolver {

    /** A resolved policy plus the config error that forced a fallback, if any. */
    public static final class Resolved {
        public final LivenessPolicy policy;
        /** Null when the host config was valid. Safe to audit (contains key names/values, no biometrics). */
        public final String configError;

        Resolved(LivenessPolicy policy, String configError) {
            this.policy = policy; this.configError = configError;
        }
    }

    private PolicyResolver() { }

    public static Resolved resolve(ConfigProvider host, WorkflowType workflow) {
        ConfigProvider hostOrEmpty = host != null ? host : new LayeredConfigProvider();
        try {
            LivenessPolicy p = LivenessPolicy.load(new LayeredConfigProvider(hostOrEmpty, BundledDefaults.get()), workflow);
            validateExtra(p);
            return new Resolved(p, null);
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            try {
                LivenessPolicy fallback = LivenessPolicy.load(BundledDefaults.get(), workflow);
                validateExtra(fallback);
                return new Resolved(fallback, error);
            } catch (RuntimeException bundledBroken) {
                return new Resolved(new LivenessPolicy(), error + "; bundled defaults also invalid: "
                        + bundledBroken.getMessage());
            }
        }
    }

    /** Checks {@link LivenessPolicy#validate()} does not make. Throws IllegalArgumentException. */
    public static void validateExtra(LivenessPolicy p) {
        if (p.passiveWindowFrames < 1) throw new IllegalArgumentException("passiveWindowFrames must be >= 1");
        if (p.passiveTimeoutMs <= 0) throw new IllegalArgumentException("passiveTimeoutMs must be > 0");
        if (p.cooldownSec < 0) throw new IllegalArgumentException("cooldownSec must be >= 0");
        if (p.challengePool == null || p.challengePool.isEmpty())
            throw new IllegalArgumentException("challengeTypes must not be empty");
        Set<ChallengeType> distinct = EnumSet.noneOf(ChallengeType.class);
        for (ChallengeType c : p.challengePool) {
            if (c == null) throw new IllegalArgumentException("challengeTypes contains an empty entry");
            distinct.add(c);
        }
        if (p.minChallenges > distinct.size())
            throw new IllegalArgumentException("minChallenges (" + p.minChallenges
                    + ") exceeds the number of distinct challengeTypes (" + distinct.size() + ")");
    }

    /** Defensive copy: LivenessPolicy has public mutable fields and sessions must keep a stable snapshot. */
    public static LivenessPolicy copy(LivenessPolicy s) {
        LivenessPolicy p = new LivenessPolicy();
        p.enabled = s.enabled;
        p.passiveThreshold = s.passiveThreshold;
        p.padRejectThreshold = s.padRejectThreshold;
        p.passiveWindowFrames = s.passiveWindowFrames;
        p.passiveTimeoutMs = s.passiveTimeoutMs;
        p.activeEnabled = s.activeEnabled;
        p.challengePool = new ArrayList<ChallengeType>(s.challengePool);
        p.minChallenges = s.minChallenges;
        p.challengeTimeoutMs = s.challengeTimeoutMs;
        p.maxRetries = s.maxRetries;
        p.onRepeatedFailure = s.onRepeatedFailure;
        p.cooldownSec = s.cooldownSec;
        p.diagnosticMode = s.diagnosticMode;
        p.analyzeFps = s.analyzeFps;
        return p;
    }
}
