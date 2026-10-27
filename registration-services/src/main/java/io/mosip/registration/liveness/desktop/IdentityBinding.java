package io.mosip.registration.liveness.desktop;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import io.mosip.kernel.biometrics.commons.CbeffValidator;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.dao.UserDetailDAO;
import io.mosip.registration.entity.UserBiometric;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.match.IdentityResult;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.WorkflowType;

/**
 * Identity binding (F15): after a liveness gate that no face capture follows (the password login), the live face must
 * be the user's own onboarded face.
 *
 * <p>F1 binds the live face to an SBI capture. A password login has no capture, so without this check liveness proves
 * only that some live person is at the machine. {@link #verify} takes the PASSED session's best frame (held by
 * {@link FaceBinding}) and compares it with the face templates the user onboarded on this machine ({@link
 * #fromUserBiometrics}): each decoded as F1 decodes a capture, then {@code FaceMatcher.identify}, the best of several
 * templates, with its own threshold ({@code mosip.registration.liveness.face.binding.identity.threshold}, provisional
 * 0.40).
 *
 * <p>F15 is a second factor after the password: a false match lets in someone who has the password AND a similar face.
 * <ul>
 * <li>MATCH: accepted.</li>
 * <li>NO_MATCH, NO_LIVE_FACE, NO_LIVE_FRAME, NOT_ARMED, EXPIRED, ERROR: rejected; {@link Verdict#retryable()} (one
 * new liveness session, then the gate is blocked).</li>
 * <li>NOT_BOUND_NO_TEMPLATE, NOT_BOUND_UNREADABLE (no onboarded face on this machine, or none decodes to one face):
 * accepted on presence alone, unless {@value DesktopLivenessConfig#IDENTITY_REQUIRE_TEMPLATE_KEY} is true.</li>
 * </ul>
 * Every decision is audited as LIVENESS_IDENTITY_BINDING (REG-LIV-013): codes, the similarity (2 decimals), the
 * threshold, template counts and, when known, the newest template's age in days. Images and templates stay in memory;
 * nothing is logged but that record.
 */
public class IdentityBinding {

	private static final Logger LOGGER = AppConfig.getLogger(IdentityBinding.class);

	/** The gate names used in the audit. */
	public static final String GATE_PASSWORD_LOGIN = "password_login";
	/** The development screen demo's check on a test template (never the client). */
	public static final String GATE_DIAGNOSTICS = "diagnostics";

	private static final long DAY_MS = 24L * 60 * 60 * 1000;

	/** One onboarded face: its ISO/IEC 19794-5 record and when it was stored (epoch ms, null when unknown). */
	public static final class Template {
		final byte[] isoRecord;
		final Long storedAtMs;

		public Template(byte[] isoRecord, Long storedAtMs) {
			this.isoRecord = isoRecord;
			this.storedAtMs = storedAtMs;
		}
	}

	/** The user's onboarded faces. An empty list: none on this machine. */
	public interface TemplateSource {
		List<Template> load(String userId) throws Exception;
	}

	/** Compares the live face with the decoded templates (a null entry is an undecodable template). */
	public interface Identifier {
		Identification identify(Frame live, List<Frame> templates) throws Exception;
	}

	/** What {@link Identifier} found; holds no image or feature. */
	public static final class Identification {
		final String outcome;
		final double similarity;
		final double threshold;
		final int templates;
		final int unreadable;

		public Identification(String outcome, double similarity, double threshold, int templates, int unreadable) {
			this.outcome = outcome;
			this.similarity = similarity;
			this.threshold = threshold;
			this.templates = templates;
			this.unreadable = unreadable;
		}
	}

	/** The decision for one gate. */
	public static final class Verdict {
		public enum Kind {
			/** Liveness is off for this workflow: the legacy flow, nothing to bind. */
			LIVENESS_DISABLED,
			/** Identity binding is switched off by config, or the scripted engine runs (audited SKIPPED). */
			DISABLED,
			MATCH,
			/** No readable onboarded face, and templates are not required: accepted on presence alone. */
			NOT_BOUND,
			REJECTED
		}

		public final Kind kind;
		/** Outcome for audit and logs: MATCH, NO_MATCH, NOT_BOUND_NO_TEMPLATE, NOT_BOUND_UNREADABLE, ERROR, ... */
		public final String reason;
		/** Only for the development screen demo, which shows it; the client never shows a score. */
		public final double similarity;

		Verdict(Kind kind, String reason, double similarity) {
			this.kind = kind;
			this.reason = reason;
			this.similarity = similarity;
		}

		/** Whether the user may go on. */
		public boolean accepted() {
			return kind != Kind.REJECTED;
		}

		/** Whether one more liveness session may help: not when the user has no readable onboarded face. */
		public boolean retryable() {
			return kind == Kind.REJECTED && !reason.startsWith("NOT_BOUND");
		}

		@Override
		public String toString() {
			return kind + "(" + reason + ")";
		}
	}

	private final Predicate<WorkflowType> livenessEnabled;
	private final DesktopLivenessConfig config;
	private final FaceBinding binding;
	private final Identifier identifier;
	private final TemplateSource templates;
	private final CapturedFaceDecoder decoder;
	private final DesktopAuditSink audit;
	private final LongSupplier clock;
	private final boolean scriptedEngine;

	/**
	 * The client builds one in {@link DesktopLivenessService}; the development screen demo builds one on a test template
	 * (binding null: it passes the live face itself to {@link #verifyLive}).
	 */
	public IdentityBinding(Predicate<WorkflowType> livenessEnabled, DesktopLivenessConfig config, FaceBinding binding,
			Identifier identifier, TemplateSource templates, DesktopAuditSink audit, boolean scriptedEngine) {
		this(livenessEnabled, config, binding, identifier, templates, new CapturedFaceDecoder(), audit,
				System::currentTimeMillis, scriptedEngine);
	}

	IdentityBinding(Predicate<WorkflowType> livenessEnabled, DesktopLivenessConfig config, FaceBinding binding,
			Identifier identifier, TemplateSource templates, CapturedFaceDecoder decoder, DesktopAuditSink audit,
			LongSupplier clock, boolean scriptedEngine) {
		this.livenessEnabled = livenessEnabled;
		this.config = config;
		this.binding = binding;
		this.identifier = identifier;
		this.templates = templates;
		this.decoder = decoder;
		this.audit = audit;
		this.clock = clock;
		this.scriptedEngine = scriptedEngine;
	}

	/** The production identifier: a matcher on the current model bundle, closed after the call. */
	public static Identifier onnx(Callable<FaceMatcher> matchers) {
		return (live, faces) -> {
			try (FaceMatcher matcher = matchers.call()) {
				IdentityResult r = matcher.identify(live, faces);
				return new Identification(r.outcome.name(), r.similarity, r.threshold, r.templates, r.unreadable);
			}
		};
	}

	/**
	 * The client's templates: the user's active Face rows of {@code reg.user_biometric}, written by operator onboarding
	 * on this machine. Each row's BDB comes from the CBEFF BIR in {@code bio_raw_image}, else from {@code bio_iso_image},
	 * as {@code AuthenticationServiceImpl.authValidator} reads them. The age comes from the row's creation time (UTC).
	 */
	public static TemplateSource fromUserBiometrics(UserDetailDAO users) {
		return userId -> {
			List<Template> out = new ArrayList<>();
			if (userId == null || users == null) {
				return out;
			}
			List<UserBiometric> rows = users.getUserSpecificBioDetails(userId, "Face");
			if (rows == null) {
				return out;
			}
			for (UserBiometric row : rows) {
				out.add(new Template(bdb(row), row.getCrDtime() == null ? null
						: row.getCrDtime().toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli()));
			}
			return out;
		};
	}

	static byte[] bdb(UserBiometric row) {
		if (row.getBioRawImage() != null && row.getBioRawImage().length > 0) {
			try {
				BIR bir = CbeffValidator.getBIRFromXML(row.getBioRawImage());
				if (bir != null && bir.getBirs() != null && !bir.getBirs().isEmpty()
						&& bir.getBirs().get(0).getBdb() != null) {
					return bir.getBirs().get(0).getBdb();
				}
			} catch (Exception e) {
				// Rows stored before 1.2.0.1-B2 hold no BIR here; the ISO column has the BDB.
			}
		}
		return row.getBioIsoImage();
	}

	/**
	 * The check after a PASSED gate of {@code workflow} for {@code userId}: consumes the arming of {@link FaceBinding}.
	 * Call it on a background thread: loading, decoding and matching take a few hundred ms.
	 *
	 * @param attempt 1 for the first liveness session of this gate, 2 for the retry (audited)
	 */
	public Verdict verify(WorkflowType workflow, String userId, String gate, int attempt) {
		FaceBinding.Live live = binding.takeLive(workflow);
		return check(workflow, userId, gate, attempt, live.sessionId, live.frame, live.problem);
	}

	/** The same check on a live face the caller holds (the development screen demo). */
	public Verdict verifyLive(WorkflowType workflow, String userId, String gate, int attempt, String sessionId,
			Frame live) {
		return check(workflow, userId, gate, attempt, sessionId, live, live == null ? "NO_LIVE_FRAME" : null);
	}

	private Verdict check(WorkflowType workflow, String userId, String gate, int attempt, String sessionId, Frame live,
			String problem) {
		if (!livenessEnabled.test(workflow)) {
			return new Verdict(Verdict.Kind.LIVENESS_DISABLED, "LIVENESS_DISABLED", Double.NaN);
		}
		Map<String, String> d = details(userId, gate, attempt);
		// Only false/no/0/off switches F15 off: any other value keeps it on, with a warning.
		if (!config.getSwitch(DesktopLivenessConfig.IDENTITY_ENABLED_KEY, true)) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.DISABLED, "SKIPPED", Double.NaN), d);
		}
		if (problem != null) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, problem, Double.NaN), d);
		}
		if (scriptedEngine) {
			// Development only (JVM property): its best frame is synthetic, there is no live face to compare.
			return finish(workflow, sessionId,
					new Verdict(Verdict.Kind.DISABLED, "SKIPPED_SCRIPTED_ENGINE", Double.NaN), d);
		}
		Identification id;
		try {
			List<Template> stored = templates.load(userId);
			List<Frame> faces = new ArrayList<>();
			Long newest = null;
			for (Template t : stored) {
				faces.add(decoder.decode(t.isoRecord));
				if (t.storedAtMs != null && (newest == null || t.storedAtMs > newest)) {
					newest = t.storedAtMs;
				}
			}
			if (newest != null) {
				d.put("templateAgeDays", String.valueOf(Math.max(0L, (clock.getAsLong() - newest) / DAY_MS)));
			}
			id = identifier.identify(live, faces);
		} catch (Exception e) {
			LOGGER.error("Identity binding failed: {}", e.getClass().getSimpleName());
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, "ERROR", Double.NaN), d);
		}
		d.put("similarity", Double.isNaN(id.similarity) ? "NaN" : String.format(Locale.ROOT, "%.2f", id.similarity));
		d.put("threshold", String.format(Locale.ROOT, "%.2f", id.threshold));
		d.put("templates", String.valueOf(id.templates));
		d.put("unreadable", String.valueOf(id.unreadable));
		Verdict.Kind kind;
		if ("MATCH".equals(id.outcome)) {
			kind = Verdict.Kind.MATCH;
		} else if (id.outcome.startsWith("NOT_BOUND")) {
			boolean required = config.getSwitch(DesktopLivenessConfig.IDENTITY_REQUIRE_TEMPLATE_KEY, false);
			d.put("requireTemplate", String.valueOf(required));
			kind = required ? Verdict.Kind.REJECTED : Verdict.Kind.NOT_BOUND;
		} else {
			kind = Verdict.Kind.REJECTED;
		}
		return finish(workflow, sessionId, new Verdict(kind, id.outcome, id.similarity), d);
	}

	private static Map<String, String> details(String userId, String gate, int attempt) {
		Map<String, String> d = new LinkedHashMap<>();
		d.put("gate", gate);
		d.put("attempt", String.valueOf(attempt));
		d.put("userId", userId == null ? "NA" : userId);
		return d;
	}

	private Verdict finish(WorkflowType workflow, String sessionId, Verdict verdict, Map<String, String> d) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("outcome", verdict.reason);
		details.putAll(d);
		details.put("accepted", String.valueOf(verdict.accepted()));
		audit.record(AuditEvent.LIVENESS_IDENTITY_BINDING, workflow, sessionId, details);
		return verdict;
	}
}
