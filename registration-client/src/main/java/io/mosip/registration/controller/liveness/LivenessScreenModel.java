package io.mosip.registration.controller.liveness;

import java.util.Locale;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

/**
 * What the liveness screen shows, as message keys and flags, derived only from the engine's events: the state table
 * of {@code docs/ui-liveness-spec.md} §3, shared with the Android client. No JavaFX here, so every state is unit
 * tested; {@link LivenessView} renders it.
 *
 * <p>The model never sees a score, a threshold, a model name or a PAD detail (spec §1): PAD failures arrive as
 * {@code ErrorCode.PAD_FAILURE}, whose {@code userMessage()} is the generic VERIFICATION_NOT_COMPLETED.
 *
 * <p>Not thread-safe: call it on the JavaFX thread only.
 */
public final class LivenessScreenModel {

	/** Oval colour. Always rendered together with an {@link Icon}, never alone. */
	public enum Tone { NEUTRAL, CHECKING, CHALLENGE, DETECTED, SUCCESS, FAILURE }

	public enum Icon { CAMERA, SCANNING, EYE, SMILE, ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN, CHECK, CROSS, CAMERA_OFF }

	public enum ProgressMode { HIDDEN, INDETERMINATE, DETERMINATE }

	/** How the session ended, as far as the screen is concerned. */
	public enum Terminal { NONE, PASSED, FAILED, CANCELLED }

	/** A message key with its MessageFormat arguments. */
	public static final class Text {
		public final String key;
		public final Object[] args;

		Text(String key, Object... args) {
			this.key = key;
			this.args = args;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Text && ((Text) o).key.equals(key) && java.util.Arrays.equals(((Text) o).args, args);
		}

		@Override
		public int hashCode() {
			return key.hashCode();
		}

		@Override
		public String toString() {
			return args.length == 0 ? key : key + java.util.Arrays.toString(args);
		}
	}

	static final long HINT_DEBOUNCE_MS = 500;

	private final WorkflowType workflow;
	private final int maxAttempts;

	private Text status;
	private Text hint;
	private boolean hintIsGuidance;
	private Tone tone = Tone.NEUTRAL;
	private Icon icon = Icon.CAMERA;
	private ProgressMode progressMode = ProgressMode.HIDDEN;
	private double progress;
	private Text step;
	private Text attempt;
	private boolean tips;
	private boolean retry;
	private boolean cancel = true;
	private boolean close;
	private Terminal terminal = Terminal.NONE;
	private Text announcement;
	private long lastGuidanceShownMs = Long.MIN_VALUE / 2;

	/**
	 * @param maxRetries {@code policy(workflow).maxRetries}: the screen shows "Attempt a of maxRetries + 1"
	 */
	public LivenessScreenModel(WorkflowType workflow, int maxRetries) {
		this.workflow = workflow;
		this.maxAttempts = Math.max(1, maxRetries + 1);
		this.status = new Text("liveness.status.starting");
		this.attempt = new Text("liveness.progress.attempt", 1, maxAttempts);
	}

	/** Applies one engine event. {@code nowMs} drives the hint debounce. */
	public void onEvent(LivenessEvent e, long nowMs) {
		announcement = null;
		if (e.attemptNumber > 0 && terminal == Terminal.NONE) {
			attempt = new Text("liveness.progress.attempt", e.attemptNumber, maxAttempts);
		}
		switch (e.type) {
			case STARTED:
				setStatus(new Text("liveness.status.starting"));
				clearHint();
				look(Tone.NEUTRAL, Icon.CAMERA, ProgressMode.HIDDEN, 0);
				step = null;
				buttons(false, true, false);
				tips = false;
				break;
			case CHECKING_PASSIVE:
				setStatus(new Text("liveness.status.checking"));
				clearHint();
				look(Tone.CHECKING, Icon.SCANNING, ProgressMode.INDETERMINATE, 0);
				break;
			case GUIDANCE:
				if (e.guidance != null) {
					showGuidance(new Text("liveness.guidance." + lower(e.guidance)), nowMs);
				}
				break;
			case CHALLENGE_PROMPT:
				setStatus(new Text(challengeKey(e.challenge)));
				clearHint();
				look(Tone.CHALLENGE, challengeIcon(e.challenge), ProgressMode.DETERMINATE, 0);
				step = e.challengeCount > 0 ? new Text("liveness.progress.step", e.challengeIndex, e.challengeCount)
						: null;
				break;
			case CHALLENGE_PROGRESS:
				// The prompt stays; "Please continue" replaces (and clears) any positioning hint.
				setHint(new Text("liveness.feedback.continue"), false);
				progressMode = ProgressMode.DETERMINATE;
				progress = clamp(e.progress);
				break;
			case ACTION_DETECTED:
				setStatus(new Text("liveness.feedback.action_detected"));
				clearHint();
				look(Tone.DETECTED, Icon.CHECK, ProgressMode.DETERMINATE, 1);
				break;
			case HOLD_STILL:
				setStatus(new Text("liveness.feedback.hold_still"));
				clearHint();
				look(Tone.CHECKING, Icon.SCANNING, ProgressMode.INDETERMINATE, 0);
				step = null;
				break;
			case ATTEMPT_FAILED:
				setStatus(errorText(e.error));
				setHint(new Text("liveness.retry.attempts_left", e.retriesLeft), false);
				look(Tone.FAILURE, Icon.CROSS, ProgressMode.HIDDEN, 0);
				step = null;
				tips = e.error != null && e.error.userMessage() == ErrorCode.UserMessage.LIVENESS_NOT_VERIFIED;
				buttons(true, true, false);
				break;
			case DEVICE_ERROR:
				setStatus(new Text("liveness.error.device_problem"));
				clearHint();
				look(Tone.FAILURE, Icon.CAMERA_OFF, ProgressMode.HIDDEN, 0);
				step = null;
				tips = false;
				buttons(e.recoverable, true, false);
				break;
			case PASSED:
				showPassed();
				break;
			case FAILED_FINAL:
				showFinal(e.error);
				break;
			default:
				break;
		}
	}

	/** Applies the session result (exactly once). */
	public void onComplete(LivenessResult result) {
		announcement = null;
		switch (result.decision) {
			case PASSED:
				if (terminal != Terminal.PASSED) {
					showPassed();
				}
				break;
			case FAILED:
				if (terminal != Terminal.FAILED) {
					showFinal(result.failureCode);
				}
				break;
			default:
				terminal = Terminal.CANCELLED; // the screen closes; nothing is shown (UserMessage.NONE)
				buttons(false, false, false);
				break;
		}
	}

	/**
	 * Fail-closed state for errors outside the engine (for example the engine could not be created): the generic
	 * text, the workflow's recovery guidance and Close.
	 */
	public void showUnavailable() {
		announcement = null;
		terminal = Terminal.FAILED;
		setStatus(new Text("liveness.error.verification_not_completed"));
		setHint(new Text("liveness.recovery." + lower(workflow)), false);
		look(Tone.FAILURE, Icon.CROSS, ProgressMode.HIDDEN, 0);
		step = null;
		tips = false;
		buttons(false, false, true);
	}

	private void showPassed() {
		terminal = Terminal.PASSED;
		setStatus(new Text("liveness.status.passed"));
		clearHint();
		look(Tone.SUCCESS, Icon.CHECK, ProgressMode.DETERMINATE, 1);
		step = null;
		tips = false;
		buttons(false, false, false);
	}

	private void showFinal(ErrorCode error) {
		terminal = Terminal.FAILED;
		boolean device = error != null && error.userMessage() == ErrorCode.UserMessage.DEVICE_PROBLEM;
		// The flow cannot try again from here, so no "Please try again" text: see spec §3 notes.
		setStatus(new Text(device ? "liveness.error.device_problem" : "liveness.error.max_attempts_reached"));
		setHint(new Text("liveness.recovery." + lower(workflow)), false);
		look(Tone.FAILURE, device ? Icon.CAMERA_OFF : Icon.CROSS, ProgressMode.HIDDEN, 0);
		step = null;
		tips = false;
		buttons(false, false, true);
	}

	private void showGuidance(Text text, long nowMs) {
		if (text.equals(hint) || nowMs - lastGuidanceShownMs < HINT_DEBOUNCE_MS) {
			return;
		}
		lastGuidanceShownMs = nowMs;
		setHint(text, true);
	}

	private void setStatus(Text text) {
		if (!text.equals(status)) {
			announcement = text;
		}
		status = text;
	}

	private void setHint(Text text, boolean guidance) {
		if (!text.equals(hint) && announcement == null) {
			announcement = text;
		}
		hint = text;
		hintIsGuidance = guidance;
	}

	private void clearHint() {
		hint = null;
		hintIsGuidance = false;
	}

	private void look(Tone t, Icon i, ProgressMode mode, double value) {
		tone = t;
		icon = i;
		progressMode = mode;
		progress = value;
	}

	private void buttons(boolean retry, boolean cancel, boolean close) {
		this.retry = retry;
		this.cancel = cancel;
		this.close = close;
	}

	static Text errorText(ErrorCode error) {
		ErrorCode.UserMessage m = error == null ? ErrorCode.UserMessage.VERIFICATION_NOT_COMPLETED : error.userMessage();
		if (m == ErrorCode.UserMessage.NONE) {
			m = ErrorCode.UserMessage.VERIFICATION_NOT_COMPLETED;
		}
		return new Text("liveness.error." + lower(m));
	}

	static String challengeKey(ChallengeType c) {
		return c == null ? "liveness.feedback.continue" : "liveness.challenge." + lower(c);
	}

	static Icon challengeIcon(ChallengeType c) {
		if (c == null) {
			return Icon.SCANNING;
		}
		switch (c) {
			case BLINK: return Icon.EYE;
			case SMILE: return Icon.SMILE;
			case TURN_LEFT: return Icon.ARROW_LEFT;
			case TURN_RIGHT: return Icon.ARROW_RIGHT;
			case LOOK_UP: return Icon.ARROW_UP;
			case LOOK_DOWN: return Icon.ARROW_DOWN;
			default: return Icon.SCANNING;
		}
	}

	private static String lower(Enum<?> e) {
		return e.name().toLowerCase(Locale.ROOT);
	}

	private static double clamp(float p) {
		return Float.isNaN(p) ? 0 : Math.max(0, Math.min(1, p));
	}

	// ------------------------------------------------------------------ read side

	public WorkflowType workflow() { return workflow; }
	public Text title() { return new Text("liveness.title." + lower(workflow)); }
	public Text status() { return status; }
	/** Null when the hint line is empty. */
	public Text hint() { return hint; }
	/** The hint is positioning guidance: show the warning icon beside it. */
	public boolean hintIsGuidance() { return hintIsGuidance; }
	public Tone tone() { return tone; }
	public Icon icon() { return icon; }
	public ProgressMode progressMode() { return progressMode; }
	public double progress() { return progress; }
	/** "Step i of n" during challenges, else null. */
	public Text step() { return step; }
	/** "Attempt a of m"; null after the session ended. */
	public Text attempt() { return terminal == Terminal.NONE ? attempt : null; }
	public boolean showTips() { return tips; }
	public boolean showRetry() { return retry; }
	public boolean showCancel() { return cancel; }
	public boolean showClose() { return close; }
	public Terminal terminal() { return terminal; }
	/** What changed for a screen reader after the last call, or null (progress ticks are never announced). */
	public Text announcement() { return announcement; }
}
