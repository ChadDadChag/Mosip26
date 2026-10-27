package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.Test;
import org.mockito.Mockito;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.dao.UserDetailDAO;
import io.mosip.registration.entity.UserBiometric;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

public class IdentityBindingTest {

	private static final long DAY = 24L * 60 * 60 * 1000;

	private final Map<String, Object> synced = new HashMap<>();
	private final DesktopLivenessConfig config = new DesktopLivenessConfig(() -> synced, new Properties());
	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final Set<WorkflowType> enabled = EnumSet.allOf(WorkflowType.class);
	private final Frame live = frame();
	private Frame decoded = frame();
	private String outcome = "MATCH";
	private long now = 100 * DAY;
	private List<IdentityBinding.Template> stored = new ArrayList<>(
			Collections.singletonList(new IdentityBinding.Template(new byte[] { 1 }, 70 * DAY)));
	private final List<List<Frame>> identified = new ArrayList<>();
	private final List<String> loadedFor = new ArrayList<>();

	private final CapturedFaceDecoder decoder = new CapturedFaceDecoder() {
		@Override
		Frame decode(byte[] iso) {
			return decoded;
		}
	};

	private final FaceBinding faceBinding = new FaceBinding(enabled::contains, config, (a, b) -> null, decoder,
			new DesktopAuditSink(audit, Runnable::run), () -> now, false);

	private IdentityBinding identity(boolean scripted) {
		return new IdentityBinding(enabled::contains, config, faceBinding, (l, faces) -> {
			identified.add(faces);
			if ("THROW".equals(outcome)) {
				throw new IllegalStateException("matcher closed");
			}
			double sim = "MATCH".equals(outcome) ? 0.6789 : outcome.startsWith("NOT_BOUND") ? Double.NaN : 0.1234;
			return new IdentityBinding.Identification(outcome, sim, 0.40, faces.size(),
					"NOT_BOUND_UNREADABLE".equals(outcome) ? faces.size() : 0);
		}, userId -> {
			loadedFor.add(userId);
			return stored;
		}, decoder, new DesktopAuditSink(audit, Runnable::run), () -> now, scripted);
	}

	private final IdentityBinding identity = identity(false);

	@Test
	public void theOnboardedUserMatchesOnceAndIsAudited() {
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		IdentityBinding.Verdict v = identity.verify(WorkflowType.OPERATOR, "op1", IdentityBinding.GATE_PASSWORD_LOGIN, 1);
		assertEquals(IdentityBinding.Verdict.Kind.MATCH, v.kind);
		assertTrue(v.accepted());
		assertEquals(Collections.singletonList("op1"), loadedFor);
		assertEquals(AuditEvent.LIVENESS_IDENTITY_BINDING, audit.last().event);
		String d = audit.last().description;
		assertTrue(d, d.contains("outcome=MATCH; similarity=0.68; threshold=0.40; attempt=1"));
		assertTrue(d, d.contains("gate=password_login") && d.contains("templateAgeDays=30") && d.contains("templates=1")
				&& d.contains("unreadable=0") && d.contains("userId=op1") && d.contains("accepted=true"));
		assertFalse("no score with more decimals", d.contains("0.678"));
		assertEquals("session-1", audit.last().refId);

		IdentityBinding.Verdict again = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 2);
		assertEquals("the arming is consumed", "NOT_ARMED", again.reason);
		assertFalse(again.accepted());
		assertTrue(again.retryable());
	}

	@Test
	public void anotherPersonIsRejectedAndMayRetry() {
		outcome = "NO_MATCH";
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		IdentityBinding.Verdict v = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertFalse(v.accepted());
		assertTrue(v.retryable());
		assertEquals("NO_MATCH", v.reason);
		assertTrue(audit.last().description.contains("outcome=NO_MATCH; similarity=0.12"));
		assertTrue(audit.last().description.contains("accepted=false"));
	}

	@Test
	public void everyTemplateIsDecodedAndPassedOn() {
		stored = Arrays.asList(new IdentityBinding.Template(new byte[] { 1 }, null),
				new IdentityBinding.Template(new byte[] { 2 }, 99 * DAY), new IdentityBinding.Template(null, 10 * DAY));
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertEquals(3, identified.get(0).size());
		assertTrue("the newest template's age", audit.last().description.contains("templateAgeDays=1"));
	}

	@Test
	public void withoutAReadableTemplateTheUserGetsInOnPresenceUnlessTemplatesAreRequired() {
		List<IdentityBinding.Template> one = stored;
		for (String notBound : new String[] { "NOT_BOUND_NO_TEMPLATE", "NOT_BOUND_UNREADABLE" }) {
			outcome = notBound;
			boolean none = notBound.endsWith("NO_TEMPLATE");
			stored = none ? Collections.<IdentityBinding.Template>emptyList() : one;
			synced.remove(DesktopLivenessConfig.IDENTITY_REQUIRE_TEMPLATE_KEY);
			faceBinding.arm(WorkflowType.OPERATOR, passed(live));
			IdentityBinding.Verdict v = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
			assertEquals(IdentityBinding.Verdict.Kind.NOT_BOUND, v.kind);
			assertTrue(v.accepted());
			assertTrue(audit.last().description, audit.last().description.contains("outcome=" + notBound
					+ "; similarity=NaN") && audit.last().description.contains("requireTemplate=false"));
			assertEquals("an age only with a template", !none, audit.last().description.contains("templateAgeDays="));

			synced.put(DesktopLivenessConfig.IDENTITY_REQUIRE_TEMPLATE_KEY, "true");
			faceBinding.arm(WorkflowType.OPERATOR, passed(live));
			IdentityBinding.Verdict required = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
			assertFalse(required.accepted());
			assertFalse("a new liveness session cannot help", required.retryable());
			assertTrue(audit.last().description.contains("requireTemplate=true"));
		}
	}

	@Test
	public void everyOtherFailureFailsClosed() {
		identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertTrue("never armed", audit.last().description.contains("outcome=NOT_ARMED"));

		faceBinding.arm(WorkflowType.SUPERVISOR, passed(live));
		assertEquals("armed for another workflow", "NOT_ARMED",
				identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1).reason);

		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		now += FaceBinding.ARM_TTL_MS + 1;
		assertEquals("EXPIRED", identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1).reason);

		faceBinding.arm(WorkflowType.OPERATOR, passed(null));
		assertEquals("NO_LIVE_FRAME", identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1).reason);

		outcome = "THROW";
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		IdentityBinding.Verdict error = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 2);
		assertEquals("ERROR", error.reason);
		assertTrue(error.retryable());
		assertTrue(audit.last().description.contains("attempt=2"));

		outcome = "NO_LIVE_FACE";
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		assertFalse(identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1).accepted());

		IdentityBinding broken = new IdentityBinding(enabled::contains, config, faceBinding, (l, f) -> null, userId -> {
			throw new IllegalStateException("database closed");
		}, decoder, new DesktopAuditSink(audit, Runnable::run), () -> now, false);
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		assertEquals("ERROR", broken.verify(WorkflowType.OPERATOR, "op1", "password_login", 1).reason);
	}

	@Test
	public void livenessOffKeepsTheLegacyFlowAndSwitchedOffIsAudited() {
		enabled.remove(WorkflowType.OPERATOR);
		IdentityBinding.Verdict legacy = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertEquals(IdentityBinding.Verdict.Kind.LIVENESS_DISABLED, legacy.kind);
		assertTrue(legacy.accepted());
		assertTrue("nothing to audit", audit.calls.isEmpty());

		enabled.add(WorkflowType.OPERATOR);
		synced.put(DesktopLivenessConfig.IDENTITY_ENABLED_KEY, "false");
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		IdentityBinding.Verdict off = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertEquals(IdentityBinding.Verdict.Kind.DISABLED, off.kind);
		assertTrue(off.accepted());
		assertTrue(audit.last().description.contains("outcome=SKIPPED"));
		assertTrue("no template read", loadedFor.isEmpty());

		// A malformed value is not "off": F15 still checks the face.
		synced.put(DesktopLivenessConfig.IDENTITY_ENABLED_KEY, "yes");
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		IdentityBinding.Verdict typo = identity.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertNotEquals(IdentityBinding.Verdict.Kind.DISABLED, typo.kind);
		assertEquals("the onboarded faces were read", Arrays.asList("op1"), loadedFor);
	}

	@Test
	public void onlyTheScriptedEngineSkipsTheComparison() {
		IdentityBinding dev = identity(true);
		faceBinding.arm(WorkflowType.OPERATOR, passed(live));
		IdentityBinding.Verdict v = dev.verify(WorkflowType.OPERATOR, "op1", "password_login", 1);
		assertTrue(v.accepted());
		assertEquals("SKIPPED_SCRIPTED_ENGINE", v.reason);
		assertTrue(identified.isEmpty());
	}

	@Test
	public void theDemoPassesItsOwnLiveFace() {
		IdentityBinding.Verdict v = identity.verifyLive(WorkflowType.OPERATOR, "test-user",
				IdentityBinding.GATE_DIAGNOSTICS, 1, "demo-1", live);
		assertEquals(IdentityBinding.Verdict.Kind.MATCH, v.kind);
		assertEquals(0.6789, v.similarity, 1e-9);
		assertTrue(audit.last().description.contains("gate=diagnostics"));
		assertEquals("NO_LIVE_FRAME",
				identity.verifyLive(WorkflowType.OPERATOR, "test-user", "diagnostics", 1, "demo-2", null).reason);
	}

	@Test
	public void theClientReadsTheBirFromTheRowElseTheIsoColumn() throws Exception {
		UserDetailDAO dao = Mockito.mock(UserDetailDAO.class);
		UserBiometric legacy = new UserBiometric();
		legacy.setBioRawImage(null);
		legacy.setBioIsoImage(new byte[] { 7, 7 });
		UserBiometric garbled = new UserBiometric();
		garbled.setBioRawImage("<not a BIR".getBytes("UTF-8"));
		garbled.setBioIsoImage(new byte[] { 8 });
		Mockito.when(dao.getUserSpecificBioDetails("op1", "Face")).thenReturn(Arrays.asList(legacy, garbled));
		List<IdentityBinding.Template> t = IdentityBinding.fromUserBiometrics(dao).load("op1");
		assertEquals(2, t.size());
		assertArrayEquals(new byte[] { 7, 7 }, t.get(0).isoRecord);
		assertArrayEquals("an unreadable BIR falls back to the ISO column", new byte[] { 8 }, t.get(1).isoRecord);
		assertNull("no creation time set", t.get(0).storedAtMs);
		assertTrue(IdentityBinding.fromUserBiometrics(dao).load("nobody").isEmpty());
		assertTrue(IdentityBinding.fromUserBiometrics(null).load("op1").isEmpty());
	}

	@Test
	public void theTemplateAgeComesFromTheUtcCreationTime() throws Exception {
		UserDetailDAO dao = Mockito.mock(UserDetailDAO.class);
		UserBiometric row = new UserBiometric();
		row.setBioIsoImage(new byte[] { 1 });
		// The client stores crDtime as the UTC wall-clock time (DateUtils.getUTCCurrentDateTime()).
		LocalDateTime utc = LocalDateTime.of(2026, 9, 27, 10, 0);
		Timestamp ts = Timestamp.valueOf(utc);
		java.lang.reflect.Field f = io.mosip.registration.entity.RegistrationCommonFields.class.getDeclaredField("crDtime");
		f.setAccessible(true);
		f.set(row, ts);
		Mockito.when(dao.getUserSpecificBioDetails("op1", "Face")).thenReturn(Collections.singletonList(row));
		assertEquals(Long.valueOf(utc.toInstant(ZoneOffset.UTC).toEpochMilli()),
				IdentityBinding.fromUserBiometrics(dao).load("op1").get(0).storedAtMs);
	}

	private static LivenessResult passed(Frame best) {
		return new LivenessResult(LivenessResult.Decision.PASSED, LivenessResult.PassPath.PASSIVE_ONLY, null, best,
				0.93f, Collections.<ChallengeType>emptyList(), 1, 1500, "session-1", "pad-2026.10.5");
	}

	private static Frame frame() {
		return new Frame(new byte[4 * 4 * 3], 4, 4, 0, 0, "test");
	}
}
