package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.integrity.VerifiedBundle;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.desktop.DesktopLivenessService.ModelBundleOutcome;
import io.mosip.registration.liveness.desktop.DesktopLivenessService.ModelBundleResult;
import io.mosip.registration.util.advice.AuthenticationAdvice;

/**
 * F17: a supervisor or admin installs a signed bundle zip from removable media, or rolls back; bundles are signed with a
 * throwaway key. Every action is audited as LIVENESS_MODEL_UPDATE with codes, versions and the user id only.
 */
public class DesktopOfflineModelInstallTest {

	private static final String BUNDLED = "pad-2026.10.5";
	private static final String NEWER = "pad-2099.01.1";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Map<String, Object> synced = new HashMap<>();
	private final RecordingAuditManager audit = new RecordingAuditManager();
	private File storeDir;
	private KeyPair kp;
	private TrustedKeys trust;
	private Collection<String> roles = Collections.singletonList(AuthenticationAdvice.SUPERVISOR_ROLE);

	@Before
	public void keys() throws Exception {
		storeDir = tmp.newFolder("store");
		synced.put(DesktopLivenessConfig.MODEL_STORE_KEY, storeDir.getAbsolutePath());
		kp = SignedTestBundles.keyPair();
		trust = SignedTestBundles.trust(kp);
	}

	private DesktopLivenessService service() throws Exception {
		return new DesktopLivenessService(new DesktopLivenessConfig(() -> synced, DesktopLivenessConfig.loadDefaults()),
				audit, null, null)
						.withModels(SignedTestBundles.source(SignedTestBundles.bundle(BUNDLED, kp, SignedTestBundles.realModels())),
								trust)
						.withUser(() -> roles, () -> "sup-17");
	}

	/** A zip as ModelBundleTool pack writes it. */
	private File zip(String version) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ModelUpdater.writeZip(VerifiedBundle.load(
				SignedTestBundles.source(SignedTestBundles.bundle(version, kp, SignedTestBundles.realModels())), trust), out);
		File f = tmp.newFile(version + ".zip");
		Files.write(f.toPath(), out.toByteArray());
		return f;
	}

	/** A zip with exactly these entries, in this order. */
	private File rawZip(String name, Object... nameThenBytes) throws Exception {
		File f = tmp.newFile(name);
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f.toPath()))) {
			for (int i = 0; i < nameThenBytes.length; i += 2) {
				z.putNextEntry(new ZipEntry((String) nameThenBytes[i]));
				z.write((byte[]) nameThenBytes[i + 1]);
				z.closeEntry();
			}
		}
		return f;
	}

	private void assertStoreUntouched() throws Exception {
		ModelStore store = new ModelStore(storeDir);
		assertNull(store.currentVersion());
		assertTrue(store.installedVersions().isEmpty());
	}

	@Test(timeout = 180_000)
	public void aSupervisorInstallsANewerZipAndNewSessionsUseIt() throws Exception {
		DesktopLivenessService s = service();
		assertTrue(s.preload());
		audit.await(AuditEvent.LIVENESS_MODEL_LOADED, "modelVersion=" + BUNDLED);
		assertTrue(s.isOfflineModelInstallAllowed());
		assertEquals(BUNDLED, s.activeModelVersion());
		File zip = zip(NEWER);

		ModelBundleResult r = s.installOfflineBundle(zip);

		assertEquals(ModelBundleOutcome.INSTALLED, r.outcome());
		assertEquals(BUNDLED, r.fromVersion());
		assertEquals(NEWER, r.toVersion());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "trigger=offline");
		assertTrue(a, a.contains("outcome=INSTALLED") && a.contains("fromVersion=" + BUNDLED)
				&& a.contains("toVersion=" + NEWER) && a.contains("userId=sup-17"));
		assertFalse("no paths in the audit", a.contains(zip.getName()) || a.contains(tmp.getRoot().getName()));
		audit.await(AuditEvent.LIVENESS_MODEL_LOADED, "modelVersion=" + NEWER);
		assertEquals(Arrays.asList(BUNDLED, NEWER), new ModelStore(storeDir).installedVersions());
		assertEquals(NEWER, s.activeModelVersion());
		assertTrue("the operator's file is left alone", zip.exists());
	}

	/** A signed bundle of this version, packed as a zip, with one byte of the detector model flipped. */
	private File tamperedZip(String version, Map<String, byte[]> f) throws Exception {
		Object[] layout = new Object[(2 + ModelRole.values().length) * 2];
		int i = 0;
		layout[i++] = VerifiedBundle.MANIFEST;
		layout[i++] = f.get(VerifiedBundle.MANIFEST);
		layout[i++] = VerifiedBundle.SIGNATURE;
		layout[i++] = f.get(VerifiedBundle.SIGNATURE);
		for (ModelRole role : ModelRole.values()) {
			byte[] b = f.get(role.fileName());
			if (role == ModelRole.DETECTOR) {
				b = b.clone();
				b[b.length / 2] ^= 1;
			}
			layout[i++] = role.fileName();
			layout[i++] = b;
		}
		return rawZip(version + "-tampered.zip", layout);
	}

	@Test(timeout = 120_000)
	public void aTamperedOrUnsignedZipIsRefusedAndTheStoreIsUnchanged() throws Exception {
		DesktopLivenessService s = service();
		Map<String, byte[]> f = SignedTestBundles.bundle(NEWER, kp, SignedTestBundles.realModels());

		ModelBundleResult tampered = s.installOfflineBundle(tamperedZip(NEWER, f));
		assertEquals(ModelBundleOutcome.INVALID, tampered.outcome());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "trigger=offline");
		assertTrue(a, a.contains("outcome=FAILED") && a.contains("errorCode=MODEL_INTEGRITY_FAILED")
				&& a.contains("reason=BUNDLE_INVALID"));
		ModelBundleResult unsigned = s.installOfflineBundle(
				rawZip("unsigned.zip", VerifiedBundle.MANIFEST, f.get(VerifiedBundle.MANIFEST)));
		assertEquals(ModelBundleOutcome.INVALID, unsigned.outcome());
		assertStoreUntouched();
	}

	@Test(timeout = 120_000)
	public void aSameOrOlderZipIsRefused() throws Exception {
		DesktopLivenessService s = service();
		assertEquals(ModelBundleOutcome.NOT_NEWER, s.installOfflineBundle(zip(BUNDLED)).outcome());
		assertEquals(ModelBundleOutcome.NOT_NEWER, s.installOfflineBundle(zip("pad-2026.09.1")).outcome());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "reason=NOT_NEWER");
		assertTrue(a, a.contains("outcome=UP_TO_DATE") && a.contains("trigger=offline"));
		assertStoreUntouched();
	}

	@Test(timeout = 120_000)
	public void aTamperedZipIsInvalidEvenWhenItsVersionIsNotNewer() throws Exception {
		DesktopLivenessService s = service();
		String older = "pad-2026.09.1";
		ModelBundleResult r = s.installOfflineBundle(
				tamperedZip(older, SignedTestBundles.bundle(older, kp, SignedTestBundles.realModels())));
		assertEquals("verified before the version check", ModelBundleOutcome.INVALID, r.outcome());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "trigger=offline");
		assertTrue(a, a.contains("outcome=FAILED") && a.contains("errorCode=MODEL_INTEGRITY_FAILED")
				&& a.contains("reason=BUNDLE_INVALID") && !a.contains("NOT_NEWER"));
		assertStoreUntouched();
	}

	@Test(timeout = 60_000)
	public void aZipSlipEntryIsRefusedAndNothingIsWrittenOutside() throws Exception {
		DesktopLivenessService s = service();
		Map<String, byte[]> f = SignedTestBundles.bundle(NEWER, kp, SignedTestBundles.realModels());
		ModelBundleResult r = s.installOfflineBundle(rawZip("slip.zip", VerifiedBundle.MANIFEST,
				f.get(VerifiedBundle.MANIFEST), "../evil.onnx", new byte[] {6}));
		assertEquals(ModelBundleOutcome.INVALID, r.outcome());
		assertFalse(new File(storeDir.getParentFile(), "evil.onnx").exists());
		assertFalse(new File(storeDir, "evil.onnx").exists());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "reason=ZIP_ENTRY_REFUSED");
		assertTrue(a, a.contains("errorCode=MODEL_INTEGRITY_FAILED"));
		assertStoreUntouched();
	}

	@Test(timeout = 180_000)
	public void aRollbackReturnsToTheBundledModelsAndThenHasNothingEarlier() throws Exception {
		DesktopLivenessService s = service();
		assertEquals(ModelBundleOutcome.NOTHING_TO_ROLL_BACK, s.rollbackModelBundle().outcome());
		assertEquals(ModelBundleOutcome.INSTALLED, s.installOfflineBundle(zip(NEWER)).outcome());

		ModelBundleResult r = s.rollbackModelBundle();

		assertEquals(ModelBundleOutcome.ROLLED_BACK, r.outcome());
		assertEquals(NEWER, r.fromVersion());
		assertEquals(BUNDLED, r.toVersion());
		assertEquals(BUNDLED, s.activeModelVersion());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "outcome=ROLLED_BACK");
		assertTrue(a, a.contains("trigger=rollback") && a.contains("fromVersion=" + NEWER)
				&& a.contains("toVersion=" + BUNDLED) && a.contains("userId=sup-17"));
		assertEquals(ModelBundleOutcome.NOTHING_TO_ROLL_BACK, s.rollbackModelBundle().outcome());
	}

	@Test(timeout = 60_000)
	public void onlySupervisorsAndAdminsMayActAndTheConfigCanHideIt() throws Exception {
		DesktopLivenessService s = service();
		assertTrue(s.isOfflineModelInstallAllowed());
		roles = Collections.singletonList(AuthenticationAdvice.ADMIN_ROLE);
		assertTrue(s.isOfflineModelInstallAllowed());
		roles = Arrays.asList(AuthenticationAdvice.OFFICER_ROLE, AuthenticationAdvice.DEFAULT_ROLE);
		assertFalse(s.isOfflineModelInstallAllowed());

		File zip = zip(NEWER);
		assertEquals(ModelBundleOutcome.NOT_ALLOWED, s.installOfflineBundle(zip).outcome());
		assertEquals(ModelBundleOutcome.NOT_ALLOWED, s.rollbackModelBundle().outcome());
		String a = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "reason=NOT_ALLOWED");
		assertTrue(a, a.contains("outcome=REFUSED") && a.contains("userId=sup-17"));
		assertStoreUntouched();

		roles = Collections.singletonList(AuthenticationAdvice.SUPERVISOR_ROLE);
		synced.put(DesktopLivenessConfig.MODEL_OFFLINE_INSTALL_ENABLED_KEY, "false");
		assertFalse("off in the synced config: hidden and refused", s.isOfflineModelInstallAllowed());
		assertEquals(ModelBundleOutcome.NOT_ALLOWED, s.installOfflineBundle(zip).outcome());
		assertStoreUntouched();
	}
}
