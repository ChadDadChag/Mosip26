package io.mosip.registration.liveness.core.challenge;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder.ChallengeOutcome;
import io.mosip.registration.liveness.core.detect.ActionDetector;
import io.mosip.registration.liveness.core.detect.Baseline;
import io.mosip.registration.liveness.core.detect.DetectorStatus;
import io.mosip.registration.liveness.core.detect.Detectors;
import io.mosip.registration.liveness.core.detect.FaceContinuityGuard;
import io.mosip.registration.liveness.core.detect.FaceGeometry;
import io.mosip.registration.liveness.core.guidance.PositioningHintFilter;
import io.mosip.registration.liveness.core.passive.PadMonitor;
import io.mosip.registration.liveness.core.quality.BestFrameSelector;
import io.mosip.registration.liveness.core.session.ActivePhase;
import io.mosip.registration.liveness.core.session.ActiveStep;
import io.mosip.registration.liveness.core.util.RandomSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Runs a challenge sequence for one attempt. For each challenge:
 * <ol>
 *   <li><b>Pre-prompt:</b> wait a random 0..{@code maxPromptJitterMs} and until {@code baselineFrames} stable,
 *       near-frontal frames give a per-user {@link Baseline} (for the first challenge the passive window seeds
 *       it). The baseline comes from frames BEFORE the prompt: people start moving 200-500 ms after it.</li>
 *   <li><b>Prompted:</b> the challenge's {@link ActionDetector} runs until DETECTED, WRONG_ACTION or the policy
 *       timeout. Progress is reported per quarter and re-sent as a "please continue" heartbeat.</li>
 * </ol>
 * After the last challenge a short neutral <b>hold</b> picks the best frame (never a mid-action frame).
 *
 * <p>On every frame, in every sub-phase: exactly one face (no face for {@code faceLostMs} or several faces for
 * {@code multiFaceChallengeFrames} frames fail), {@link FaceContinuityGuard} (face swap / photo switch), and the
 * rolling {@link PadMonitor} (an attack signal fails immediately; performing the action cannot compensate).
 */
public final class ChallengeRunner implements ActivePhase {

    private enum Sub { PRE_PROMPT, PROMPTED, HOLD, DONE }

    /** TUNE: in the hold, the mouth must be within this fraction of baseline width to count as neutral. */
    static final float HOLD_MOUTH_TOLERANCE = 0.08f;
    /** TUNE: pre-prompt, show LOOK_AT_CAMERA if no stable baseline after this long. */
    static final long BASELINE_HINT_AFTER_MS = 1000;

    private final LivenessPolicy policy;
    private final EngineTuning t;
    private final RandomSource random;
    private final PadMonitor pad;
    private final FaceContinuityGuard continuity;
    private final PositioningHintFilter hints;
    private final BestFrameSelector best;
    private final Deque<FrameAnalysis> baselineWindow = new ArrayDeque<FrameAnalysis>();

    private List<ChallengeType> sequence = Collections.emptyList();
    private int index;
    private Sub sub = Sub.DONE;
    private long subStartMs;
    private long promptNotBeforeMs;
    private boolean baselineHintShown;
    private long promptAtMs;
    private ActionDetector detector;
    private Baseline baseline;
    private int lastProgressBucket;
    private long lastProgressEmitMs;
    private long faceLostSinceMs = -1;
    private int multiFaceFrames;
    private FrameAnalysis lastSingleFace;

    public ChallengeRunner(LivenessPolicy policy, EngineTuning tuning, RandomSource random) {
        this.policy = policy;
        this.t = tuning;
        this.random = random;
        this.pad = new PadMonitor(policy.padRejectThreshold, tuning.padMonitorFrames);
        this.continuity = new FaceContinuityGuard(tuning);
        this.hints = new PositioningHintFilter(tuning);
        this.best = new BestFrameSelector(tuning);
    }

    @Override
    public Set<ChallengeType> supported() {
        return Detectors.supported();
    }

    @Override
    public void start(List<ChallengeType> sequence, List<FrameAnalysis> seedFrames, long nowMs) {
        this.sequence = new ArrayList<ChallengeType>(sequence);
        index = 0;
        pad.reset();
        continuity.reset();
        hints.reset();
        best.reset();
        baselineWindow.clear();
        faceLostSinceMs = -1;
        multiFaceFrames = 0;
        lastSingleFace = null;
        for (FrameAnalysis f : seedFrames) {
            best.offer(f); // passive-window frames are pre-action and neutral
            addBaselineFrame(f);
            if (f.faceCount == 1) lastSingleFace = f;
        }
        beginPrePrompt(nowMs);
    }

    @Override
    public void onFrame(FrameAnalysis a, long nowMs, List<ActiveStep> out) {
        if (sub == Sub.DONE) return;

        PositioningHintFilter.Output hint = hints.update(a, nowMs, true);
        if (hint.show != null) out.add(ActiveStep.guidance(hint.show));
        else if (hint.clear && sub == Sub.PROMPTED) out.add(clearStep());

        if (a.faceCount == 0) {
            if (faceLostSinceMs < 0) faceLostSinceMs = nowMs;
            checkFaceLost(nowMs, out);
            return;
        }
        faceLostSinceMs = -1;
        if (a.faceCount > 1) {
            if (++multiFaceFrames >= t.multiFaceChallengeFrames) {
                fail(ErrorCode.MULTIPLE_FACES, ChallengeOutcome.MULTIPLE_FACES, Float.NaN, nowMs, out);
            }
            return;
        }
        multiFaceFrames = 0;
        if (!continuity.check(a, nowMs)) {
            fail(ErrorCode.CHALLENGE_FAILED, ChallengeOutcome.CONTINUITY_BREAK, Float.NaN, nowMs, out);
            return;
        }
        if (pad.attack(a)) {
            fail(ErrorCode.PAD_FAILURE, ChallengeOutcome.PAD_FAILURE, pad.median(), nowMs, out);
            return;
        }
        lastSingleFace = a;

        switch (sub) {
            case PRE_PROMPT:
                addBaselineFrame(a);
                prePrompt(nowMs, out);
                break;
            case PROMPTED:
                if (timedOut(nowMs, out)) return;
                DetectorStatus status = detector.update(a, nowMs);
                if (status == DetectorStatus.DETECTED) {
                    out.add(ActiveStep.actionDetected(current(), index + 1, sequence.size(), nowMs - promptAtMs));
                    next(nowMs, out);
                } else if (status == DetectorStatus.WRONG_ACTION) {
                    fail(ErrorCode.CHALLENGE_FAILED, ChallengeOutcome.WRONG_ACTION, Float.NaN, nowMs, out);
                } else {
                    progress(nowMs, out);
                }
                break;
            case HOLD:
                if (isNeutral(a)) best.offer(a);
                hold(nowMs, out);
                break;
            default:
                break;
        }
    }

    @Override
    public void onTick(long nowMs, List<ActiveStep> out) {
        if (sub == Sub.DONE) return;
        if (checkFaceLost(nowMs, out)) return;
        switch (sub) {
            case PRE_PROMPT:
                prePrompt(nowMs, out);
                break;
            case PROMPTED:
                if (!timedOut(nowMs, out)) progress(nowMs, out);
                break;
            case HOLD:
                hold(nowMs, out);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ sub-phases

    private void beginPrePrompt(long nowMs) {
        sub = Sub.PRE_PROMPT;
        subStartMs = nowMs;
        promptNotBeforeMs = nowMs + random.nextInt(t.maxPromptJitterMs + 1);
        baselineHintShown = false;
        detector = null;
    }

    private void prePrompt(long nowMs, List<ActiveStep> out) {
        boolean stable = baselineStable();
        if (nowMs >= promptNotBeforeMs && stable) {
            baseline = Baseline.of(new ArrayList<FrameAnalysis>(baselineWindow));
            detector = Detectors.create(current(), t);
            detector.reset(baseline, nowMs);
            promptAtMs = nowMs;
            lastProgressBucket = 0;
            lastProgressEmitMs = nowMs;
            sub = Sub.PROMPTED;
            out.add(ActiveStep.prompt(current(), index + 1, sequence.size()));
            return;
        }
        if (nowMs - subStartMs >= t.baselineTimeoutMs) {
            fail(ErrorCode.CHALLENGE_TIMEOUT, ChallengeOutcome.BASELINE_UNSTABLE, Float.NaN, nowMs, out);
        } else if (!stable && !baselineHintShown && nowMs - subStartMs >= BASELINE_HINT_AFTER_MS) {
            baselineHintShown = true;
            out.add(ActiveStep.guidance(Guidance.LOOK_AT_CAMERA));
        }
    }

    private void next(long nowMs, List<ActiveStep> out) {
        index++;
        baselineWindow.clear(); // the next baseline comes from fresh neutral frames after this action
        if (index < sequence.size()) {
            beginPrePrompt(nowMs);
        } else {
            sub = Sub.HOLD;
            subStartMs = nowMs;
            out.add(ActiveStep.holdStill());
        }
    }

    private void hold(long nowMs, List<ActiveStep> out) {
        long held = nowMs - subStartMs;
        if (held >= t.holdMinMs && best.hasBest()) {
            finish(best.bestFrame(), out);
        } else if (held >= t.holdMaxMs) {
            // Liveness is established; hand back the best frame seen, else the latest single-face frame.
            finish(best.hasBest() ? best.bestFrame() : lastSingleFace != null ? lastSingleFace.frame : null, out);
        }
    }

    private void finish(Frame frame, List<ActiveStep> out) {
        sub = Sub.DONE;
        out.add(ActiveStep.passed(frame));
    }

    // ------------------------------------------------------------------ checks and helpers

    private boolean timedOut(long nowMs, List<ActiveStep> out) {
        if (nowMs - promptAtMs < policy.challengeTimeoutMs) return false;
        fail(ErrorCode.CHALLENGE_TIMEOUT, ChallengeOutcome.TIMEOUT, Float.NaN, nowMs, out);
        return true;
    }

    private boolean checkFaceLost(long nowMs, List<ActiveStep> out) {
        if (faceLostSinceMs < 0 || nowMs - faceLostSinceMs < t.faceLostMs) return false;
        fail(ErrorCode.FACE_NOT_DETECTED, ChallengeOutcome.FACE_LOST, Float.NaN, nowMs, out);
        return true;
    }

    private void progress(long nowMs, List<ActiveStep> out) {
        float p = detector.progress();
        int bucket = (int) (p * 4);
        if (bucket > lastProgressBucket || nowMs - lastProgressEmitMs >= t.progressHeartbeatMs) {
            lastProgressBucket = Math.max(lastProgressBucket, bucket);
            lastProgressEmitMs = nowMs;
            out.add(ActiveStep.progress(current(), index + 1, sequence.size(), p));
        }
    }

    private ActiveStep clearStep() {
        return ActiveStep.clearGuidance(current(), index + 1, sequence.size(), detector != null ? detector.progress() : 0f);
    }

    private void fail(ErrorCode code, ChallengeOutcome outcome, float padMedian, long nowMs, List<ActiveStep> out) {
        // The challenge is only attributed once it was prompted (issued); earlier failures belong to the attempt.
        ChallengeType c = sub == Sub.PROMPTED ? current() : null;
        long duration = sub == Sub.PROMPTED ? nowMs - promptAtMs : 0;
        sub = Sub.DONE;
        out.add(ActiveStep.failed(code, c, outcome, duration, padMedian));
    }

    private void addBaselineFrame(FrameAnalysis a) {
        boolean eligible = a.faceCount == 1 && FaceGeometry.withinPose(a, t.baselineMaxPoseDeg);
        if (!eligible) {
            baselineWindow.clear(); // a baseline must be consecutive stable frames
            return;
        }
        baselineWindow.addLast(a);
        while (baselineWindow.size() > t.baselineFrames) baselineWindow.removeFirst();
    }

    private boolean baselineStable() {
        if (baselineWindow.size() < t.baselineFrames) return false;
        return spread(true) <= t.baselineMaxSpreadDeg && spread(false) <= t.baselineMaxSpreadDeg;
    }

    /** Max - min of the reported yaw (or pitch) in the baseline window; unknown (NaN) values are skipped. */
    private float spread(boolean yaw) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (FrameAnalysis f : baselineWindow) {
            float v = yaw ? f.yawDeg : f.pitchDeg;
            if (Float.isNaN(v)) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return min > max ? 0f : max - min;
    }

    /** Hold frames must look like the neutral baseline: mouth back to normal, eyes open. */
    private boolean isNeutral(FrameAnalysis a) {
        if (baseline == null) return true;
        float mouth = FaceGeometry.mouthWidthRatio(a.landmarks);
        if (!Float.isNaN(mouth) && baseline.mouthWidth > 0
                && Math.abs(mouth / baseline.mouthWidth - 1f) > HOLD_MOUTH_TOLERANCE) return false;
        float ear = FaceGeometry.meanEar(a.landmarks);
        return Float.isNaN(ear) || !(baseline.ear > 0) || ear >= t.blinkReopenRatio * baseline.ear;
    }

    private ChallengeType current() {
        return sequence.get(Math.min(index, sequence.size() - 1));
    }
}
