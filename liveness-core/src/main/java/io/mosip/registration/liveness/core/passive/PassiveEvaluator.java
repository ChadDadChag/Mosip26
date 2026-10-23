package io.mosip.registration.liveness.core.passive;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passive decision over a window of eligible frames (pure logic, time passed in).
 *
 * <ul>
 *   <li>Eligible: {@link FrameAnalysis#usable()} as the contract defines it (exactly one face and no quality issue
 *       at all), a liveness score, and pose within {@link EngineTuning#maxPassivePoseDeg}.</li>
 *   <li>Window: the last {@code policy.passiveWindowFrames} eligible frames. The window restarts when the face
 *       disappears or changes count (a different face may have been presented).</li>
 *   <li>Decision when the window is full, on the MEDIAN score (contract): below {@code padRejectThreshold} →
 *       ATTACK (never falls back to active); at or above {@code passiveThreshold} → PASS; otherwise NEED_ACTIVE,
 *       or BELOW_THRESHOLD when {@code activeEnabled} is false.</li>
 *   <li>No full window within {@code passiveTimeoutMs} → TIMEOUT with the dominant reason
 *       (FACE_NOT_DETECTED, MULTIPLE_FACES or POOR_QUALITY).</li>
 * </ul>
 */
public final class PassiveEvaluator {

    public enum Verdict { PENDING, PASS, ATTACK, NEED_ACTIVE, BELOW_THRESHOLD, TIMEOUT }

    private final LivenessPolicy policy;
    private final EngineTuning tuning;

    private final Deque<FrameAnalysis> window = new ArrayDeque<FrameAnalysis>();
    private long startedMs;
    private int noFace, multiFace, poorQuality;
    private float lastMedian = Float.NaN;

    public PassiveEvaluator(LivenessPolicy policy, EngineTuning tuning) {
        this.policy = policy;
        this.tuning = tuning;
    }

    public void reset(long nowMs) {
        window.clear();
        startedMs = nowMs;
        noFace = multiFace = poorQuality = 0;
        lastMedian = Float.NaN;
    }

    public Verdict offer(FrameAnalysis a, long nowMs) {
        if (a.faceCount == 0) {
            noFace++;
            window.clear();
        } else if (a.faceCount > 1) {
            multiFace++;
            window.clear();
        } else if (!isEligible(a)) {
            poorQuality++;
        } else {
            window.addLast(a);
            while (window.size() > policy.passiveWindowFrames) window.removeFirst();
            if (window.size() == policy.passiveWindowFrames) return decide();
        }
        return onTick(nowMs);
    }

    public Verdict onTick(long nowMs) {
        return nowMs - startedMs >= policy.passiveTimeoutMs ? Verdict.TIMEOUT : Verdict.PENDING;
    }

    boolean isEligible(FrameAnalysis a) {
        if (!a.usable() || Float.isNaN(a.livenessScore)) return false;
        return Math.abs(a.yawDeg) <= tuning.maxPassivePoseDeg && Math.abs(a.pitchDeg) <= tuning.maxPassivePoseDeg;
    }

    private Verdict decide() {
        List<Float> scores = new ArrayList<Float>();
        for (FrameAnalysis f : window) scores.add(f.livenessScore);
        lastMedian = Stats.median(scores);
        if (lastMedian < policy.padRejectThreshold) return Verdict.ATTACK;
        if (lastMedian >= policy.passiveThreshold) return Verdict.PASS;
        return policy.activeEnabled ? Verdict.NEED_ACTIVE : Verdict.BELOW_THRESHOLD;
    }

    /** Failure code for a TIMEOUT verdict: the most frequent reason frames were rejected. */
    public ErrorCode timeoutCode() {
        if (noFace == 0 && multiFace == 0 && poorQuality == 0) return ErrorCode.FACE_NOT_DETECTED;
        if (noFace >= multiFace && noFace >= poorQuality) return ErrorCode.FACE_NOT_DETECTED;
        if (multiFace >= poorQuality) return ErrorCode.MULTIPLE_FACES;
        return ErrorCode.POOR_QUALITY;
    }

    /** Median of the last full window (NaN until one was decided). */
    public float median() {
        return lastMedian;
    }

    public int windowSize() {
        return window.size();
    }

    public long elapsedMs(long nowMs) {
        return nowMs - startedMs;
    }

    /** Current window, oldest first (for best-frame selection and challenge baselines). */
    public List<FrameAnalysis> windowFrames() {
        return new ArrayList<FrameAnalysis>(window);
    }

    /** Per-key median of the analyzer's PAD outputs over the window: audit only. */
    public Map<String, Float> padDetailMedians() {
        Map<String, List<Float>> byKey = new LinkedHashMap<String, List<Float>>();
        for (FrameAnalysis f : window) {
            for (Map.Entry<String, Float> e : f.padDetails.entrySet()) {
                List<Float> l = byKey.get(e.getKey());
                if (l == null) byKey.put(e.getKey(), l = new ArrayList<Float>());
                l.add(e.getValue());
            }
        }
        Map<String, Float> out = new LinkedHashMap<String, Float>();
        for (Map.Entry<String, List<Float>> e : byKey.entrySet()) out.put(e.getKey(), Stats.median(e.getValue()));
        return out;
    }
}
