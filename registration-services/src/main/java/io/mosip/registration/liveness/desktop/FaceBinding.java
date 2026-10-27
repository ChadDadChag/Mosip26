package io.mosip.registration.liveness.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

/**
 * Face binding (F1): the face the SBI device captures, and that is then stored in the packet or matched against the
 * operator's or supervisor's template, must be the person who just passed liveness.
 *
 * <p>Liveness runs on the device's stream, but the face that counts comes from a separate {@code /capture}. Without
 * this check a live person could pass liveness and a photo, or another person, be captured. So a PASSED gate
 * {@link #arm arms} the binding with its best frame; the next face capture of that workflow {@link #verifyCapture
 * consumes} it and is accepted only on MATCH. One arming per capture, valid for {@link #ARM_TTL_MS}.
 *
 * <p>Fails closed: when liveness is enabled for the workflow, a capture with no (or an expired, or another
 * workflow's) arming, an undecodable capture, no face, several faces, a different person or any error is rejected.
 * Binding is not presentation attack detection (a photo of the same person matches), so it never replaces liveness.
 * Both images stay in memory and neither is logged; the audit gets the outcome, the similarity and the threshold.
 */
public class FaceBinding {

	private static final Logger LOGGER = AppConfig.getLogger(FaceBinding.class);

	static final long ARM_TTL_MS = 5 * 60_000L;

	/** Compares the live face with the captured face. */
	public interface Comparator {
		Comparison compare(Frame live, Frame captured) throws Exception;
	}

	/** What {@link Comparator} found; holds no image or feature. */
	public static final class Comparison {
		final String outcome;
		final double similarity;
		final double threshold;

		public Comparison(String outcome, double similarity, double threshold) {
			this.outcome = outcome;
			this.similarity = similarity;
			this.threshold = threshold;
		}

		boolean matched() {
			return "MATCH".equals(outcome);
		}
	}

	/** The decision for one capture. */
	public static final class Verdict {
		public enum Kind {
			/** Liveness is off for this workflow: the legacy flow, nothing to bind. */
			LIVENESS_DISABLED,
			/** Binding is switched off by config (audited SKIPPED). */
			BINDING_DISABLED,
			MATCH,
			REJECTED
		}

		public final Kind kind;
		/** Outcome for audit and logs: MATCH, NO_MATCH, NO_FACE_A/B, MULTIPLE_FACES_A/B, NOT_ARMED, ... */
		public final String reason;

		Verdict(Kind kind, String reason) {
			this.kind = kind;
			this.reason = reason;
		}

		/** Whether the capture may be stored or used for authentication. */
		public boolean accepted() {
			return kind != Kind.REJECTED;
		}

		@Override
		public String toString() {
			return kind + "(" + reason + ")";
		}
	}

	private static final class Armed {
		final WorkflowType workflow;
		final String sessionId;
		final Frame bestFrame;
		final long expiresAtMs;

		Armed(WorkflowType workflow, String sessionId, Frame bestFrame, long expiresAtMs) {
			this.workflow = workflow;
			this.sessionId = sessionId;
			this.bestFrame = bestFrame;
			this.expiresAtMs = expiresAtMs;
		}
	}

	private final Predicate<WorkflowType> livenessEnabled;
	private final DesktopLivenessConfig config;
	private final Comparator comparator;
	private final CapturedFaceDecoder decoder;
	private final DesktopAuditSink audit;
	private final LongSupplier clock;
	private final boolean scriptedEngine;
	private Armed armed;

	FaceBinding(Predicate<WorkflowType> livenessEnabled, DesktopLivenessConfig config, Comparator comparator,
			CapturedFaceDecoder decoder, DesktopAuditSink audit, LongSupplier clock, boolean scriptedEngine) {
		this.livenessEnabled = livenessEnabled;
		this.config = config;
		this.comparator = comparator;
		this.decoder = decoder;
		this.audit = audit;
		this.clock = clock;
		this.scriptedEngine = scriptedEngine;
	}

	/** Called by the liveness gate when a session PASSED; replaces any earlier arming. */
	public synchronized void arm(WorkflowType workflow, LivenessResult passed) {
		if (passed == null || !passed.passed()) {
			armed = null;
			return;
		}
		armed = new Armed(workflow, passed.sessionId, passed.bestFrame, clock.getAsLong() + ARM_TTL_MS);
	}

	/** Drops any arming (the gate was cancelled or failed, or the screen was left). */
	public synchronized void disarm() {
		armed = null;
	}

	/** The armed live face for a check without an SBI capture: the frame, or why there is none. */
	static final class Live {
		final String sessionId;
		final Frame frame;
		/** Null, NOT_ARMED (none, or another workflow's), EXPIRED or NO_LIVE_FRAME. */
		final String problem;

		Live(String sessionId, Frame frame, String problem) {
			this.sessionId = sessionId;
			this.frame = frame;
			this.problem = problem;
		}
	}

	/**
	 * F15: consumes the arming for identity binding ({@link IdentityBinding}), which has no SBI capture. Never null; a
	 * missing, other-workflow or expired arming comes back as a problem.
	 */
	synchronized Live takeLive(WorkflowType workflow) {
		Armed a = armed;
		armed = null;
		if (a == null || a.workflow != workflow) {
			return new Live(a == null ? null : a.sessionId, null, "NOT_ARMED");
		}
		if (clock.getAsLong() > a.expiresAtMs) {
			return new Live(a.sessionId, null, "EXPIRED");
		}
		return new Live(a.sessionId, a.bestFrame, a.bestFrame == null ? "NO_LIVE_FRAME" : null);
	}

	/**
	 * Decides whether a face capture of {@code workflow} may be used. Consumes the arming in every case. Call it on a
	 * background thread: decoding and matching take about 100 ms.
	 */
	public Verdict verifyCapture(WorkflowType workflow, List<BiometricsDto> captured) {
		Armed a;
		synchronized (this) {
			a = armed;
			armed = null;
		}
		if (!livenessEnabled.test(workflow)) {
			return new Verdict(Verdict.Kind.LIVENESS_DISABLED, "LIVENESS_DISABLED");
		}
		String sessionId = a == null ? null : a.sessionId;
		if (!config.getBoolean(DesktopLivenessConfig.BINDING_ENABLED_KEY, true)) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.BINDING_DISABLED, "SKIPPED"), null);
		}
		if (a == null || a.workflow != workflow) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, "NOT_ARMED"), null);
		}
		if (clock.getAsLong() > a.expiresAtMs) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, "EXPIRED"), null);
		}
		if (scriptedEngine) {
			// Development only (JVM property): its best frame is synthetic, there is no live face to compare.
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.BINDING_DISABLED, "SKIPPED_SCRIPTED_ENGINE"),
					null);
		}
		if (a.bestFrame == null) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, "NO_LIVE_FRAME"), null);
		}
		Frame face = decoder.decode(faceRecord(captured));
		if (face == null) {
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, "UNDECODABLE"), null);
		}
		try {
			Comparison c = comparator.compare(a.bestFrame, face);
			return finish(workflow, sessionId,
					new Verdict(c.matched() ? Verdict.Kind.MATCH : Verdict.Kind.REJECTED, c.outcome), c);
		} catch (Exception e) {
			LOGGER.error("Face binding failed: {}", e.getClass().getSimpleName());
			return finish(workflow, sessionId, new Verdict(Verdict.Kind.REJECTED, "ERROR"), null);
		}
	}

	private static byte[] faceRecord(List<BiometricsDto> captured) {
		if (captured == null) {
			return null;
		}
		for (BiometricsDto dto : captured) {
			if (dto != null && dto.getAttributeISO() != null) {
				return dto.getAttributeISO();
			}
		}
		return null;
	}

	private Verdict finish(WorkflowType workflow, String sessionId, Verdict verdict, Comparison c) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("outcome", verdict.reason);
		if (c != null) {
			details.put("similarity", Double.isNaN(c.similarity) ? "NaN"
					: String.format(Locale.ROOT, "%.2f", c.similarity));
			details.put("threshold", String.format(Locale.ROOT, "%.2f", c.threshold));
		}
		details.put("accepted", String.valueOf(verdict.accepted()));
		audit.record(AuditEvent.LIVENESS_FACE_BINDING, workflow, sessionId, details);
		return verdict;
	}
}
