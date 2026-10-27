package io.mosip.registration.controller.liveness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.mosip.registration.liveness.desktop.DesktopLivenessService.ModelBundleOutcome;

/**
 * F17: the message keys of the offline face liveness model install and rollback ({@code docs/ui-liveness-spec.md}
 * section 4.5), in the messages bundles. The texts are generic: never a path, a file name or an exception message.
 */
public final class ModelBundleTexts {

	/** Every key, in the spec's order. */
	public static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
			"liveness.model.title", "liveness.model.current", "liveness.model.install", "liveness.model.rollback",
			"liveness.model.select_file", "liveness.model.working", "liveness.model.installed",
			"liveness.model.rolled_back", "liveness.model.not_newer", "liveness.model.invalid",
			"liveness.model.nothing_to_roll_back", "liveness.model.failed", "liveness.model.rollback_failed",
			"liveness.model.not_allowed", "liveness.model.confirm_rollback"));

	private ModelBundleTexts() {
	}

	/** The message key for an outcome; a failed rollback has its own text (no file was involved). */
	public static String messageKey(ModelBundleOutcome outcome, boolean rollback) {
		if (rollback && (outcome == ModelBundleOutcome.INVALID || outcome == ModelBundleOutcome.FAILED)) {
			return "liveness.model.rollback_failed";
		}
		switch (outcome) {
		case INSTALLED:
			return "liveness.model.installed";
		case ROLLED_BACK:
			return "liveness.model.rolled_back";
		case NOT_NEWER:
			return "liveness.model.not_newer";
		case INVALID:
			return "liveness.model.invalid";
		case NOTHING_TO_ROLL_BACK:
			return "liveness.model.nothing_to_roll_back";
		case NOT_ALLOWED:
			return "liveness.model.not_allowed";
		default:
			return "liveness.model.failed";
		}
	}

	/** Puts the version into {0}: a literal replacement, not MessageFormat, because the translations contain apostrophes. */
	public static String fill(String text, String version) {
		return text.replace("{0}", version == null ? "" : version);
	}
}
