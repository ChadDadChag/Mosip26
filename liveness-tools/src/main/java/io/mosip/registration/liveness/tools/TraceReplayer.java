package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.detect.ActionDetector;
import io.mosip.registration.liveness.core.detect.Baseline;
import io.mosip.registration.liveness.core.detect.DetectorStatus;
import io.mosip.registration.liveness.core.detect.Detectors;
import io.mosip.registration.liveness.core.passive.PassiveEvaluator;

import java.util.ArrayList;
import java.util.List;

/** Replays traces through the real detectors and the real passive evaluator. */
public final class TraceReplayer {

    /** Arbitrary base so replayed timestamps are positive and monotonic. */
    static final long TIME_BASE_MS = 1000000;
    /** How long after the prompt a detector may take (the default policy's challenge timeout). */
    static final long DETECT_WINDOW_MS = 6000;

    private TraceReplayer() { }

    public static final class DetectorRun {
        public final boolean detected;
        public final boolean wrongAction;
        /** Prompt → DETECTED in ms, or -1. */
        public final long timeToDetectMs;

        DetectorRun(boolean detected, boolean wrongAction, long timeToDetectMs) {
            this.detected = detected; this.wrongAction = wrongAction; this.timeToDetectMs = timeToDetectMs;
        }
    }

    public static List<FrameAnalysis> analyses(Trace trace) {
        List<FrameAnalysis> out = new ArrayList<FrameAnalysis>();
        for (TraceFrame f : trace.frames) out.add(SyntheticFace.analysis(f, TIME_BASE_MS));
        return out;
    }

    /**
     * Runs one detector as the challenge runner would: baseline from stable frames before the prompt, then frames
     * from the prompt on, until DETECTED, WRONG_ACTION or the detection window ends.
     */
    public static DetectorRun runDetector(Trace trace, ChallengeType type, EngineTuning tuning) {
        List<FrameAnalysis> frames = analyses(trace);
        long promptAt = TIME_BASE_MS + trace.promptAtMs;
        List<FrameAnalysis> before = new ArrayList<FrameAnalysis>();
        for (FrameAnalysis a : frames) {
            if (a.frame.timestampMs() >= promptAt) break;
            boolean ok = a.faceCount == 1 && Math.abs(a.yawDeg) <= tuning.baselineMaxPoseDeg
                    && Math.abs(a.pitchDeg) <= tuning.baselineMaxPoseDeg;
            if (ok) before.add(a); else before.clear();
        }
        if (before.isEmpty()) return new DetectorRun(false, false, -1);
        List<FrameAnalysis> base = before.subList(Math.max(0, before.size() - tuning.baselineFrames), before.size());
        ActionDetector d = Detectors.create(type, tuning);
        d.reset(Baseline.of(base), promptAt);
        for (FrameAnalysis a : frames) {
            long t = a.frame.timestampMs();
            if (t < promptAt) continue;
            if (t - promptAt > DETECT_WINDOW_MS) break;
            DetectorStatus s = d.update(a, t);
            if (s == DetectorStatus.DETECTED) return new DetectorRun(true, false, t - promptAt);
            if (s == DetectorStatus.WRONG_ACTION) return new DetectorRun(false, true, -1);
        }
        return new DetectorRun(false, false, -1);
    }

    /** The passive verdict the engine would reach on this trace, and the window median. */
    public static final class PassiveRun {
        public final PassiveEvaluator.Verdict verdict;
        public final float median;

        PassiveRun(PassiveEvaluator.Verdict verdict, float median) {
            this.verdict = verdict; this.median = median;
        }
    }

    public static PassiveRun runPassive(Trace trace, LivenessPolicy policy, EngineTuning tuning) {
        PassiveEvaluator ev = new PassiveEvaluator(policy, tuning);
        List<FrameAnalysis> frames = analyses(trace);
        if (frames.isEmpty()) return new PassiveRun(PassiveEvaluator.Verdict.PENDING, Float.NaN);
        ev.reset(frames.get(0).frame.timestampMs());
        for (FrameAnalysis a : frames) {
            PassiveEvaluator.Verdict v = ev.offer(a, a.frame.timestampMs());
            if (v != PassiveEvaluator.Verdict.PENDING) return new PassiveRun(v, ev.median());
        }
        return new PassiveRun(PassiveEvaluator.Verdict.PENDING, ev.median());
    }
}
