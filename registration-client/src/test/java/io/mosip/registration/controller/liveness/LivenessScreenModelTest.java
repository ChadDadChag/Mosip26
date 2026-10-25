package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.controller.liveness.LivenessScreenModel.Icon;
import io.mosip.registration.controller.liveness.LivenessScreenModel.ProgressMode;
import io.mosip.registration.controller.liveness.LivenessScreenModel.Terminal;
import io.mosip.registration.controller.liveness.LivenessScreenModel.Text;
import io.mosip.registration.controller.liveness.LivenessScreenModel.Tone;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

/** The state table of docs/ui-liveness-spec.md §3, row by row. */
public class LivenessScreenModelTest {

	private final LivenessScreenModel model = new LivenessScreenModel(WorkflowType.RESIDENT, 3);
	private long now = 10_000;

	private void apply(LivenessEvent e) {
		model.onEvent(e, now);
	}

	private static Text text(String key, Object... args) {
		return new Text(key, args);
	}

	@Test
	public void startsWithTheStartingStateAndOnlyCancel() {
		assertEquals(text("liveness.title.resident"), model.title());
		assertEquals(text("liveness.status.starting"), model.status());
		assertEquals(text("liveness.progress.attempt", 1, 4), model.attempt());
		assertButtons(false, true, false);
		assertEquals(ProgressMode.HIDDEN, model.progressMode());
	}

	@Test
	public void startedShowsTheAttemptFromTheEvent() {
		apply(LivenessEvent.of(Type.STARTED, 2));
		assertEquals(text("liveness.status.starting"), model.status());
		assertEquals(text("liveness.progress.attempt", 2, 4), model.attempt());
		assertEquals(Tone.NEUTRAL, model.tone());
		assertEquals(Icon.CAMERA, model.icon());
	}

	@Test
	public void checkingPassiveIsBlueAndIndeterminateAndClearsTheHint() {
		apply(LivenessEvent.guidance(Guidance.MOVE_CLOSER, 1));
		assertEquals(text("liveness.guidance.move_closer"), model.hint());
		apply(LivenessEvent.of(Type.CHECKING_PASSIVE, 1));
		assertEquals(text("liveness.status.checking"), model.status());
		assertNull(model.hint());
		assertEquals(Tone.CHECKING, model.tone());
		assertEquals(Icon.SCANNING, model.icon());
		assertEquals(ProgressMode.INDETERMINATE, model.progressMode());
		assertButtons(false, true, false);
	}

	@Test
	public void guidanceIsDebouncedAndNotRepeated() {
		apply(LivenessEvent.of(Type.CHECKING_PASSIVE, 1));
		apply(LivenessEvent.guidance(Guidance.MOVE_CLOSER, 1));
		assertEquals(text("liveness.guidance.move_closer"), model.hint());
		assertTrue(model.hintIsGuidance());
		assertEquals(text("liveness.guidance.move_closer"), model.announcement());
		assertEquals("guidance never changes the status line", text("liveness.status.checking"), model.status());

		now += 200;
		apply(LivenessEvent.guidance(Guidance.IMPROVE_LIGHTING, 1));
		assertEquals("a different hint within 500 ms is dropped", text("liveness.guidance.move_closer"), model.hint());

		now += 300;
		apply(LivenessEvent.guidance(Guidance.IMPROVE_LIGHTING, 1));
		assertEquals(text("liveness.guidance.improve_lighting"), model.hint());

		now += 1000;
		apply(LivenessEvent.guidance(Guidance.IMPROVE_LIGHTING, 1));
		assertNull("the same hint is not announced again", model.announcement());
	}

	@Test
	public void challengePromptShowsTheActionStepAndEmptyProgress() {
		apply(LivenessEvent.guidance(Guidance.CENTER_FACE, 1));
		apply(LivenessEvent.prompt(ChallengeType.BLINK, 1, 2, 6000, 1));
		assertEquals(text("liveness.challenge.blink"), model.status());
		assertNull(model.hint());
		assertEquals(text("liveness.progress.step", 1, 2), model.step());
		assertEquals(Tone.CHALLENGE, model.tone());
		assertEquals(Icon.EYE, model.icon());
		assertEquals(ProgressMode.DETERMINATE, model.progressMode());
		assertEquals(0, model.progress(), 0);
	}

	@Test
	public void everyChallengeHasItsOwnIconAndText() {
		assertEquals(Icon.SMILE, LivenessScreenModel.challengeIcon(ChallengeType.SMILE));
		assertEquals(Icon.ARROW_LEFT, LivenessScreenModel.challengeIcon(ChallengeType.TURN_LEFT));
		assertEquals(Icon.ARROW_RIGHT, LivenessScreenModel.challengeIcon(ChallengeType.TURN_RIGHT));
		assertEquals(Icon.ARROW_UP, LivenessScreenModel.challengeIcon(ChallengeType.LOOK_UP));
		assertEquals(Icon.ARROW_DOWN, LivenessScreenModel.challengeIcon(ChallengeType.LOOK_DOWN));
		for (ChallengeType c : ChallengeType.values()) {
			assertTrue(c.name(), LivenessMessages.ENGLISH.containsKey(LivenessScreenModel.challengeKey(c)));
		}
	}

	@Test
	public void progressKeepsThePromptShowsPleaseContinueAndClearsGuidance() {
		apply(LivenessEvent.prompt(ChallengeType.SMILE, 2, 2, 6000, 1));
		now += 600;
		apply(LivenessEvent.guidance(Guidance.HOLD_STEADY, 1));
		apply(LivenessEvent.progress(ChallengeType.SMILE, 2, 2, 0.4f, 1));
		assertEquals(text("liveness.challenge.smile"), model.status());
		assertEquals(text("liveness.feedback.continue"), model.hint());
		assertFalse(model.hintIsGuidance());
		assertEquals(0.4, model.progress(), 1e-6);

		apply(LivenessEvent.progress(ChallengeType.SMILE, 2, 2, 0.6f, 1));
		assertNull("progress ticks are not announced", model.announcement());
		assertEquals(0.6, model.progress(), 1e-6);
	}

	@Test
	public void actionDetectedIsGreenAndFull() {
		apply(LivenessEvent.prompt(ChallengeType.TURN_LEFT, 1, 1, 6000, 1));
		apply(LivenessEvent.of(Type.ACTION_DETECTED, 1));
		assertEquals(text("liveness.feedback.action_detected"), model.status());
		assertEquals(Tone.DETECTED, model.tone());
		assertEquals(Icon.CHECK, model.icon());
		assertEquals(1, model.progress(), 0);
	}

	@Test
	public void holdStillThenLookAtCameraAfterATurn() {
		apply(LivenessEvent.prompt(ChallengeType.TURN_RIGHT, 1, 1, 6000, 1));
		apply(LivenessEvent.of(Type.ACTION_DETECTED, 1));
		apply(LivenessEvent.of(Type.HOLD_STILL, 1));
		assertEquals(text("liveness.feedback.hold_still"), model.status());
		assertNull(model.step());
		assertEquals(ProgressMode.INDETERMINATE, model.progressMode());
		apply(LivenessEvent.guidance(Guidance.LOOK_AT_CAMERA, 1));
		assertEquals(text("liveness.guidance.look_at_camera"), model.hint());
		assertEquals(text("liveness.feedback.hold_still"), model.status());
	}

	@Test
	public void attemptFailedOffersRetryWithTipsForLivenessNotVerified() {
		apply(LivenessEvent.prompt(ChallengeType.BLINK, 1, 2, 6000, 1));
		apply(LivenessEvent.failure(Type.ATTEMPT_FAILED, ErrorCode.CHALLENGE_TIMEOUT, 1, 3, true));
		assertEquals(text("liveness.error.liveness_not_verified"), model.status());
		assertEquals(text("liveness.retry.attempts_left", 3), model.hint());
		assertTrue(model.showTips());
		assertEquals(Tone.FAILURE, model.tone());
		assertEquals(Icon.CROSS, model.icon());
		assertEquals(ProgressMode.HIDDEN, model.progressMode());
		assertNull(model.step());
		assertButtons(true, true, false);
	}

	@Test
	public void padFailureLooksLikeAnyVerificationFailure() {
		apply(LivenessEvent.failure(Type.ATTEMPT_FAILED, ErrorCode.PAD_FAILURE, 1, 2, true));
		assertEquals(text("liveness.error.verification_not_completed"), model.status());
		assertFalse("no tips for VERIFICATION_NOT_COMPLETED", model.showTips());

		LivenessScreenModel engineError = new LivenessScreenModel(WorkflowType.RESIDENT, 3);
		engineError.onEvent(LivenessEvent.failure(Type.ATTEMPT_FAILED, ErrorCode.ENGINE_ERROR, 1, 2, true), now);
		assertEquals("PAD and engine errors are indistinguishable on screen", model.status(), engineError.status());
		assertEquals(model.icon(), engineError.icon());
	}

	@Test
	public void deviceErrorRetryDependsOnRecoverable() {
		apply(LivenessEvent.failure(Type.DEVICE_ERROR, ErrorCode.DEVICE_DISCONNECTED, 1, 0, true));
		assertEquals(text("liveness.error.device_problem"), model.status());
		assertEquals(Icon.CAMERA_OFF, model.icon());
		assertFalse(model.showTips());
		assertButtons(true, true, false);

		apply(LivenessEvent.failure(Type.DEVICE_ERROR, ErrorCode.DEVICE_DISCONNECTED, 1, 0, false));
		assertButtons(false, true, false);
	}

	@Test
	public void finalFailureShowsRecoveryGuidanceAndOnlyClose() {
		apply(LivenessEvent.failure(Type.FAILED_FINAL, ErrorCode.MAX_RETRIES_EXCEEDED, 4, 0, false));
		assertEquals(text("liveness.error.max_attempts_reached"), model.status());
		assertEquals(text("liveness.recovery.resident"), model.hint());
		assertEquals(Terminal.FAILED, model.terminal());
		assertNull(model.attempt());
		assertButtons(false, false, true);
	}

	@Test
	public void finalPadOrEngineFailureUsesTheMaxAttemptsText() {
		apply(LivenessEvent.failure(Type.FAILED_FINAL, ErrorCode.PAD_FAILURE, 4, 0, false));
		assertEquals(text("liveness.error.max_attempts_reached"), model.status());
	}

	@Test
	public void finalDeviceFailureUsesTheDeviceTextWithRecovery() {
		LivenessScreenModel supervisor = new LivenessScreenModel(WorkflowType.SUPERVISOR, 2);
		supervisor.onEvent(LivenessEvent.failure(Type.FAILED_FINAL, ErrorCode.DEVICE_UNAVAILABLE, 1, 0, false), now);
		assertEquals(text("liveness.error.device_problem"), supervisor.status());
		assertEquals(text("liveness.recovery.supervisor"), supervisor.hint());
		assertEquals(Icon.CAMERA_OFF, supervisor.icon());
	}

	@Test
	public void passedHasNoButtons() {
		apply(LivenessEvent.of(Type.PASSED, 1));
		assertEquals(text("liveness.status.passed"), model.status());
		assertEquals(Tone.SUCCESS, model.tone());
		assertEquals(Icon.CHECK, model.icon());
		assertEquals(Terminal.PASSED, model.terminal());
		assertButtons(false, false, false);
		model.onComplete(result(LivenessResult.Decision.PASSED, null));
		assertEquals(Terminal.PASSED, model.terminal());
	}

	@Test
	public void cooldownRefusalWithoutEventsStillShowsTheFinalState() {
		model.onComplete(result(LivenessResult.Decision.FAILED, ErrorCode.MAX_RETRIES_EXCEEDED));
		assertEquals(text("liveness.error.max_attempts_reached"), model.status());
		assertButtons(false, false, true);
	}

	@Test
	public void cancelShowsNothingAndCloses() {
		apply(LivenessEvent.of(Type.CHECKING_PASSIVE, 1));
		model.onComplete(result(LivenessResult.Decision.CANCELLED, ErrorCode.CANCELLED));
		assertEquals(Terminal.CANCELLED, model.terminal());
		assertButtons(false, false, false);
	}

	@Test
	public void unavailableFailsClosedWithTheGenericText() {
		LivenessScreenModel operator = new LivenessScreenModel(WorkflowType.OPERATOR, 3);
		operator.showUnavailable();
		assertEquals(text("liveness.error.verification_not_completed"), operator.status());
		assertEquals(text("liveness.recovery.operator"), operator.hint());
		assertEquals(Terminal.FAILED, operator.terminal());
		assertTrue(operator.showClose());
	}

	@Test
	public void everyKeyTheModelCanProduceHasAnEnglishText() {
		List<Text> seen = new ArrayList<>();
		for (WorkflowType wf : WorkflowType.values()) {
			for (ErrorCode code : ErrorCode.values()) {
				for (Type type : new Type[] { Type.ATTEMPT_FAILED, Type.FAILED_FINAL, Type.DEVICE_ERROR }) {
					LivenessScreenModel m = new LivenessScreenModel(wf, 3);
					m.onEvent(LivenessEvent.failure(type, code, 1, 1, true), 0);
					collect(m, seen);
				}
			}
			LivenessScreenModel m = new LivenessScreenModel(wf, 3);
			collect(m, seen);
			long t = 0;
			for (Guidance g : Guidance.values()) {
				t += 1000;
				m.onEvent(LivenessEvent.guidance(g, 1), t);
				collect(m, seen);
			}
			for (ChallengeType c : ChallengeType.values()) {
				m.onEvent(LivenessEvent.prompt(c, 1, 2, 6000, 1), t);
				collect(m, seen);
				m.onEvent(LivenessEvent.progress(c, 1, 2, 0.5f, 1), t);
				collect(m, seen);
			}
			for (Type type : new Type[] { Type.STARTED, Type.CHECKING_PASSIVE, Type.ACTION_DETECTED, Type.HOLD_STILL,
					Type.PASSED }) {
				m.onEvent(LivenessEvent.of(type, 1), t);
				collect(m, seen);
			}
			m.showUnavailable();
			collect(m, seen);
		}
		for (Text text : seen) {
			assertTrue("missing English text for " + text.key, LivenessMessages.ENGLISH.containsKey(text.key));
		}
	}

	private static void collect(LivenessScreenModel m, List<Text> out) {
		for (Text t : new Text[] { m.title(), m.status(), m.hint(), m.step(), m.attempt() }) {
			if (t != null) {
				out.add(t);
			}
		}
	}

	private void assertButtons(boolean retry, boolean cancel, boolean close) {
		assertEquals("retry", retry, model.showRetry());
		assertEquals("cancel", cancel, model.showCancel());
		assertEquals("close", close, model.showClose());
	}

	private static LivenessResult result(LivenessResult.Decision d, ErrorCode code) {
		LivenessResult r = new LivenessResult(d,
				d == LivenessResult.Decision.PASSED ? LivenessResult.PassPath.PASSIVE_ONLY : LivenessResult.PassPath.NONE,
				code, null, 0.9f, Collections.<ChallengeType>emptyList(), 1, 1000, "s-1", "test");
		assertNotNull(r);
		return r;
	}
}
