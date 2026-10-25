package io.mosip.registration.liveness.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.Components;
import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.WorkflowType;

/**
 * Writes the engine's audit records through the client's existing audit module ({@link AuditManagerService}, table
 * {@code AUDIT.APP_AUDIT_LOG}), as events {@code REG-LIV-001..011}.
 *
 * <p>The engine's details (error codes, scores, PAD reasons, challenge outcomes, timings, model version, device id)
 * go into the description column as {@code key=value; ...}, with the workflow and the liveness session id first. The
 * reference id is the session id, which links the audit trail, the app log and the metrics. A record longer than the
 * column is split into numbered parts.
 *
 * <p>Privacy: the engine never passes images or landmarks here, and this class enforces it again: keys must be short
 * identifiers, values are capped at {@value #MAX_VALUE_LENGTH} characters, and anything that looks like encoded
 * binary data is dropped.
 *
 * <p>Records are written on a single background thread so that the engine's threads never wait for the database.
 */
public class DesktopAuditSink implements AuditSink {

	private static final Logger LOGGER = AppConfig.getLogger(DesktopAuditSink.class);

	static final int MAX_DESCRIPTION = 2000;
	static final int MAX_VALUE_LENGTH = 400; // the CONFIG_LOADED policy summary is about 350 characters
	private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{1,64}");
	private static final Pattern ENCODED_BLOB = Pattern.compile("[A-Za-z0-9+/=_\\-]{120,}");
	/** Shown first, in this order, when present; the rest follow alphabetically. */
	private static final List<String> FIRST_KEYS = Arrays.asList("errorCode", "outcome", "reason", "passPath",
			"passiveOutcome", "passiveScore", "similarity", "threshold", "challenges", "attempt", "retriesLeft",
			"recoverable", "durationMs", "attemptMs", "modelVersion", "engine", "deviceId", "deviceCertification");

	private final AuditManagerService auditManagerService;
	private final Executor writer;

	public DesktopAuditSink(AuditManagerService auditManagerService, Executor writer) {
		this.auditManagerService = auditManagerService;
		this.writer = writer;
	}

	@Override
	public void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details) {
		record(map(event), workflow, sessionId, details);
	}

	/** For the Desktop's own events (face binding, metrics, model loading). */
	public void record(final AuditEvent event, WorkflowType workflow, final String sessionId,
			Map<String, String> details) {
		final List<String> parts = describe(workflow, sessionId, details);
		LOGGER.info("{} {}", event.getName(), parts.get(0));
		writer.execute(() -> {
			for (String part : parts) {
				try {
					auditManagerService.audit(event, Components.REG_LIVENESS, sessionId == null ? "NA" : sessionId,
							AuditReferenceIdTypes.LIVENESS_SESSION_ID.getReferenceTypeId(), part);
				} catch (RuntimeException e) {
					// Auditing must never break a capture or a login; the app log above keeps the record.
					LOGGER.error("Could not write liveness audit record {}: {}", event.getName(), e.getMessage());
				}
			}
		});
	}

	static AuditEvent map(Event event) {
		switch (event) {
			case LIVENESS_STARTED: return AuditEvent.LIVENESS_STARTED;
			case LIVENESS_PASSED: return AuditEvent.LIVENESS_PASSED;
			case LIVENESS_ATTEMPT_FAILED: return AuditEvent.LIVENESS_ATTEMPT_FAILED;
			case LIVENESS_FAILED_FINAL: return AuditEvent.LIVENESS_FAILED_FINAL;
			case PAD_ATTACK_SUSPECTED: return AuditEvent.LIVENESS_PAD_ATTACK_SUSPECTED;
			case DEVICE_ERROR: return AuditEvent.LIVENESS_DEVICE_ERROR;
			case MODEL_LOADED: return AuditEvent.LIVENESS_MODEL_LOADED;
			case MODEL_INTEGRITY_FAILED: return AuditEvent.LIVENESS_MODEL_INTEGRITY_FAILED;
			case CONFIG_LOADED: return AuditEvent.LIVENESS_CONFIG_LOADED;
			default: throw new IllegalArgumentException("Unmapped liveness audit event " + event);
		}
	}

	/** {@code workflow=...; session=...; key=value; ...}, split into parts of at most {@value #MAX_DESCRIPTION}. */
	static List<String> describe(WorkflowType workflow, String sessionId, Map<String, String> details) {
		Map<String, String> ordered = new LinkedHashMap<>();
		Map<String, String> clean = sanitize(details);
		for (String k : FIRST_KEYS) {
			if (clean.containsKey(k)) {
				ordered.put(k, clean.remove(k));
			}
		}
		ordered.putAll(new TreeMap<>(clean));

		String head = "workflow=" + (workflow == null ? "NONE" : workflow.name()) + "; session="
				+ (sessionId == null ? "NA" : sessionId);
		List<String> fields = new ArrayList<>();
		for (Map.Entry<String, String> e : ordered.entrySet()) {
			fields.add(e.getKey() + "=" + e.getValue());
		}

		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder(head);
		for (String f : fields) {
			if (current.length() + 2 + f.length() > MAX_DESCRIPTION - 16) { // room for "; part=n/m"
				parts.add(current.toString());
				current = new StringBuilder(head);
			}
			current.append("; ").append(f);
		}
		parts.add(current.toString());
		if (parts.size() > 1) {
			for (int i = 0; i < parts.size(); i++) {
				parts.set(i, parts.get(i) + "; part=" + (i + 1) + "/" + parts.size());
			}
		}
		return parts;
	}

	static Map<String, String> sanitize(Map<String, String> details) {
		Map<String, String> out = new LinkedHashMap<>();
		if (details == null) {
			return out;
		}
		for (Map.Entry<String, String> e : details.entrySet()) {
			String key = e.getKey();
			if (key == null || !KEY.matcher(key).matches()) {
				continue;
			}
			String value = e.getValue() == null ? "" : e.getValue().replaceAll("[\\r\\n;=]", " ").trim();
			if (ENCODED_BLOB.matcher(value).find()) {
				LOGGER.warn("Dropped liveness audit field {}: looks like encoded data", key);
				continue;
			}
			if (value.length() > MAX_VALUE_LENGTH) {
				value = value.substring(0, MAX_VALUE_LENGTH) + "...";
			}
			out.put(key, value);
		}
		return out;
	}
}
