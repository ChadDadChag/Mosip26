package io.mosip.registration.controller.liveness;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Function;

import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;

/**
 * Texts of the liveness screen, by the message keys shared with the Android client ({@code docs/ui-liveness-spec.md}
 * §4 and §5). Lookup: the application language's {@code messages_<lang>.properties}, then English, then the
 * built-in English texts below, so a language that has not translated the liveness keys yet still shows English
 * instead of failing.
 */
public final class LivenessMessages {

	/** The English catalogue, identical to the block in {@code messages_en.properties} (checked by a test). */
	public static final Map<String, String> ENGLISH;

	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("liveness.title.resident", "Face liveness check");
		m.put("liveness.title.operator", "Operator face verification");
		m.put("liveness.title.supervisor", "Supervisor face verification");
		m.put("liveness.status.starting", "Starting face liveness check…");
		m.put("liveness.status.checking", "Checking face liveness…");
		m.put("liveness.status.passed", "Good, face captured successfully");
		m.put("liveness.status.cancelled", "Face liveness check cancelled.");
		m.put("liveness.challenge.blink", "Please blink");
		m.put("liveness.challenge.smile", "Please smile");
		m.put("liveness.challenge.turn_left", "Please turn your head to the left");
		m.put("liveness.challenge.turn_right", "Please turn your head to the right");
		m.put("liveness.challenge.look_up", "Please look up");
		m.put("liveness.challenge.look_down", "Please look down");
		m.put("liveness.feedback.continue", "Please continue");
		m.put("liveness.feedback.action_detected", "Action detected");
		m.put("liveness.feedback.hold_still", "Hold still");
		m.put("liveness.guidance.no_face", "No face detected. Please look at the camera.");
		m.put("liveness.guidance.multiple_faces", "Only one person should be in view.");
		m.put("liveness.guidance.move_closer", "Please move closer.");
		m.put("liveness.guidance.move_back", "Please move back.");
		m.put("liveness.guidance.center_face", "Please center your face in the oval.");
		m.put("liveness.guidance.improve_lighting", "Please improve the lighting.");
		m.put("liveness.guidance.hold_steady", "Please hold steady.");
		m.put("liveness.guidance.look_at_camera", "Please look at the camera.");
		m.put("liveness.error.liveness_not_verified", "We could not verify face liveness. Please try again.");
		m.put("liveness.error.verification_not_completed", "Face verification could not be completed. Please try again.");
		m.put("liveness.error.max_attempts_reached", "Face verification could not be completed.");
		m.put("liveness.error.device_problem", "Camera/device not available. Check the connection and try again.");
		m.put("liveness.error.identity_not_verified",
				"Face verification could not be completed for this user. Please try again.");
		m.put("liveness.tips.title", "Tips:");
		m.put("liveness.tips.look_at_camera", "Look directly at the camera.");
		m.put("liveness.tips.face_visible", "Ensure your face is clearly visible.");
		m.put("liveness.tips.lighting", "Improve lighting.");
		m.put("liveness.tips.capture_area", "Keep your face within the capture area.");
		m.put("liveness.tips.follow_prompt", "Follow the prompted action.");
		m.put("liveness.retry.attempts_left", "{0} attempt(s) left");
		m.put("liveness.progress.step", "Step {0} of {1}");
		m.put("liveness.progress.attempt", "Attempt {0} of {1}");
		m.put("liveness.recovery.resident", "Please contact the supervisor. The resident can be registered using the biometric exception process.");
		m.put("liveness.recovery.operator", "Please contact your administrator or try again later.");
		m.put("liveness.recovery.supervisor", "Please contact your administrator.");
		m.put("liveness.button.retry", "Retry");
		m.put("liveness.button.cancel", "Cancel");
		m.put("liveness.button.close", "Close");
		m.put("liveness.button.open_settings", "Open settings");
		ENGLISH = Collections.unmodifiableMap(m);
	}

	/** The tips shown under LIVENESS_NOT_VERIFIED, in order. */
	public static final String[] TIP_KEYS = { "liveness.tips.look_at_camera", "liveness.tips.face_visible",
			"liveness.tips.lighting", "liveness.tips.capture_area", "liveness.tips.follow_prompt" };

	/** Languages written right to left (MOSIP's three-letter codes and the two-letter ones). */
	private static final Set<String> RIGHT_TO_LEFT = new HashSet<>(Arrays.asList("ara", "ar", "heb", "he", "fas",
			"fa", "urd", "ur"));

	private final Function<String, String> lookup;
	private final boolean rightToLeft;

	private LivenessMessages(Function<String, String> lookup, boolean rightToLeft) {
		this.lookup = lookup;
		this.rightToLeft = rightToLeft;
	}

	/** Texts in the client's current application language. */
	public static LivenessMessages forApplicationLanguage() {
		String lang = ApplicationContext.applicationLanguage();
		final ResourceBundle current = bundle(lang);
		final ResourceBundle english = bundle("eng");
		return new LivenessMessages(key -> {
			String v = find(current, key);
			return v != null ? v : find(english, key);
		}, isRightToLeft(lang));
	}

	/** Built-in English only (tests, the screen demo). */
	public static LivenessMessages english() {
		return new LivenessMessages(key -> null, false);
	}

	/** Texts from one bundle, English where it has none (tests, the screen demo in another language). */
	static LivenessMessages of(ResourceBundle bundle, String lang) {
		return new LivenessMessages(key -> find(bundle, key), isRightToLeft(lang));
	}

	static boolean isRightToLeft(String lang) {
		return lang != null && RIGHT_TO_LEFT.contains(lang.toLowerCase(Locale.ROOT));
	}

	/** The screen is mirrored for this language. */
	public boolean rightToLeft() {
		return rightToLeft;
	}

	public String text(LivenessScreenModel.Text t) {
		return t == null ? "" : text(t.key, t.args);
	}

	public String text(String key, Object... args) {
		String pattern = lookup.apply(key);
		if (pattern == null) {
			pattern = ENGLISH.get(key);
		}
		if (pattern == null) {
			return key;
		}
		if (args == null || args.length == 0) {
			return pattern;
		}
		return new MessageFormat(pattern, Locale.ROOT).format(args);
	}

	private static ResourceBundle bundle(String lang) {
		try {
			return lang == null ? null : ApplicationContext.getBundle(lang, RegistrationConstants.MESSAGES);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String find(ResourceBundle b, String key) {
		if (b == null) {
			return null;
		}
		try {
			return b.containsKey(key) ? b.getString(key) : null;
		} catch (MissingResourceException e) {
			return null;
		}
	}
}
