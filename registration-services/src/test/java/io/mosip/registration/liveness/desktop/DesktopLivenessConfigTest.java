package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Test;

import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.WorkflowType;

public class DesktopLivenessConfigTest {

	private final Map<String, Object> synced = new HashMap<>();

	private DesktopLivenessConfig config(Properties defaults) {
		return new DesktopLivenessConfig(() -> synced, defaults);
	}

	@After
	public void clearJvmProperties() {
		System.clearProperty(DesktopLivenessConfig.ENGINE_KEY);
		System.clearProperty(DesktopLivenessConfig.SOURCE_KEY);
	}

	@Test
	public void syncedConfigWinsOverBundledDefaults() {
		Properties defaults = new Properties();
		defaults.setProperty(DesktopLivenessConfig.BINDING_ENABLED_KEY, "true");
		DesktopLivenessConfig c = config(defaults);
		assertTrue(c.getBoolean(DesktopLivenessConfig.BINDING_ENABLED_KEY, false));
		synced.put(DesktopLivenessConfig.BINDING_ENABLED_KEY, " false ");
		assertFalse(c.getBoolean(DesktopLivenessConfig.BINDING_ENABLED_KEY, true));
	}

	@Test
	public void blankValuesCountAsAbsentSoTheEngineDefaultsApply() {
		synced.put(LivenessPolicy.PREFIX + "resident.maxRetries", "  ");
		DesktopLivenessConfig c = config(new Properties());
		assertNull(c.get(LivenessPolicy.PREFIX + "resident.maxRetries"));
		assertEquals(3, LivenessPolicy.load(c, WorkflowType.RESIDENT).maxRetries);
	}

	@Test
	public void workflowPoliciesComeFromTheSyncedGlobalConfig() {
		synced.put(LivenessPolicy.PREFIX + "default.passiveThreshold", "0.9");
		synced.put(LivenessPolicy.PREFIX + "supervisor.minChallenges", "1");
		synced.put(LivenessPolicy.PREFIX + "supervisor.challengeTypes", "BLINK,TURN_LEFT");
		DesktopLivenessConfig c = config(new Properties());
		LivenessPolicy supervisor = LivenessPolicy.load(c, WorkflowType.SUPERVISOR);
		assertEquals(0.9f, supervisor.passiveThreshold, 1e-6);
		assertEquals(1, supervisor.minChallenges);
		assertEquals(2, supervisor.challengePool.size());
	}

	@Test
	public void developmentSwitchesAreReadFromJvmPropertiesOnly() {
		synced.put(DesktopLivenessConfig.ENGINE_KEY, "fake");
		synced.put(DesktopLivenessConfig.SOURCE_KEY, "webcam");
		DesktopLivenessConfig c = config(new Properties());
		assertNull("a config push can never select the scripted engine", c.get(DesktopLivenessConfig.ENGINE_KEY));
		assertNull(c.get(DesktopLivenessConfig.SOURCE_KEY));
		System.setProperty(DesktopLivenessConfig.ENGINE_KEY, "fake");
		assertEquals("fake", c.get(DesktopLivenessConfig.ENGINE_KEY));
	}

	@Test
	public void fingerprintChangesWithLivenessKeysOnly() {
		DesktopLivenessConfig c = config(new Properties());
		String before = c.fingerprint();
		synced.put("mosip.registration.some.other.key", "1");
		assertEquals(before, c.fingerprint());
		synced.put(LivenessPolicy.PREFIX + "operator.maxRetries", "1");
		assertNotEquals(before, c.fingerprint());
	}

	@Test
	public void invalidNumbersFallBack() {
		synced.put(DesktopLivenessConfig.AUTO_CONTINUE_KEY, "soon");
		assertEquals(1000L, config(new Properties()).getLong(DesktopLivenessConfig.AUTO_CONTINUE_KEY, 1000L));
	}

	@Test
	public void theBundledDefaultsFileIsOnTheClasspath() {
		Properties p = DesktopLivenessConfig.loadDefaults();
		assertEquals("true", p.getProperty(DesktopLivenessConfig.BINDING_ENABLED_KEY));
		assertEquals("15000", p.getProperty(DesktopLivenessConfig.SBI_FRAME_TIMEOUT_KEY));
	}
}
