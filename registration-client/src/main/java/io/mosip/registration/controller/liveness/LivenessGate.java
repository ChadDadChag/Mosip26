package io.mosip.registration.controller.liveness;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.desktop.DesktopLivenessConfig;
import io.mosip.registration.liveness.desktop.DesktopLivenessService;
import io.mosip.registration.liveness.desktop.IdentityBinding;
import io.mosip.registration.liveness.device.util.PreviewFrameSource;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * The one call every Desktop face flow makes before it captures or matches a face: resident face capture, operator
 * login and onboarding, packet (operator) authentication, and supervisor authentication (biometric-exception review
 * and end-of-day approval).
 *
 * <p>{@link #run} opens the liveness screen as a modal window over the calling screen, runs one
 * {@link LivenessSession} (with its retries) and reports one {@link Outcome}:
 * <ul>
 * <li>{@link Outcome#PROCEED}: the session PASSED (the face binding is now armed for the next capture), or liveness
 * is disabled for the workflow, in which case no screen opens and the legacy flow continues unchanged;</li>
 * <li>{@link Outcome#CANCELLED}: the user pressed Cancel or closed the window; nothing changes;</li>
 * <li>{@link Outcome#BLOCKED}: liveness failed; the screen already showed the failure and the recovery guidance.</li>
 * </ul>
 * It never throws: any error inside it ends as BLOCKED with the generic text (fail closed). The accept or reject
 * decision comes only from {@code onComplete(result)}; events only drive the screen.
 *
 * <p>The password login, which no face capture follows, uses {@link #runWithIdentity} instead (F15): after the
 * session PASSED, the live face must also be the user's onboarded face.
 */
@Component
public class LivenessGate {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessGate.class);

	/** The text when identity binding (F15) rejects the live face. Generic: never a score or a reason. */
	public static final String IDENTITY_NOT_VERIFIED_KEY = "liveness.error.identity_not_verified";
	/** Liveness sessions per identity gate: the first, and one retry after a rejection. */
	static final int IDENTITY_ATTEMPTS = 2;

	public enum Outcome {
		PROCEED, CANCELLED, BLOCKED,
		/** F15: liveness passed, but the live face was not verified as the user's onboarded face, after the retry. */
		IDENTITY_NOT_VERIFIED;

		public boolean proceed() {
			return this == PROCEED;
		}
	}

	/** What follows an identity check: go on, offer the one retry, or stop. */
	enum IdentityStep {
		PROCEED, RETRY, NOT_VERIFIED
	}

	@Autowired
	private DesktopLivenessService livenessService;

	/**
	 * Whether a successful password login must pass an OPERATOR liveness check before the operator gets in
	 * ({@link DesktopLivenessConfig#GATE_PASSWORD_LOGIN_KEY}, default true, as on Android).
	 */
	public boolean gatesPasswordLogin(Collection<String> remainingLoginModes) {
		return gatesPasswordLogin(
				livenessService.config().getBoolean(DesktopLivenessConfig.GATE_PASSWORD_LOGIN_KEY, true),
				remainingLoginModes);
	}

	/**
	 * Not when switched off, and not when a face login step still follows: that step runs its own liveness check
	 * (bound to the face it matches), so the operator is not asked twice.
	 */
	static boolean gatesPasswordLogin(boolean enabled, Collection<String> remainingLoginModes) {
		if (!enabled) {
			return false;
		}
		if (remainingLoginModes != null) {
			for (String mode : remainingLoginModes) {
				if (RegistrationConstants.FACE.equalsIgnoreCase(mode)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Runs the liveness step on the JavaFX thread and calls {@code done} once, on the JavaFX thread.
	 *
	 * @param owner the calling screen's window (the new window is modal to it), or null
	 */
	public void run(WorkflowType workflow, Window owner, Consumer<Outcome> done) {
		LivenessSession session;
		try {
			livenessService.binding().disarm();
			session = livenessService.newSession(workflow);
		} catch (RuntimeException e) {
			LOGGER.error("Face liveness could not start for {}: {}", workflow, e.getMessage());
			showUnavailable(workflow, owner, done);
			return;
		}
		if (session == null) {
			LOGGER.info("Face liveness is disabled for {}: legacy flow", workflow);
			done.accept(Outcome.PROCEED);
			return;
		}
		try {
			new Run(workflow, owner, session, done).start();
		} catch (Exception e) {
			LOGGER.error("Face liveness screen failed for {}: {}", workflow, e.getMessage());
			session.cancel();
			showUnavailable(workflow, owner, done);
		}
	}

	/**
	 * F15: {@link #run}, then identity binding for {@code userId} ({@link IdentityBinding}): the live face must be the
	 * user's face onboarded on this machine. A rejection shows {@value #IDENTITY_NOT_VERIFIED_KEY} with Retry, which
	 * runs one new liveness session; a second rejection, Cancel, or a rejection that a retry cannot fix ends as
	 * {@link Outcome#IDENTITY_NOT_VERIFIED}. The caller counts that as a failed login. {@code done} is called once, on
	 * the JavaFX thread.
	 */
	public void runWithIdentity(WorkflowType workflow, Window owner, String userId, Consumer<Outcome> done) {
		identityAttempt(workflow, owner, userId, 1, done);
	}

	private void identityAttempt(WorkflowType workflow, Window owner, String userId, int attempt,
			Consumer<Outcome> done) {
		run(workflow, owner, outcome -> {
			if (!outcome.proceed()) {
				done.accept(livenessEnded(outcome, attempt));
				return;
			}
			// Loading, decoding and matching take a few hundred ms: off the JavaFX thread.
			Thread worker = new Thread(() -> {
				IdentityBinding.Verdict v = null;
				try {
					v = livenessService.identity().verify(workflow, userId, IdentityBinding.GATE_PASSWORD_LOGIN, attempt);
				} catch (Throwable e) { // an Error too: the login must end refused, not wait for ever
					LOGGER.error("Identity binding could not run for {}: {}", workflow, e.getClass().getSimpleName());
				}
				final IdentityStep step = v == null ? (attempt < IDENTITY_ATTEMPTS ? IdentityStep.RETRY
						: IdentityStep.NOT_VERIFIED) : identityStep(v.accepted(), v.retryable(), attempt);
				LOGGER.info("Identity binding for {}: {} (attempt {})", workflow, v == null ? "ERROR" : v, attempt);
				Platform.runLater(() -> afterIdentity(step, workflow, owner, userId, attempt, done));
			}, "liveness-identity");
			worker.setDaemon(true);
			worker.start();
		});
	}

	/**
	 * The outcome when a liveness session of the identity step does not pass. The retry session follows a rejection,
	 * so however it ends (Cancel, the window's X, a failure), the login is {@link Outcome#IDENTITY_NOT_VERIFIED} and
	 * counts as a failed login: cancelling the retry must not hide the rejection.
	 */
	static Outcome livenessEnded(Outcome liveness, int attempt) {
		return attempt > 1 ? Outcome.IDENTITY_NOT_VERIFIED : liveness;
	}

	/** The step after an identity check: one retry, and only when a new liveness session can change the result. */
	static IdentityStep identityStep(boolean accepted, boolean retryable, int attempt) {
		if (accepted) {
			return IdentityStep.PROCEED;
		}
		return retryable && attempt < IDENTITY_ATTEMPTS ? IdentityStep.RETRY : IdentityStep.NOT_VERIFIED;
	}

	private void afterIdentity(IdentityStep step, WorkflowType workflow, Window owner, String userId, int attempt,
			Consumer<Outcome> done) {
		if (step == IdentityStep.PROCEED) {
			done.accept(Outcome.PROCEED);
			return;
		}
		if (step == IdentityStep.NOT_VERIFIED) {
			done.accept(Outcome.IDENTITY_NOT_VERIFIED);
			return;
		}
		try {
			LivenessMessages texts = LivenessMessages.forApplicationLanguage();
			ButtonType retry = new ButtonType(texts.text("liveness.button.retry"), ButtonBar.ButtonData.OK_DONE);
			ButtonType cancel = new ButtonType(texts.text("liveness.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
			Alert alert = new Alert(Alert.AlertType.WARNING, texts.text(IDENTITY_NOT_VERIFIED_KEY), retry, cancel);
			alert.setHeaderText(null);
			alert.setTitle(texts.text("liveness.title." + workflow.name().toLowerCase(Locale.ROOT)));
			if (owner != null) {
				alert.initOwner(owner);
			}
			Optional<ButtonType> choice = alert.showAndWait();
			if (choice.isPresent() && choice.get() == retry) {
				identityAttempt(workflow, owner, userId, attempt + 1, done);
				return;
			}
		} catch (RuntimeException e) {
			LOGGER.error("Identity binding retry could not be offered: {}", e.getClass().getSimpleName());
		}
		done.accept(Outcome.IDENTITY_NOT_VERIFIED);
	}

	/** The fail-closed screen: generic text, recovery guidance, Close. */
	private void showUnavailable(WorkflowType workflow, Window owner, Consumer<Outcome> done) {
		try {
			LivenessView view = LivenessView.load(LivenessMessages.forApplicationLanguage(), false);
			LivenessScreenModel model = new LivenessScreenModel(workflow, 0);
			model.showUnavailable();
			view.render(model);
			Stage stage = stage(owner, view);
			view.setOnClose(stage::close);
			stage.setOnHidden(e -> {
				view.dispose();
				done.accept(Outcome.BLOCKED);
			});
			stage.show();
		} catch (Exception e) {
			LOGGER.error("Face liveness failure screen could not be shown: {}", e.getMessage());
			done.accept(Outcome.BLOCKED);
		}
	}

	private static Stage stage(Window owner, LivenessView view) {
		Stage stage = new Stage();
		stage.initStyle(StageStyle.UTILITY);
		stage.initModality(owner == null ? Modality.APPLICATION_MODAL : Modality.WINDOW_MODAL);
		if (owner != null) {
			stage.initOwner(owner);
		}
		stage.setResizable(false);
		stage.setScene(new Scene(view.root()));
		return stage;
	}

	/** One gate run: one session, one window, one outcome. */
	private final class Run implements LivenessListener {
		private final WorkflowType workflow;
		private final Window owner;
		private final LivenessSession session;
		private final Consumer<Outcome> done;
		private final boolean diagnostic;
		private LivenessView view;
		private LivenessScreenModel model;
		private Stage stage;
		private Outcome outcome;
		private boolean autoLogoutWas;

		Run(WorkflowType workflow, Window owner, LivenessSession session, Consumer<Outcome> done) {
			this.workflow = workflow;
			this.owner = owner;
			this.session = session;
			this.done = done;
			this.diagnostic = livenessService.policyDiagnostic(workflow);
		}

		void start() throws Exception {
			model = new LivenessScreenModel(workflow, livenessService.policy(workflow).maxRetries);
			view = LivenessView.load(LivenessMessages.forApplicationLanguage(),
					livenessService.config().getBoolean(DesktopLivenessConfig.PREFIX + "desktop.animations", true));
			view.render(model);
			view.setOnRetry(session::retry);
			view.setOnCancel(session::cancel);
			view.setOnClose(() -> stage.close());

			stage = stage(owner, view);
			stage.setTitle(LivenessMessages.forApplicationLanguage().text(model.title()));
			stage.setOnCloseRequest(e -> {
				if (model.terminal() == LivenessScreenModel.Terminal.NONE) {
					e.consume(); // the window's X behaves like Cancel; the session closes the window
					session.cancel();
				}
			});
			stage.setOnHidden(e -> finish());

			FrameSource source;
			if (livenessService.isScriptedEngine()) {
				source = new PreviewFrameSource(livenessService.openFaceSource()); // never started by the fake
				view.showPreview(false, "Scripted liveness engine (development build): no camera preview");
			} else {
				PreviewFrameSource preview = new PreviewFrameSource(livenessService.openFaceSource());
				preview.addPreviewListener(view);
				source = preview;
			}
			autoLogoutWas = SessionContext.isAutoLogoutAllowed();
			SessionContext.setAutoLogout(false); // as during a biometric scan
			stage.show();
			if (diagnostic) {
				LOGGER.info("LIVENESS-DIAG session={} workflow={} start", session.sessionId(), workflow);
			}
			session.start(source, this);
		}

		@Override
		public void onEvent(LivenessEvent event) {
			if (diagnostic) {
				LOGGER.info("LIVENESS-DIAG session={} event={} challenge={} {}/{} guidance={} error={} recoverable={}",
						session.sessionId(), event.type, event.challenge, event.challengeIndex, event.challengeCount,
						event.guidance, event.error, event.recoverable);
			}
			final long now = System.currentTimeMillis();
			Platform.runLater(() -> {
				model.onEvent(event, now);
				view.render(model);
			});
		}

		@Override
		public void onComplete(LivenessResult result) {
			LOGGER.info("Face liveness {} for {}: {} after {} attempt(s), session {}", result.decision, workflow,
					result.failureCode == null ? result.passPath : result.failureCode, result.attempts,
					result.sessionId);
			livenessService.sessionCompleted(workflow, result);
			Platform.runLater(() -> {
				model.onComplete(result);
				view.render(model);
				switch (result.decision) {
					case PASSED:
						livenessService.binding().arm(workflow, result);
						outcome = Outcome.PROCEED;
						PauseTransition wait = new PauseTransition(Duration.millis(Math.max(0, livenessService
								.config().getLong(DesktopLivenessConfig.AUTO_CONTINUE_KEY, 1000L))));
						wait.setOnFinished(e -> stage.close());
						wait.play();
						break;
					case FAILED:
						outcome = Outcome.BLOCKED; // the screen shows the failure and recovery; Close returns
						break;
					default:
						outcome = Outcome.CANCELLED;
						stage.close();
						break;
				}
			});
		}

		private void finish() {
			view.dispose();
			SessionContext.setAutoLogout(autoLogoutWas);
			if (outcome == null) {
				// Closed before a result (should not happen: the X is routed to cancel). Fail closed.
				session.cancel();
				outcome = Outcome.CANCELLED;
			}
			if (outcome != Outcome.PROCEED) {
				livenessService.binding().disarm();
			}
			done.accept(outcome);
		}
	}
}
