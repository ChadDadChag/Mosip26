package io.mosip.registration.liveness.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-workflow policy. Keys: mosip.registration.liveness.face.<resident|operator|supervisor>.<field>
 * Any key missing for a workflow falls back to mosip.registration.liveness.face.default.<field>, then to
 * the hard-coded defaults below. Owner: Person 3.
 */
public final class LivenessPolicy {

    public enum OnRepeatedFailure {
        BLOCK,     // show recovery guidance, return to previous screen (resident: may use biometric exception flow)
        COOLDOWN   // additionally refuse new sessions for this workflow for cooldownSec
    }

    public static final String PREFIX = "mosip.registration.liveness.face.";

    public boolean enabled = true;
    public float passiveThreshold = 0.85f;     // median score >= this -> pass without challenge
    public float padRejectThreshold = 0.30f;   // median score <  this -> PAD_FAILURE (no challenge offered)
    public int passiveWindowFrames = 10;       // usable frames aggregated for the passive decision
    public long passiveTimeoutMs = 8000;       // no usable window within this -> FACE_NOT_DETECTED / POOR_QUALITY
    public boolean activeEnabled = true;       // false -> scores between the thresholds fail instead of challenging
    public List<ChallengeType> challengePool =
            new ArrayList<ChallengeType>(Arrays.asList(ChallengeType.BLINK, ChallengeType.SMILE,
                    ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT));
    public int minChallenges = 2;
    public long challengeTimeoutMs = 6000;     // per challenge
    public int maxRetries = 3;                 // attempts = 1 + maxRetries
    public OnRepeatedFailure onRepeatedFailure = OnRepeatedFailure.BLOCK;
    public int cooldownSec = 60;
    public boolean diagnosticMode = false;     // extra detail in audit/diagnostic log only, never in UI
    public int analyzeFps = 8;                 // frames/s actually analysed; rest dropped

    public static LivenessPolicy load(ConfigProvider cfg, WorkflowType wf) {
        LivenessPolicy p = new LivenessPolicy();
        String w = wf.name().toLowerCase();
        p.enabled = bool(cfg, w, "enabled", p.enabled);
        p.passiveThreshold = flt(cfg, w, "passiveThreshold", p.passiveThreshold);
        p.padRejectThreshold = flt(cfg, w, "padRejectThreshold", p.padRejectThreshold);
        p.passiveWindowFrames = integer(cfg, w, "passiveWindowFrames", p.passiveWindowFrames);
        p.passiveTimeoutMs = integer(cfg, w, "passiveTimeoutMs", (int) p.passiveTimeoutMs);
        p.activeEnabled = bool(cfg, w, "activeEnabled", p.activeEnabled);
        String pool = str(cfg, w, "challengeTypes");
        if (pool != null && !pool.trim().isEmpty()) {
            p.challengePool = new ArrayList<ChallengeType>();
            for (String s : pool.split(",")) p.challengePool.add(ChallengeType.valueOf(s.trim().toUpperCase()));
        }
        p.minChallenges = integer(cfg, w, "minChallenges", p.minChallenges);
        p.challengeTimeoutMs = integer(cfg, w, "challengeTimeoutMs", (int) p.challengeTimeoutMs);
        p.maxRetries = integer(cfg, w, "maxRetries", p.maxRetries);
        String orf = str(cfg, w, "onRepeatedFailure");
        if (orf != null) p.onRepeatedFailure = OnRepeatedFailure.valueOf(orf.trim().toUpperCase());
        p.cooldownSec = integer(cfg, w, "cooldownSec", p.cooldownSec);
        p.diagnosticMode = bool(cfg, w, "diagnosticMode", p.diagnosticMode);
        p.analyzeFps = integer(cfg, w, "analyzeFps", p.analyzeFps);
        p.validate();
        return p;
    }

    public void validate() {
        if (!(padRejectThreshold >= 0 && padRejectThreshold < passiveThreshold && passiveThreshold <= 1))
            throw new IllegalArgumentException("Require 0 <= padRejectThreshold < passiveThreshold <= 1");
        if (minChallenges < 1 || minChallenges > challengePool.size())
            throw new IllegalArgumentException("minChallenges must be 1..challengePool size");
        if (maxRetries < 0 || challengeTimeoutMs < 1000 || analyzeFps < 1)
            throw new IllegalArgumentException("Invalid retry/timeout/fps value");
    }

    private static String str(ConfigProvider c, String wf, String f) {
        String v = c.get(PREFIX + wf + "." + f);
        return v != null ? v : c.get(PREFIX + "default." + f);
    }
    private static boolean bool(ConfigProvider c, String wf, String f, boolean d) {
        String v = str(c, wf, f); return v == null ? d : Boolean.parseBoolean(v.trim());
    }
    private static float flt(ConfigProvider c, String wf, String f, float d) {
        String v = str(c, wf, f); return v == null ? d : Float.parseFloat(v.trim());
    }
    private static int integer(ConfigProvider c, String wf, String f, int d) {
        String v = str(c, wf, f); return v == null ? d : Integer.parseInt(v.trim());
    }
}
