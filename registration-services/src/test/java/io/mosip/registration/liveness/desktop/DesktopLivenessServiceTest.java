package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Test;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.device.LivenessTestSupport;

/**
 * The Desktop wiring end to end: the real engine and the signed ONNX models, a recorded clip through the deferred
 * frame source, the synced config and the client's audit module. No SBI device and no Spring context needed.
 */
public class DesktopLivenessServiceTest {

	private static final String NO_FACE_CLIP = "/liveness/videos/no-face-blue-screen__expect-FAILED-FACE_NOT_DETECTED.mjpeg";

	private final Map<String, Object> synced = new HashMap<>();
	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final DesktopLivenessService service = new DesktopLivenessService(
			new DesktopLivenessConfig(() -> synced, DesktopLivenessConfig.loadDefaults()), audit, null, null);

	@After
	public void clearJvmProperties() {
		System.clearProperty(DesktopLivenessConfig.SOURCE_KEY);
		System.clearProperty(DesktopLivenessConfig.ENGINE_KEY);
		System.clearProperty(DesktopLivenessConfig.FAKE_SCENARIO_KEY);
	}

	@Test(timeout = 120_000)
	public void realEngineOnARecordedClipFailsAndAuditsThroughTheAuditModule() throws Exception {
		File clip = new File(getClass().getResource(NO_FACE_CLIP).toURI());
		System.setProperty(DesktopLivenessConfig.SOURCE_KEY, "video:" + clip.getAbsolutePath());
		synced.put(LivenessPolicy.PREFIX + "resident.maxRetries", "0");
		synced.put(LivenessPolicy.PREFIX + "resident.passiveTimeoutMs", "3000");

		LivenessSession session = service.newSession(WorkflowType.RESIDENT);
		assertNotNull(session);
		assertFalse(service.isScriptedEngine());
		LivenessTestSupport.Recorder rec = new LivenessTestSupport.Recorder();
		session.start(service.openFaceSource(), rec);
		LivenessResult r = rec.await(90);
		service.sessionCompleted(WorkflowType.RESIDENT, r);

		assertEquals(LivenessResult.Decision.FAILED, r.decision);
		assertEquals("one attempt only (maxRetries=0)", ErrorCode.MAX_RETRIES_EXCEEDED, r.failureCode);
		assertTrue("the signed bundle was used: " + r.modelVersion, r.modelVersion.startsWith("pad-"));

		List<AuditEvent> events = awaitAudit(AuditEvent.LIVENESS_FAILED_FINAL);
		String finalRecord = null;
		for (RecordingAuditManager.Call c : snapshot()) {
			if (c.event == AuditEvent.LIVENESS_FAILED_FINAL) {
				finalRecord = c.description;
			}
		}
		assertTrue("the audit keeps the real cause: " + finalRecord, finalRecord.contains("lastCause=FACE_NOT_DETECTED"));
		assertTrue(events.toString(), events.contains(AuditEvent.LIVENESS_CONFIG_LOADED));
		assertTrue(events.toString(), events.contains(AuditEvent.LIVENESS_STARTED));
		for (RecordingAuditManager.Call c : snapshot()) {
			assertEquals(Components.REG_LIVENESS, c.component);
			assertTrue(c.description.length() <= 2048);
			if (c.event == AuditEvent.LIVENESS_STARTED || c.event == AuditEvent.LIVENESS_FAILED_FINAL) {
				assertEquals(r.sessionId, c.refId);
				assertTrue(c.description, c.description.startsWith("workflow=RESIDENT; session=" + r.sessionId));
			}
		}
		assertTrue(service.metricsSnapshot().get("failedFinal").equals("1"));
	}

	@Test
	public void aWorkflowSwitchedOffBySyncedConfigKeepsTheLegacyFlow() {
		synced.put(LivenessPolicy.PREFIX + "operator.enabled", "false");
		assertNull(service.newSession(WorkflowType.OPERATOR));
		assertFalse(service.isEnabled(WorkflowType.OPERATOR));
		assertTrue(service.binding().verifyCapture(WorkflowType.OPERATOR, capture()).accepted());
		assertTrue(service.isEnabled(WorkflowType.RESIDENT));
		assertFalse("enabled workflows fail closed without a passed session",
				service.binding().verifyCapture(WorkflowType.RESIDENT, capture()).accepted());
	}

	@Test
	public void aConfigSyncTakesEffectForTheNextSession() {
		assertEquals(3, service.policy(WorkflowType.SUPERVISOR).maxRetries);
		synced.put(LivenessPolicy.PREFIX + "supervisor.maxRetries", "1");
		assertEquals(1, service.policy(WorkflowType.SUPERVISOR).maxRetries);
		synced.put(LivenessPolicy.PREFIX + "supervisor.diagnosticMode", "true");
		assertTrue(service.policyDiagnostic(WorkflowType.SUPERVISOR));
	}

	@Test(timeout = 60_000)
	public void theScriptedEngineIsOnlyForDevelopmentAndSkipsBinding() throws Exception {
		System.setProperty(DesktopLivenessConfig.ENGINE_KEY, "fake");
		System.setProperty(DesktopLivenessConfig.FAKE_SCENARIO_KEY, "PASS_PASSIVE");
		System.setProperty("liveness.fake.timeScale", "0");
		try {
			assertTrue(service.isScriptedEngine());
			LivenessSession session = service.newSession(WorkflowType.RESIDENT);
			LivenessTestSupport.Recorder rec = new LivenessTestSupport.Recorder();
			session.start(service.openFaceSource(), rec);
			LivenessResult r = rec.await(30);
			assertEquals(LivenessResult.Decision.PASSED, r.decision);
			service.binding().arm(WorkflowType.RESIDENT, r);
			FaceBinding.Verdict v = service.binding().verifyCapture(WorkflowType.RESIDENT, capture());
			assertEquals("SKIPPED_SCRIPTED_ENGINE", v.reason);
		} finally {
			System.clearProperty("liveness.fake.timeScale");
		}
	}

	private List<AuditEvent> awaitAudit(AuditEvent wanted) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			List<AuditEvent> events = new ArrayList<>();
			for (RecordingAuditManager.Call c : snapshot()) {
				events.add(c.event);
			}
			if (events.contains(wanted)) {
				return events;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("no " + wanted + " audited");
	}

	private List<RecordingAuditManager.Call> snapshot() {
		synchronized (audit) {
			return new ArrayList<>(audit.calls);
		}
	}

	private static List<BiometricsDto> capture() {
		BiometricsDto dto = new BiometricsDto();
		dto.setAttributeISO(new byte[] { 1 });
		return Collections.singletonList(dto);
	}
}
