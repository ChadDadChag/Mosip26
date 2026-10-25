package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.Components;
import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.WorkflowType;

public class DesktopAuditSinkTest {

	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final DesktopAuditSink sink = new DesktopAuditSink(audit, Runnable::run);

	@Test
	public void everyEngineEventHasItsOwnDesktopEvent() {
		Set<String> ids = new HashSet<>();
		for (AuditSink.Event e : AuditSink.Event.values()) {
			AuditEvent mapped = DesktopAuditSink.map(e);
			assertTrue(mapped.getId(), mapped.getId().startsWith("REG-LIV-"));
			assertTrue("same name as on Android: " + e, mapped.getName().endsWith(e.name().replace("PAD_", "PAD_")));
			ids.add(mapped.getId());
		}
		assertEquals(AuditSink.Event.values().length, ids.size());
	}

	@Test
	public void recordsThroughTheAuditModuleWithTheSessionAsReference() {
		Map<String, String> d = new LinkedHashMap<>();
		d.put("modelVersion", "pad-2026.10.5");
		d.put("errorCode", "PAD_FAILURE");
		d.put("pad.minifasnet_v2", "0.12");
		d.put("passiveScore", "0.21");
		sink.record(AuditSink.Event.PAD_ATTACK_SUSPECTED, WorkflowType.RESIDENT, "sess-1", d);

		RecordingAuditManager.Call c = audit.last();
		assertEquals(AuditEvent.LIVENESS_PAD_ATTACK_SUSPECTED, c.event);
		assertEquals(Components.REG_LIVENESS, c.component);
		assertEquals("sess-1", c.refId);
		assertEquals(AuditReferenceIdTypes.LIVENESS_SESSION_ID.getReferenceTypeId(), c.refIdType);
		assertEquals("workflow=RESIDENT; session=sess-1; errorCode=PAD_FAILURE; passiveScore=0.21; "
				+ "modelVersion=pad-2026.10.5; pad.minifasnet_v2=0.12", c.description);
	}

	@Test
	public void dropsAnythingThatLooksLikeImageDataOrBadKeys() {
		Map<String, String> d = new LinkedHashMap<>();
		StringBuilder blob = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			blob.append("iVBORw0KGgo");
		}
		d.put("image", blob.toString());
		d.put("bad key with spaces", "x");
		d.put("reason", "line1\nline2; injected=1");
		Map<String, String> clean = DesktopAuditSink.sanitize(d);
		assertFalse(clean.containsKey("image"));
		assertFalse(clean.containsKey("bad key with spaces"));
		assertEquals("line1 line2  injected 1", clean.get("reason"));
	}

	@Test
	public void longValuesAreCappedAndLongRecordsSplitIntoParts() {
		Map<String, String> d = new LinkedHashMap<>();
		for (int i = 0; i < 60; i++) {
			d.put("diag.k" + i, "value value value value value value " + i);
		}
		d.put("policy", String.join(",", java.util.Collections.nCopies(80, "x 1")));
		List<String> parts = DesktopAuditSink.describe(WorkflowType.OPERATOR, "s", d);
		assertTrue(parts.size() > 1);
		for (int i = 0; i < parts.size(); i++) {
			String p = parts.get(i);
			assertTrue(p.length() <= DesktopAuditSink.MAX_DESCRIPTION);
			assertTrue(p.startsWith("workflow=OPERATOR; session=s"));
			assertTrue(p.endsWith("part=" + (i + 1) + "/" + parts.size()));
		}
		assertTrue(DesktopAuditSink.sanitize(d).get("policy").length() <= DesktopAuditSink.MAX_VALUE_LENGTH + 3);
	}

	@Test
	public void anAuditFailureNeverReachesTheEngine() {
		audit.failWith = new IllegalStateException("db down");
		sink.record(AuditSink.Event.LIVENESS_STARTED, WorkflowType.SUPERVISOR, "s", null);
		assertTrue(audit.calls.isEmpty());
	}

	@Test
	public void desktopEventsUseTheSameFormat() {
		sink.record(AuditEvent.LIVENESS_FACE_BINDING, WorkflowType.OPERATOR, "s2",
				new LinkedHashMap<>(Map.of("outcome", "MATCH", "similarity", "0.71")));
		assertEquals(AuditEvent.LIVENESS_FACE_BINDING, audit.last().event);
		assertTrue(Arrays.asList(audit.last().description.split("; ")).contains("outcome=MATCH"));
	}
}
