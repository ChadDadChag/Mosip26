package io.mosip.registration.liveness.analyzer.integrity;

import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.testing.TestBundles;
import io.mosip.registration.liveness.analyzer.testing.TestBundles.MapSource;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedBundleTest {

    private static final String V = "pad-2026.10.1";
    private static KeyPair kp;
    private static TrustedKeys trust;
    private static Map<ModelRole, byte[]> models;

    @BeforeAll
    static void setUp() throws Exception {
        kp = TestBundles.keyPair();
        trust = TestBundles.trust(TestBundles.KEY_ID, kp.getPublic());
        models = TestBundles.fakeModels(1);
    }

    private static Map<String, byte[]> valid() throws Exception {
        return TestBundles.bundle(V, kp, models, "{ \"pitchBiasDeg\": -9.5 }");
    }

    /** A bundle whose manifest is exactly {@code manifestText}, correctly signed with the test key. */
    private static Map<String, byte[]> signedManifest(String manifestText) throws Exception {
        byte[] m = manifestText.getBytes(StandardCharsets.UTF_8);
        return TestBundles.withManifest(models, m, TestBundles.sigLine(TestBundles.KEY_ID, m, kp.getPrivate()));
    }

    private static String filesBody(Map<ModelRole, byte[]> ms) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ModelRole, byte[]> e : ms.entrySet()) {
            if (sb.length() > 0) sb.append(",\n");
            sb.append(TestBundles.fileEntry(e.getKey().name(), e.getKey().fileName(), e.getValue().length,
                    TestBundles.sha256(e.getValue())));
        }
        return sb.toString();
    }

    private static void assertFails(Map<String, byte[]> files, String messagePart) {
        assertFails(files, trust, messagePart);
    }

    private static void assertFails(Map<String, byte[]> files, TrustedKeys keys, String messagePart) {
        assertThatThrownBy(() -> VerifiedBundle.load(new MapSource(files), keys))
                .isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED))
                .hasMessageContaining(messagePart);
    }

    @Test
    void aValidBundleLoadsAndReturnsTheExactBytes() throws Exception {
        VerifiedBundle b = VerifiedBundle.load(new MapSource(valid()), trust);
        assertThat(b.version()).isEqualTo(V);
        assertThat(b.keyId()).isEqualTo(TestBundles.KEY_ID);
        assertThat(b.params()).containsEntry("pitchBiasDeg", -9.5);
        for (ModelRole r : ModelRole.values()) {
            assertThat(b.modelBytes().get(r)).isEqualTo(models.get(r));
            assertThat(b.fileName(r)).isEqualTo(r.fileName());
            assertThat(b.sha256().get(r)).isEqualTo(TestBundles.sha256(models.get(r)));
        }
        assertThat(b.manifestBytes()).isEqualTo(valid().get("manifest.json"));
    }

    @Test
    void oversizedManifestOrSignature() throws Exception {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 70 * 1024; i++) pad.append('x');
        String big = TestBundles.manifest(V, TestBundles.KEY_ID, "{\"pad\": 1}", filesBody(models), 1)
                .replace("2026-10-23T00:00:00Z", pad.toString());
        assertFails(signedManifest(big), "larger than 64 KiB");
        Map<String, byte[]> files = valid();
        files.put("manifest.sig", new byte[2000]);
        assertFails(files, "larger than 1 KiB");
    }

    @Test
    void malformedSignatureLines() throws Exception {
        String[] bad = {"", "no-colon\n", "test-key:\n", "test-key:ABCDEF0123456789ABCDEF\n", "test-key:00\n",
            "bad key id:" + "ab".repeat(40) + "\n", "test-key:" + "ab".repeat(40) + "\n\n", "test-key:" + "ab".repeat(41) + "x\n"};
        for (String s : bad) {
            Map<String, byte[]> files = valid();
            files.put("manifest.sig", s.getBytes(StandardCharsets.US_ASCII));
            assertFails(files, "malformed");
        }
    }

    @Test
    void unknownKeyIdAndBadSignatures() throws Exception {
        KeyPair other = TestBundles.keyPair();
        assertFails(valid(), TestBundles.trust("someone-else", other.getPublic()), "unknown signing keyId");
        assertFails(valid(), TestBundles.trust(TestBundles.KEY_ID, other.getPublic()), "bad manifest signature");
        Map<String, byte[]> flipped = valid();
        byte[] m = flipped.get("manifest.json").clone();
        m[10] ^= 1;
        flipped.put("manifest.json", m);
        assertFails(flipped, "bad manifest signature");
        Map<String, byte[]> garbage = valid();
        garbage.put("manifest.sig", ("test-key:" + "ab".repeat(70) + "\n").getBytes(StandardCharsets.US_ASCII));
        assertFails(garbage, "bad manifest signature"); // not a DER signature at all
    }

    @Test
    void invalidJsonAndSchema() throws Exception {
        assertFails(signedManifest("this is not json"), "invalid manifest.json");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", filesBody(models), 2)), "schema");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", filesBody(models), 1)
                .replace("\"schema\": 1,", "\"schema\": 1, \"extra\": true,")), "unknown manifest field");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", filesBody(models), 1)
                .replace("\"createdUtc\": \"2026-10-23T00:00:00Z\",", "")), "createdUtc");
    }

    @Test
    void invalidVersions() throws Exception {
        for (String v : new String[] {"pad-26.10.1", "pad-2026.1.1", "2026.10.1", "pad-2026.10.", "pad-2026.10.1-rc"}) {
            assertFails(signedManifest(TestBundles.manifest(v, TestBundles.KEY_ID, "{}", filesBody(models), 1)), "bundleVersion");
        }
    }

    @Test
    void manifestKeyIdMustMatchTheSignature() throws Exception {
        assertFails(signedManifest(TestBundles.manifest(V, "another-key", "{}", filesBody(models), 1)), "keyId differs");
    }

    @Test
    void filesMustBeTheFourRolesOnceEach() throws Exception {
        Map<ModelRole, byte[]> three = new LinkedHashMap<ModelRole, byte[]>(models);
        three.remove(ModelRole.PAD_V1SE);
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", filesBody(three), 1)), "exactly 4");
        String dup = filesBody(three) + ",\n" + TestBundles.fileEntry("DETECTOR", "other.onnx", 5, "ab".repeat(32));
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", dup, 1)), "listed twice");
        String unknown = filesBody(three) + ",\n" + TestBundles.fileEntry("GPU", "x.onnx", 5, "ab".repeat(32));
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", unknown, 1)), "unknown role");
        String extraField = filesBody(models).replaceFirst("\"size\"", "\"mode\": 1, \"size\"");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", extraField, 1)), "unknown file field");
    }

    @Test
    void unsafeOrInvalidFileEntries() throws Exception {
        for (String name : new String[] {"../evil.onnx", "a/b.onnx", "x..y.onnx", "back\\\\slash.onnx", ""}) {
            String body = filesBody(models).replace(ModelRole.DETECTOR.fileName(), name);
            assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", body, 1)), "file name");
        }
        String zero = filesBody(models).replaceFirst("\"size\": \\d+", "\"size\": 0");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", zero, 1)), "invalid size");
        String hash = filesBody(models).replaceFirst("\"sha256\": \"[0-9a-f]+\"", "\"sha256\": \"ABC\"");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{}", hash, 1)), "invalid sha256");
    }

    @Test
    void sizeAndHashMismatchesAndMissingFiles() throws Exception {
        Map<String, byte[]> longer = valid();
        longer.put(ModelRole.PAD_V2.fileName(), new byte[models.get(ModelRole.PAD_V2).length + 1]);
        assertFails(longer, "size mismatch for " + ModelRole.PAD_V2.fileName());
        Map<String, byte[]> tampered = valid();
        byte[] t = models.get(ModelRole.LANDMARKS).clone();
        t[0] ^= 1;
        tampered.put(ModelRole.LANDMARKS.fileName(), t);
        assertFails(tampered, "sha256 mismatch for " + ModelRole.LANDMARKS.fileName());
        Map<String, byte[]> missing = valid();
        missing.remove(ModelRole.DETECTOR.fileName());
        assertFails(missing, "missing or unreadable " + ModelRole.DETECTOR.fileName());
        Map<String, byte[]> noSig = valid();
        noSig.remove("manifest.sig");
        assertFails(noSig, "manifest.sig");
    }

    @Test
    void paramsMustBeFiniteNumbers() throws Exception {
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{\"pitchBiasDeg\": \"-9.5\"}", filesBody(models), 1)),
                "not a number");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "{\"pitchBiasDeg\": 1e400}", filesBody(models), 1)),
                "invalid manifest.json");
        assertFails(signedManifest(TestBundles.manifest(V, TestBundles.KEY_ID, "[]", filesBody(models), 1)), "params");
    }

    @Test
    void filesOver64MiBAreRefused(@TempDir File dir) throws Exception {
        TestBundles.writeDir(dir, valid());
        RandomAccessFile big = new RandomAccessFile(new File(dir, ModelRole.PAD_V2.fileName()), "rw");
        try {
            big.setLength(BundleSource.MAX_FILE_BYTES + 1L);
        } finally {
            big.close();
        }
        assertThatThrownBy(() -> VerifiedBundle.load(new DirectoryBundleSource(dir), trust))
                .isInstanceOf(LivenessEngineException.class).hasMessageContaining("larger than");
    }

    @Test
    void directoryAndClasspathSources(@TempDir File dir) throws Exception {
        TestBundles.writeDir(dir, valid());
        DirectoryBundleSource src = new DirectoryBundleSource(dir);
        assertThat(VerifiedBundle.load(src, trust).version()).isEqualTo(V);
        assertThat(src.describe()).isEqualTo(dir.getPath());
        assertThat(src.dir()).isEqualTo(dir);
        ClasspathBundleSource cp = new ClasspathBundleSource();
        assertThat(cp.describe()).isEqualTo("classpath:liveness/models/");
        assertThat(cp.read(ModelRole.DETECTOR.fileName())).hasSize(229738);
        assertThatThrownBy(() -> cp.read("nope.onnx")).isInstanceOf(java.io.FileNotFoundException.class);
        assertThatThrownBy(() -> VerifiedBundle.load(null, trust)).isInstanceOf(LivenessEngineException.class);
    }

    @Test
    void compareVersionsUsesTheNumericTuple() {
        assertThat(VerifiedBundle.compareVersions("pad-2026.10.1", "pad-2026.10.1")).isZero();
        assertThat(VerifiedBundle.compareVersions("pad-2026.10.10", "pad-2026.10.9")).isPositive();
        assertThat(VerifiedBundle.compareVersions("pad-2026.09.99", "pad-2026.10.1")).isNegative();
        assertThat(VerifiedBundle.compareVersions("pad-2027.01.1", "pad-2026.12.5")).isPositive();
        assertThatThrownBy(() -> VerifiedBundle.compareVersions("v1", "pad-2026.10.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VerifiedBundle.compareVersions("pad-2026.10.99999999999999999999", "pad-2026.10.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VerifiedBundle.isValidVersion(null)).isFalse();
    }

    @Test
    void hexAndTrustedKeys() throws Exception {
        assertThat(Hex.encode(new byte[] {0, (byte) 0xAB, 0x7F})).isEqualTo("00ab7f");
        assertThat(Hex.decode("00ab7f")).containsExactly(0, (byte) 0xAB, 0x7F);
        assertThatThrownBy(() -> Hex.decode("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hex.decode("AB")).isInstanceOf(IllegalArgumentException.class);
        String hex = Hex.encode(kp.getPublic().getEncoded());
        assertThat(TrustedKeys.parsePublicKey(hex).getEncoded()).isEqualTo(kp.getPublic().getEncoded());
        assertThatThrownBy(() -> TrustedKeys.parsePublicKey("00ff")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedKeys(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TrustedKeys(Collections.singletonMap("k", kp.getPublic())).keyIds()).containsExactly("k");
        assertThat(TrustedKeys.builtIn()).isNotNull();
    }
}
