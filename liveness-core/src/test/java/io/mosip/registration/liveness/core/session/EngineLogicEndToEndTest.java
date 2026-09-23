package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.challenge.ChallengeRunner;
import io.mosip.registration.liveness.core.challenge.ChallengeSelector;
import io.mosip.registration.liveness.core.testing.Fa;
import io.mosip.registration.liveness.core.util.ManualTimeSource;
import io.mosip.registration.liveness.core.util.SeededRandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SessionStateMachine + the real ChallengeRunner and detectors, driven by synthetic analyzer output. */
class EngineLogicEndToEndTest {

    interface Shape { Fa apply(Fa f); }

    final LivenessPolicy policy = new LivenessPolicy();
    SessionStateMachine sm;
    final List<Effect> effects = new ArrayList<Effect>();
    long t = 0, seq = 0;

    void build(int minChallenges, long seed) {
        policy.minChallenges = minChallenges;
        EngineTuning tuning = EngineTuning.defaults();
        sm = new SessionStateMachine(policy, tuning, "sid", "m",
                new AuditRecorder(null, WorkflowType.RESIDENT, "sid", "m", new ManualTimeSource(0), "core"),
                new ChallengeSelector(new SeededRandomSource(seed)),
                new ChallengeRunner(policy, tuning, new SeededRandomSource(seed)));
        effects.addAll(sm.start(t));
    }

    void frames(int n, long stepMs, Shape s) {
        for (int i = 0; i < n; i++) {
            t += stepMs;
            effects.addAll(sm.onFrame(s.apply(Fa.at(t, ++seq)).build(), t));
        }
    }

    LivenessEvent last(Type type) {
        for (int i = effects.size() - 1; i >= 0; i--) {
            Effect e = effects.get(i);
            if (e.kind == Effect.Kind.EMIT && e.event.type == type) return e.event;
        }
        return null;
    }

    LivenessResult result() {
        for (Effect e : effects) if (e.kind == Effect.Kind.COMPLETE) return e.result;
        return null;
    }

    static final Shape MID = new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f); } };

    /** Performs whatever the current prompt asks for, like a cooperative live user. */
    Shape perform(final ChallengeType c, final int frameInAction) {
        return new Shape() {
            @Override public Fa apply(Fa f) {
                Fa g = f.score(0.6f);
                switch (c) {
                    case BLINK: return frameInAction == 0 ? g.eyesClosed() : g;
                    case SMILE: return frameInAction < 6 ? g.smile() : g;
                    case TURN_LEFT: return frameInAction < 5 ? g.yaw(26) : g;
                    case TURN_RIGHT: return frameInAction < 5 ? g.yaw(-26) : g;
                    case LOOK_UP: return frameInAction < 5 ? g.pitch(18) : g;
                    default: return frameInAction < 5 ? g.pitch(-18) : g;
                }
            }
        };
    }

    @Test
    void inconclusivePassiveThenBlinkPasses() {
        for (long seed = 0; seed < 20; seed++) {
            effects.clear();
            build(2, seed);
            frames(10, 125, MID); // passive window at 8 fps: median 0.6 -> challenges
            assertThat(sm.state()).isEqualTo(SessionStateMachine.State.ACTIVE_CHALLENGE);
            int prompts = 0;
            for (int guard = 0; guard < 400 && result() == null; guard++) {
                LivenessEvent prompt = last(Type.CHALLENGE_PROMPT);
                if (prompt != null && countPrompts() > prompts) {
                    prompts = countPrompts();
                    frames(4, 67, MID); // reaction time
                    for (int k = 0; k < 8; k++) frames(1, 67, perform(prompt.challenge, k));
                } else {
                    frames(1, 67, MID);
                }
            }
            LivenessResult r = result();
            assertThat(r).as("seed " + seed).isNotNull();
            assertThat(r.decision).as("seed " + seed + " " + effects).isEqualTo(LivenessResult.Decision.PASSED);
            assertThat(r.passPath).isEqualTo(LivenessResult.PassPath.ACTIVE_CHALLENGE);
            assertThat(r.challengesIssued).hasSize(2);
        }
    }

    int countPrompts() {
        int n = 0;
        for (Effect e : effects) if (e.kind == Effect.Kind.EMIT && e.event.type == Type.CHALLENGE_PROMPT) n++;
        return n;
    }

    @Test
    void oppositeTurnFailsTheAttemptWithChallengeFailed() {
        policy.challengePool = new ArrayList<ChallengeType>(java.util.Arrays.asList(ChallengeType.TURN_LEFT));
        build(1, 1);
        frames(10, 125, MID);
        for (int i = 0; i < 20 && last(Type.CHALLENGE_PROMPT) == null; i++) frames(1, 67, MID);
        frames(4, 67, MID);
        frames(3, 67, new Shape() { @Override public Fa apply(Fa f) { return f.score(0.6f).yaw(-25); } });
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.CHALLENGE_FAILED);
    }

    @Test
    void attackDuringChallengeCannotBeCompensatedByPerformingTheAction() {
        policy.challengePool = new ArrayList<ChallengeType>(java.util.Arrays.asList(ChallengeType.BLINK));
        build(1, 2);
        frames(10, 125, MID);
        for (int i = 0; i < 20 && last(Type.CHALLENGE_PROMPT) == null; i++) frames(1, 67, MID);
        frames(4, 67, new Shape() { @Override public Fa apply(Fa f) { return f.score(0.1f); } });
        frames(1, 67, new Shape() { @Override public Fa apply(Fa f) { return f.score(0.1f).eyesClosed(); } });
        frames(3, 67, new Shape() { @Override public Fa apply(Fa f) { return f.score(0.1f); } });
        assertThat(last(Type.ATTEMPT_FAILED).error).isEqualTo(ErrorCode.PAD_FAILURE);
        assertThat(last(Type.ACTION_DETECTED)).isNull();
    }
}
