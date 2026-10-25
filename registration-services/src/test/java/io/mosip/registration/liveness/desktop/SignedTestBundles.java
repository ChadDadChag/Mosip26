package io.mosip.registration.liveness.desktop;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;

/**
 * TEST ONLY: model bundles signed with a throwaway key, made in memory from the real bundled models, so that the
 * update and preload paths run end to end without the development signing key.
 */
final class SignedTestBundles {

	static final String KEY_ID = "desktop-test-key";

	private SignedTestBundles() {
	}

	static KeyPair keyPair() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		return g.generateKeyPair();
	}

	static TrustedKeys trust(KeyPair kp) {
		return new TrustedKeys(Collections.singletonMap(KEY_ID, kp.getPublic()));
	}

	/** The five model files of the signed bundle in the analyzer jar. */
	static Map<ModelRole, byte[]> realModels() throws IOException {
		ClasspathBundleSource bundled = new ClasspathBundleSource();
		Map<ModelRole, byte[]> models = new EnumMap<>(ModelRole.class);
		for (ModelRole role : ModelRole.values()) {
			models.put(role, bundled.read(role.fileName()));
		}
		return models;
	}

	/** manifest.json, manifest.sig and the models of a bundle {@code version}, signed with {@code kp}. */
	static Map<String, byte[]> bundle(String version, KeyPair kp, Map<ModelRole, byte[]> models) throws Exception {
		StringBuilder files = new StringBuilder();
		for (ModelRole role : ModelRole.values()) {
			byte[] b = models.get(role);
			files.append(files.length() == 0 ? "" : ",\n").append("    {\"role\": \"").append(role.name())
					.append("\", \"name\": \"").append(role.fileName()).append("\", \"size\": ").append(b.length)
					.append(", \"sha256\": \"").append(hex(MessageDigest.getInstance("SHA-256").digest(b))).append("\"}");
		}
		byte[] manifest = ("{\n  \"schema\": 1,\n  \"bundleVersion\": \"" + version + "\",\n  \"keyId\": \"" + KEY_ID
				+ "\",\n  \"createdUtc\": \"2026-10-26T00:00:00Z\",\n  \"params\": { \"pitchBiasDeg\": -9.5 },\n"
				+ "  \"files\": [\n" + files + "\n  ]\n}\n").getBytes(StandardCharsets.UTF_8);
		Signature s = Signature.getInstance("SHA256withECDSA");
		s.initSign(kp.getPrivate());
		s.update(manifest);
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put("manifest.json", manifest);
		out.put("manifest.sig", (KEY_ID + ":" + hex(s.sign()) + "\n").getBytes(StandardCharsets.US_ASCII));
		for (ModelRole role : ModelRole.values()) {
			out.put(role.fileName(), models.get(role));
		}
		return out;
	}

	/** An in-memory bundle source. */
	static BundleSource source(final Map<String, byte[]> files) {
		return new BundleSource() {
			@Override
			public byte[] read(String name) throws IOException {
				byte[] b = files.get(name);
				if (b == null) {
					throw new FileNotFoundException(name);
				}
				return b.clone();
			}

			@Override
			public String describe() {
				return "test bundle";
			}
		};
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format("%02x", x & 0xff));
		}
		return sb.toString();
	}
}
