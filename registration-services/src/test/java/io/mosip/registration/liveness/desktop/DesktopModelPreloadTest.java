package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;

/** The start-up preload: a model integrity failure is audited, a matcher warm-up failure only logged. */
public class DesktopModelPreloadTest {

	private static final String BUNDLED = "pad-2026.10.5";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Map<String, Object> synced = new HashMap<>();
	private final RecordingAuditManager audit = new RecordingAuditManager();

	@Before
	public void emptyStore() throws Exception {
		synced.put(DesktopLivenessConfig.MODEL_STORE_KEY, tmp.newFolder("store").getAbsolutePath());
	}

	private DesktopLivenessService service(Map<String, byte[]> bundled, KeyPair trusted) {
		return new DesktopLivenessService(new DesktopLivenessConfig(() -> synced, DesktopLivenessConfig.loadDefaults()),
				audit, null, null).withModels(SignedTestBundles.source(bundled), SignedTestBundles.trust(trusted));
	}

	@Test(timeout = 120_000)
	public void aMatcherWarmUpFailureIsLoggedNotAuditedAsAnIntegrityFailure() throws Exception {
		KeyPair kp = SignedTestBundles.keyPair();
		Map<ModelRole, byte[]> models = SignedTestBundles.realModels();
		byte[] notAModel = new byte[4096];
		new Random(1).nextBytes(notAModel);
		models.put(ModelRole.MATCHER, notAModel); // signed and verified, but not an ONNX model: only the warm-up fails
		DesktopLivenessService s = service(SignedTestBundles.bundle(BUNDLED, kp, models), kp);

		assertTrue("the per-frame models verified and loaded", s.preload());
		String loaded = audit.await(AuditEvent.LIVENESS_MODEL_LOADED, "");
		assertTrue(loaded, loaded.contains("modelVersion=" + BUNDLED));
		Thread.sleep(300);
		assertFalse(audit.events().toString(), audit.events().contains(AuditEvent.LIVENESS_MODEL_INTEGRITY_FAILED));
	}

	@Test(timeout = 60_000)
	public void aBundleThatDoesNotVerifyIsStillAnIntegrityFailure() throws Exception {
		KeyPair signer = SignedTestBundles.keyPair();
		DesktopLivenessService s = service(SignedTestBundles.bundle(BUNDLED, signer, SignedTestBundles.realModels()),
				SignedTestBundles.keyPair()); // another key: the signature does not verify

		assertFalse(s.preload());
		String failed = audit.await(AuditEvent.LIVENESS_MODEL_INTEGRITY_FAILED, "");
		assertTrue(failed, failed.contains("errorCode=MODEL_INTEGRITY_FAILED"));
	}
}
