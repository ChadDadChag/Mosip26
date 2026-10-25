package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.Test;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

public class FaceBindingTest {

	private final Map<String, Object> synced = new HashMap<>();
	private final DesktopLivenessConfig config = new DesktopLivenessConfig(() -> synced, new Properties());
	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final Set<WorkflowType> enabled = EnumSet.allOf(WorkflowType.class);
	private final Frame live = frame();
	private Frame decoded = frame();
	private String compareOutcome = "MATCH";
	private long now = 1_000;
	private int comparisons;

	private final CapturedFaceDecoder decoder = new CapturedFaceDecoder() {
		@Override
		Frame decode(byte[] iso) {
			return decoded;
		}
	};

	private FaceBinding binding(boolean scripted) {
		return new FaceBinding(enabled::contains, config, (a, b) -> {
			comparisons++;
			if ("THROW".equals(compareOutcome)) {
				throw new IllegalStateException("matcher closed");
			}
			return new FaceBinding.Comparison(compareOutcome, "MATCH".equals(compareOutcome) ? 0.71 : 0.12, 0.45);
		}, decoder, new DesktopAuditSink(audit, Runnable::run), () -> now, scripted);
	}

	private final FaceBinding binding = binding(false);

	@Test
	public void aMatchingCaptureIsAcceptedOnceAndAudited() {
		binding.arm(WorkflowType.RESIDENT, passed(live));
		FaceBinding.Verdict v = binding.verifyCapture(WorkflowType.RESIDENT, capture());
		assertEquals(FaceBinding.Verdict.Kind.MATCH, v.kind);
		assertTrue(v.accepted());
		assertEquals(AuditEvent.LIVENESS_FACE_BINDING, audit.last().event);
		assertTrue(audit.last().description.contains("outcome=MATCH; similarity=0.71; threshold=0.45"));
		assertEquals("session-1", audit.last().refId);

		FaceBinding.Verdict again = binding.verifyCapture(WorkflowType.RESIDENT, capture());
		assertEquals("the arming is consumed", "NOT_ARMED", again.reason);
		assertFalse(again.accepted());
	}

	@Test
	public void anotherPersonIsRejected() {
		compareOutcome = "NO_MATCH";
		binding.arm(WorkflowType.OPERATOR, passed(live));
		FaceBinding.Verdict v = binding.verifyCapture(WorkflowType.OPERATOR, capture());
		assertFalse(v.accepted());
		assertEquals("NO_MATCH", v.reason);
	}

	@Test
	public void everyOtherFailureFailsClosed() {
		binding.verifyCapture(WorkflowType.RESIDENT, capture());
		assertEquals("never armed", "NOT_ARMED", audit.last().description.split("; ")[2].substring(8));

		binding.arm(WorkflowType.OPERATOR, passed(live));
		assertEquals("armed for another workflow", "NOT_ARMED",
				binding.verifyCapture(WorkflowType.SUPERVISOR, capture()).reason);

		binding.arm(WorkflowType.RESIDENT, passed(live));
		now += FaceBinding.ARM_TTL_MS + 1;
		assertEquals("EXPIRED", binding.verifyCapture(WorkflowType.RESIDENT, capture()).reason);

		decoded = null;
		binding.arm(WorkflowType.RESIDENT, passed(live));
		assertEquals("UNDECODABLE", binding.verifyCapture(WorkflowType.RESIDENT, capture()).reason);

		decoded = frame();
		compareOutcome = "THROW";
		binding.arm(WorkflowType.RESIDENT, passed(live));
		assertEquals("ERROR", binding.verifyCapture(WorkflowType.RESIDENT, capture()).reason);

		compareOutcome = "MATCH";
		binding.arm(WorkflowType.RESIDENT, passed(null));
		assertEquals("NO_LIVE_FRAME", binding.verifyCapture(WorkflowType.RESIDENT, capture()).reason);

		binding.arm(WorkflowType.RESIDENT, passed(live));
		binding.disarm();
		assertEquals("NOT_ARMED", binding.verifyCapture(WorkflowType.RESIDENT, capture()).reason);
	}

	@Test
	public void aFailedSessionNeverArms() {
		binding.arm(WorkflowType.RESIDENT, new LivenessResult(LivenessResult.Decision.FAILED,
				LivenessResult.PassPath.NONE, null, live, 0.1f, Collections.<ChallengeType>emptyList(), 4, 1, "s", "m"));
		assertFalse(binding.verifyCapture(WorkflowType.RESIDENT, capture()).accepted());
	}

	@Test
	public void livenessDisabledKeepsTheLegacyFlow() {
		enabled.remove(WorkflowType.SUPERVISOR);
		FaceBinding.Verdict v = binding.verifyCapture(WorkflowType.SUPERVISOR, capture());
		assertEquals(FaceBinding.Verdict.Kind.LIVENESS_DISABLED, v.kind);
		assertTrue(v.accepted());
		assertTrue("nothing to audit", audit.calls.isEmpty());
	}

	@Test
	public void bindingSwitchedOffIsAuditedAsSkipped() {
		synced.put(DesktopLivenessConfig.BINDING_ENABLED_KEY, "false");
		FaceBinding.Verdict v = binding.verifyCapture(WorkflowType.RESIDENT, capture());
		assertEquals(FaceBinding.Verdict.Kind.BINDING_DISABLED, v.kind);
		assertTrue(v.accepted());
		assertTrue(audit.last().description.contains("outcome=SKIPPED"));
		assertEquals(0, comparisons);
	}

	@Test
	public void onlyTheScriptedEngineMayPassWithoutABestFrame() {
		FaceBinding dev = binding(true);
		dev.arm(WorkflowType.RESIDENT, passed(null));
		FaceBinding.Verdict v = dev.verifyCapture(WorkflowType.RESIDENT, capture());
		assertTrue(v.accepted());
		assertEquals("SKIPPED_SCRIPTED_ENGINE", v.reason);
	}

	@Test
	public void noImageReachesTheAudit() {
		binding.arm(WorkflowType.RESIDENT, passed(live));
		binding.verifyCapture(WorkflowType.RESIDENT, capture());
		String d = audit.last().description;
		assertTrue(d, d.length() < 200);
	}

	private static List<BiometricsDto> capture() {
		BiometricsDto dto = new BiometricsDto();
		dto.setAttributeISO(new byte[] { 1, 2, 3 });
		return Collections.singletonList(dto);
	}

	private static LivenessResult passed(Frame best) {
		return new LivenessResult(LivenessResult.Decision.PASSED, LivenessResult.PassPath.PASSIVE_ONLY, null, best,
				0.93f, Collections.<ChallengeType>emptyList(), 1, 1500, "session-1", "pad-2026.10.5");
	}

	private static Frame frame() {
		return new Frame(new byte[4 * 4 * 3], 4, 4, 0, 0, "test");
	}
}
