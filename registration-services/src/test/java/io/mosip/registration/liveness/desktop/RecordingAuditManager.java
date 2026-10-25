package io.mosip.registration.liveness.desktop;

import java.util.ArrayList;
import java.util.List;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.dto.ResponseDTO;

/** Keeps every audit call in memory. */
class RecordingAuditManager implements AuditManagerService {

	static final class Call {
		final AuditEvent event;
		final Components component;
		final String refId;
		final String refIdType;
		final String description;

		Call(AuditEvent event, Components component, String refId, String refIdType, String description) {
			this.event = event;
			this.component = component;
			this.refId = refId;
			this.refIdType = refIdType;
			this.description = description;
		}
	}

	final List<Call> calls = new ArrayList<>();
	RuntimeException failWith;

	@Override
	public void audit(AuditEvent e, Components c, String refId, String refIdType) {
		audit(e, c, refId, refIdType, null);
	}

	@Override
	public synchronized void audit(AuditEvent e, Components c, String refId, String refIdType, String description) {
		if (failWith != null) {
			throw failWith;
		}
		calls.add(new Call(e, c, refId, refIdType, description));
	}

	@Override
	public ResponseDTO deleteAuditLogs() {
		return null;
	}

	Call last() {
		return calls.get(calls.size() - 1);
	}

	synchronized List<AuditEvent> events() {
		List<AuditEvent> events = new ArrayList<>();
		for (Call c : calls) {
			events.add(c.event);
		}
		return events;
	}

	/** Waits for an {@code event} record whose description contains {@code text} (the sink writes in the background). */
	String await(AuditEvent event, String text) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 90_000;
		while (System.currentTimeMillis() < deadline) {
			synchronized (this) {
				for (Call c : calls) {
					if (c.event == event && c.description != null && c.description.contains(text)) {
						return c.description;
					}
				}
			}
			Thread.sleep(50);
		}
		throw new AssertionError("no " + event + " audit with '" + text + "' in " + events());
	}
}
