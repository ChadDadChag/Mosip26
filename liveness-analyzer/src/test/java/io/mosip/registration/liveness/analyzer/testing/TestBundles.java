package io.mosip.registration.liveness.analyzer.testing;

import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.Hex;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** TEST ONLY: signed model bundles made with a throwaway key. */
public final class TestBundles {

    public static final String KEY_ID = "test-key";

    private TestBundles() { }

    public static KeyPair keyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    public static TrustedKeys trust(String keyId, PublicKey key) {
        return new TrustedKeys(Collections.singletonMap(keyId, key));
    }

    /** Small random "model" files (verification does not care what they contain). */
    public static Map<ModelRole, byte[]> fakeModels(long seed) {
        Map<ModelRole, byte[]> m = new LinkedHashMap<ModelRole, byte[]>();
        Random r = new Random(seed);
        for (ModelRole role : ModelRole.values()) {
            byte[] b = new byte[100 + role.ordinal()];
            r.nextBytes(b);
            m.put(role, b);
        }
        return m;
    }

    /** One file entry of the manifest, in the tool's format. */
    public static String fileEntry(String role, String name, long size, String sha256) {
        return "    {\"role\": \"" + role + "\", \"name\": \"" + name + "\", \"size\": " + size + ", \"sha256\": \"" + sha256 + "\"}";
    }

    /** The manifest text for these models (schema 1, deterministic layout). */
    public static String manifest(String version, String keyId, Map<ModelRole, byte[]> models, String paramsJson) throws Exception {
        StringBuilder files = new StringBuilder();
        for (ModelRole role : ModelRole.values()) {
            byte[] b = models.get(role);
            if (files.length() > 0) files.append(",\n");
            files.append(fileEntry(role.name(), role.fileName(), b.length, sha256(b)));
        }
        return manifest(version, keyId, paramsJson, files.toString(), 1);
    }

    public static String manifest(String version, String keyId, String paramsJson, String filesBody, int schema) {
        return "{\n  \"schema\": " + schema + ",\n  \"bundleVersion\": \"" + version + "\",\n  \"keyId\": \"" + keyId
                + "\",\n  \"createdUtc\": \"2026-10-23T00:00:00Z\",\n  \"params\": " + paramsJson
                + ",\n  \"files\": [\n" + filesBody + "\n  ]\n}\n";
    }

    public static byte[] sigLine(String keyId, byte[] manifest, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(manifest);
        return (keyId + ":" + Hex.encode(s.sign()) + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** A complete signed bundle: name -> bytes. */
    public static Map<String, byte[]> bundle(String version, KeyPair kp, Map<ModelRole, byte[]> models, String paramsJson)
            throws Exception {
        byte[] manifest = manifest(version, KEY_ID, models, paramsJson).getBytes(StandardCharsets.UTF_8);
        return withManifest(models, manifest, sigLine(KEY_ID, manifest, kp.getPrivate()));
    }

    public static Map<String, byte[]> withManifest(Map<ModelRole, byte[]> models, byte[] manifest, byte[] sig) {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        files.put("manifest.json", manifest);
        files.put("manifest.sig", sig);
        for (Map.Entry<ModelRole, byte[]> e : models.entrySet()) files.put(e.getKey().fileName(), e.getValue());
        return files;
    }

    public static String sha256(byte[] b) throws Exception {
        return Hex.encode(MessageDigest.getInstance("SHA-256").digest(b));
    }

    public static void writeDir(File dir, Map<String, byte[]> files) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            FileOutputStream out = new FileOutputStream(new File(dir, e.getKey()));
            try {
                out.write(e.getValue());
            } finally {
                out.close();
            }
        }
    }

    /** An in-memory bundle source. */
    public static final class MapSource implements BundleSource {
        private final Map<String, byte[]> files;

        public MapSource(Map<String, byte[]> files) {
            this.files = new LinkedHashMap<String, byte[]>(files);
        }

        @Override
        public byte[] read(String name) throws IOException {
            byte[] b = files.get(name);
            if (b == null) throw new FileNotFoundException("missing " + name);
            return b;
        }

        @Override
        public String describe() {
            return "memory";
        }
    }
}
