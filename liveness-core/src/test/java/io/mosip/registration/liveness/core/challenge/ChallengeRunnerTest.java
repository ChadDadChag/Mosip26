package io.mosip.registration.liveness.core.challenge;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder.ChallengeOutcome;
import io.mosip.registration.liveness.core.quality.Iso19794Check;
import io.mosip.registration.liveness.core.session.ActiveStep;
import io.mosip.registration.liveness.core.session.ActiveStep.Kind;
import io.mosip.registration.liveness.core.testing.Fa;
import io.mosip.registration.liveness.core.util.SeededRandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeRunnerTest {

    static final long FRAME_MS = 67; // ~15 fps during challenges

    interface Shape { Fa apply(Fa f); }

    static final Shape NEUTRAL = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f); } };
    static final Shape CLOSED = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).eyesClosed(); } };
    static final Shape SMILE = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).smile(); } };

    static Shape yaw(final float d) { return new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).yaw(d); } }; }

    final LivenessPolicy policy = new LivenessPolicy();
    final EngineTuning tuning = EngineTuning.defaults();
    ChallengeRunner runner;
    final List<ActiveStep> steps = new ArrayList<ActiveStep>();
    final Set<Frame> midActionFrames = new HashSet<Frame>();
    long t;
    long seq;

    void start(long seed, ChallengeType... sequence) {
        runner = new ChallengeRunner(policy, tuning, new SeededRandomSource(seed));
        List<FrameAnalysis> passiveWindow = new ArrayList<FrameAnalysis>();
        for (int i = 0; i < 10; i++) passiveWindow.add(Fa.at(t += 125, ++seq).score(0.6f).build());
        runner.start(Arrays.asList(sequence), passiveWindow, t);
    }

    FrameAnalysis frame(Shape s) {
        t += FRAME_MS;
        FrameAnalysis a = s.apply(Fa.at(t, ++seq)).build();
        runner.onFrame(a, t, steps);
        return a;
    }

    void frames(int n, Shape s) { for (int i = 0; i < n; i++) frame(s); }

    void midAction(int n, Shape s) { for (int i = 0; i < n; i++) midActionFrames.add(frame(s).frame); }

    /** Feeds neutral frames until the next prompt appears (jitter + baseline). */
    void untilPrompt() {
        int prompts = count(Kind.PROMPT);
        for (int i = 0; i < 40 && count(Kind.PROMPT) == prompts; i++) frame(NEUTRAL);
        assertThat(count(Kind.PROMPT)).as("prompt shown").isEqualTo(prompts + 1);
    }

    int count(Kind k) {
        int n = 0;
        for (ActiveStep s : steps) if (s.kind == k) n++;
        return n;
    }

    ActiveStep last(Kind k) {
        for (int i = steps.size() - 1; i >= 0; i--) if (steps.get(i).kind == k) return steps.get(i);
        throw new AssertionError("no " + k + " in " + steps);
    }

    List<Kind> kinds() {
        List<Kind> k = new ArrayList<Kind>();
        for (ActiveStep s : steps) if (s.kind != Kind.PROGRESS) k.add(s.kind);
        return k;
    }

    @Test
    void blinkThenTurnLeftPassesWithANeutralBestFrame() {
        start(1, ChallengeType.BLINK, ChallengeType.TURN_LEFT);
        untilPrompt();
        assertThat(last(Kind.PROMPT).challenge).isEqualTo(ChallengeType.BLINK);
        assertThat(last(Kind.PROMPT).index).isEqualTo(1);
        frames(5, NEUTRAL);
        midAction(1, CLOSED);
        frame(NEUTRAL);
        assertThat(last(Kind.ACTION_DETECTED).challenge).isEqualTo(ChallengeType.BLINK);

        untilPrompt();
        assertThat(last(Kind.PROMPT).challenge).isEqualTo(ChallengeType.TURN_LEFT);
        assertThat(last(Kind.PROMPT).index).isEqualTo(2);
        frames(4, NEUTRAL);
        midAction(2, yaw(12));
        midAction(4, yaw(27)); // detected when the hold completes, the head still turned
        assertThat(last(Kind.ACTION_DETECTED).challenge).isEqualTo(ChallengeType.TURN_LEFT);
        assertThat(last(Kind.ACTION_DETECTED).durationMs).isPositive();
        midAction(1, yaw(14));
        frame(yaw(4));

        frames(10, NEUTRAL);
        // The turn was the last challenge: hold, then LOOK_AT_CAMERA so the user faces the camera for the best frame.
        assertThat(kinds()).containsExactly(Kind.PROMPT, Kind.ACTION_DETECTED, Kind.PROMPT, Kind.ACTION_DETECTED,
                Kind.HOLD_STILL, Kind.GUIDANCE, Kind.PASSED);
        assertThat(last(Kind.GUIDANCE).guidance).isEqualTo(Guidance.LOOK_AT_CAMERA);
        Frame best = last(Kind.PASSED).bestFrame;
        assertThat(best).isNotNull();
        assertThat(midActionFrames).doesNotContain(best);
        assertThat(last(Kind.PASSED).bestFrameFallback).isFalse();
        assertThat(last(Kind.PASSED).bestFrameCheck.status).isEqualTo(Iso19794Check.Status.COMPLIANT);
    }

    @Test
    void withRequireReturnTheTurnWaitsForTheReturnAndNoCueIsShown() {
        tuning.axisRequireReturn = true;
        start(1, ChallengeType.BLINK, ChallengeType.TURN_LEFT);
        untilPrompt();
        frames(5, NEUTRAL);
        midAction(1, CLOSED);
        frame(NEUTRAL);
        untilPrompt();
        frames(4, NEUTRAL);
        midAction(2, yaw(12));
        midAction(4, yaw(27));
        midAction(1, yaw(14));
        assertThat(count(Kind.ACTION_DETECTED)).as("held but not back yet").isEqualTo(1);
        frame(yaw(4));
        assertThat(last(Kind.ACTION_DETECTED).challenge).isEqualTo(ChallengeType.TURN_LEFT);
        frames(10, NEUTRAL);
        assertThat(kinds()).containsExactly(Kind.PROMPT, Kind.ACTION_DETECTED, Kind.PROMPT, Kind.ACTION_DETECTED,
                Kind.HOLD_STILL, Kind.PASSED);
    }

    @Test
    void lookAtCameraFollowsATurnButNotABlinkOrASmile() {
        start(3, ChallengeType.TURN_RIGHT, ChallengeType.SMILE, ChallengeType.BLINK);
        untilPrompt();
        frames(4, NEUTRAL);
        frames(5, yaw(-26));
        assertThat(last(Kind.ACTION_DETECTED).challenge).isEqualTo(ChallengeType.TURN_RIGHT);
        assertThat(steps.get(steps.size() - 1).kind).as("cue right after the turn").isEqualTo(Kind.GUIDANCE);
        assertThat(last(Kind.GUIDANCE).guidance).isEqualTo(Guidance.LOOK_AT_CAMERA);
        untilPrompt();
        frames(4, NEUTRAL);
        frames(6, SMILE);
        assertThat(last(Kind.ACTION_DETECTED).challenge).isEqualTo(ChallengeType.SMILE);
        untilPrompt();
        frames(5, NEUTRAL);
        frame(CLOSED);
        frame(NEUTRAL);
        assertThat(last(Kind.ACTION_DETECTED).challenge).isEqualTo(ChallengeType.BLINK);
        frames(10, NEUTRAL);
        assertThat(count(Kind.GUIDANCE)).as("only the turn's cue").isEqualTo(1);
        assertThat(count(Kind.PASSED)).isEqualTo(1);
    }

    @Test
    void progressIsReportedAndHeartbeatsWhileWaiting() {
        start(1, ChallengeType.TURN_RIGHT);
        untilPrompt();
        int before = count(Kind.PROGRESS);
        frames(3, NEUTRAL);
        frame(yaw(-12));
        assertThat(count(Kind.PROGRESS)).isGreaterThan(before);
        int mid = count(Kind.PROGRESS);
        for (int i = 0; i < 30; i++) { t += 100; runner.onTick(t, steps); } // 3 s without frames
        assertThat(count(Kind.PROGRESS)).isGreaterThanOrEqualTo(mid + 2);
    }

    @Test
    void smileHoldFramesThatStillSmileAreNotTheBestFrame() {
        start(2, ChallengeType.SMILE);
        untilPrompt();
        frames(3, NEUTRAL);
        midAction(5, SMILE);
        assertThat(count(Kind.ACTION_DETECTED)).isEqualTo(1);
        midAction(4, SMILE); // still smiling during the hold
        frames(10, NEUTRAL);
        assertThat(midActionFrames).doesNotContain(last(Kind.PASSED).bestFrame);
    }

    @Test
    void wrongDirectionFailsAndIsAttributedToTheChallenge() {
        start(3, ChallengeType.TURN_LEFT);
        untilPrompt();
        frames(3, NEUTRAL);
        frame(yaw(-25));
        ActiveStep f = last(Kind.FAILED);
        assertThat(f.error).isEqualTo(ErrorCode.CHALLENGE_FAILED);
        assertThat(f.outcome).isEqualTo(ChallengeOutcome.WRONG_ACTION);
        assertThat(f.challenge).isEqualTo(ChallengeType.TURN_LEFT);
        int n = steps.size();
        frames(5, NEUTRAL);
        assertThat(steps).as("nothing after a failure").hasSize(n);
    }

    @Test
    void doingNothingTimesOut() {
        start(4, ChallengeType.SMILE);
        untilPrompt();
        frames((int) (policy.challengeTimeoutMs / FRAME_MS) + 1, NEUTRAL);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
        assertThat(last(Kind.FAILED).outcome).isEqualTo(ChallengeOutcome.TIMEOUT);
    }

    @Test
    void timeoutAlsoFiresOnTicksWithoutFrames() {
        start(4, ChallengeType.SMILE);
        untilPrompt();
        t += policy.challengeTimeoutMs;
        runner.onTick(t, steps);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
    }

    @Test
    void actionOnlyBeforeThePromptDoesNotCount() {
        // No passive seed: the baseline needs 5 fresh frames, so these blinks are certainly before the prompt.
        runner = new ChallengeRunner(policy, tuning, new SeededRandomSource(5));
        runner.start(Arrays.asList(ChallengeType.BLINK), new ArrayList<FrameAnalysis>(), t);
        frame(CLOSED);
        frame(NEUTRAL);
        frame(CLOSED);
        assertThat(count(Kind.PROMPT)).isZero();
        untilPrompt();
        // ...and after the prompt the user does nothing.
        frames((int) (policy.challengeTimeoutMs / FRAME_MS) + 1, NEUTRAL);
        assertThat(count(Kind.ACTION_DETECTED)).isZero();
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
    }

    @Test
    void padSignalDuringTheChallengeFailsEvenIfTheActionIsPerformed() {
        start(6, ChallengeType.BLINK);
        untilPrompt();
        Shape attack = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.1f); } };
        frames(3, attack);
        ActiveStep f = last(Kind.FAILED);
        assertThat(f.error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(f.padMedian).isLessThan(policy.padRejectThreshold);
        assertThat(count(Kind.ACTION_DETECTED)).isZero();
    }

    @Test
    void padIsCheckedDuringTheFinalHoldToo() {
        start(6, ChallengeType.BLINK);
        untilPrompt();
        frames(4, NEUTRAL);
        frame(CLOSED);
        frame(NEUTRAL);
        assertThat(count(Kind.HOLD_STILL)).isEqualTo(1);
        Shape attack = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.1f); } };
        frames(3, attack);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(count(Kind.PASSED)).isZero();
    }

    @Test
    void secondFaceMidChallengeFails() {
        start(7, ChallengeType.SMILE);
        untilPrompt();
        Shape two = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).faces(2); } };
        frame(two);
        assertThat(count(Kind.FAILED)).isZero();
        frame(two);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.MULTIPLE_FACES);
    }

    @Test
    void faceLostForASecondFails() {
        start(8, ChallengeType.SMILE);
        untilPrompt();
        Shape none = new Shape() { @Override public Fa apply(Fa f) { return f.faces(0); } };
        frames(15, none); // first missing frame starts the timer: 14 * 67 = 938 ms
        assertThat(count(Kind.FAILED)).isZero();
        assertThat(last(Kind.GUIDANCE).guidance).isEqualTo(Guidance.NO_FACE);
        frame(none);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.FACE_NOT_DETECTED);
    }

    @Test
    void faceBoxJumpFailsAsContinuityBreak() {
        start(9, ChallengeType.SMILE);
        untilPrompt();
        frame(new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).shift(250, 0); } });
        assertThat(last(Kind.FAILED).outcome).isEqualTo(ChallengeOutcome.CONTINUITY_BREAK);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.CHALLENGE_FAILED);
    }

    @Test
    void unstableBaselineGetsGuidanceThenTimesOut() {
        runner = new ChallengeRunner(policy, tuning, new SeededRandomSource(10));
        runner.start(Arrays.asList(ChallengeType.BLINK), new ArrayList<FrameAnalysis>(), t);
        for (int i = 0; i < 70; i++) frame(yaw(30)); // user keeps looking away
        assertThat(last(Kind.GUIDANCE).guidance).isEqualTo(Guidance.LOOK_AT_CAMERA);
        assertThat(last(Kind.FAILED).error).isEqualTo(ErrorCode.CHALLENGE_TIMEOUT);
        assertThat(last(Kind.FAILED).outcome).isEqualTo(ChallengeOutcome.BASELINE_UNSTABLE);
        assertThat(last(Kind.FAILED).challenge).as("never issued").isNull();
    }

    @Test
    void promptTimingIsJittered() {
        Set<Long> delays = new HashSet<Long>();
        for (long seed = 0; seed < 30; seed++) {
            steps.clear();
            start(seed, ChallengeType.BLINK);
            long startedAt = t;
            untilPrompt();
            long delay = t - startedAt;
            assertThat(delay).isBetween(FRAME_MS, (long) tuning.maxPromptJitterMs + FRAME_MS);
            delays.add(delay);
        }
        assertThat(delays.size()).as("different seeds give different prompt times").isGreaterThanOrEqualTo(4);
    }

    @Test
    void holdFallsBackWhenNoQualifyingFrameArrives() {
        start(11, ChallengeType.BLINK);
        untilPrompt();
        frames(4, NEUTRAL);
        frame(CLOSED);
        frame(NEUTRAL);
        steps.clear();
        // Hold frames are all slightly turned (not frontal enough for the best frame), passive seeds still exist.
        for (int i = 0; i < 40 && count(Kind.PASSED) == 0; i++) frame(yaw(12));
        assertThat(last(Kind.PASSED).bestFrame).isNotNull();
    }

    @Test
    void whenNothingQualifiesTheFallbackFrameIsFlaggedAndChecked() {
        final Shape turned = yaw(12); // beyond the best-frame limit (10°), still a stable baseline
        Shape turnedClosed = new Shape() { @Override public Fa apply(Fa f) { return turned.apply(f).eyesClosed(); } };
        Shape turnedSmiling = new Shape() { @Override public Fa apply(Fa f) { return turned.apply(f).smile(); } };
        runner = new ChallengeRunner(policy, tuning, new SeededRandomSource(11));
        List<FrameAnalysis> seeds = new ArrayList<FrameAnalysis>();
        for (int i = 0; i < 10; i++) seeds.add(turned.apply(Fa.at(t += 125, ++seq)).build());
        runner.start(Arrays.asList(ChallengeType.BLINK), seeds, t);
        for (int i = 0; i < 40 && count(Kind.PROMPT) == 0; i++) frame(turned);
        frames(4, turned);
        frame(turnedClosed);
        frame(turned);
        assertThat(count(Kind.ACTION_DETECTED)).isEqualTo(1);

        long holdStart = t;
        for (int i = 0; i < 60 && count(Kind.PASSED) == 0; i++) frame(turnedSmiling);
        ActiveStep passed = last(Kind.PASSED);
        assertThat(t - holdStart).as("the hold is not extended past holdMaxMs").isLessThanOrEqualTo(tuning.holdMaxMs + FRAME_MS);
        assertThat(passed.bestFrame).isNotNull();
        assertThat(passed.bestFrameFallback).isTrue();
        assertThat(passed.bestFrameCheck.status).isEqualTo(Iso19794Check.Status.NOT_COMPLIANT);
        assertThat(passed.bestFrameCheck.issues).contains(Iso19794Check.Issue.POSE, Iso19794Check.Issue.EXPRESSION);
    }
}
