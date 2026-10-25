package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.integrity.Hex;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBundleToolTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream(), errBuf = new ByteArrayOutputStream();

    private int run(String... args) {
        outBuf.reset();
        errBuf.reset();
        return ModelBundleTool.run(args, new PrintStream(outBuf, true), new PrintStream(errBuf, true));
    }

    private String out() {
        return new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
    }

    private String err() {
        return new String(errBuf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void fakeModels(File dir) throws Exception {
        Random r = new Random(3);
        for (ModelRole role : ModelRole.values()) {
            byte[] b = new byte[200 + role.ordinal()];
            r.nextBytes(b);
            Files.write(new File(dir, role.fileName()).toPath(), b);
        }
    }

    /** keygen into a temp dir (outside the repo) and return the public hex it printed. */
    private String keygen(File key, String keyId) {
        assertThat(run("keygen", "--key-id", keyId, "--out", key.getPath())).isEqualTo(0);
        Matcher m = Pattern.compile("public key \\(X\\.509 SubjectPublicKeyInfo, hex\\): ([0-9a-f]+)").matcher(out());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void manifestSignVerifyRoundTrip(@TempDir File tmp) throws Exception {
        File models = new File(tmp, "models");
        assertThat(models.mkdir()).isTrue();
        fakeModels(models);
        File key = new File(tmp, "keys/test.p8");
        String pub = keygen(key, "unit-test-key");
        assertThat(key).exists();

        assertThat(run("manifest", "--dir", models.getPath(), "--version", "pad-2026.10.1", "--key-id", "unit-test-key",
                "--param", "pitchBiasDeg=-9.5", "--created", "2026-10-23T00:00:00Z")).isEqualTo(0);
        String manifest = new String(Files.readAllBytes(new File(models, "manifest.json").toPath()), StandardCharsets.UTF_8);
        assertThat(manifest).startsWith("{\n  \"schema\": 1,\n  \"bundleVersion\": \"pad-2026.10.1\",\n  \"keyId\": \"unit-test-key\",\n"
                + "  \"createdUtc\": \"2026-10-23T00:00:00Z\",\n  \"params\": { \"pitchBiasDeg\": -9.5 },\n  \"files\": [\n"
                + "    {\"role\": \"DETECTOR\", \"name\": \"yunet_2026may.onnx\", \"size\": 200, \"sha256\": \"");
        assertThat(manifest).endsWith("\"}\n  ]\n}\n").doesNotContain("\r");

        assertThat(run("sign", "--dir", models.getPath(), "--key", key.getPath(), "--key-id", "unit-test-key")).isEqualTo(0);
        String sig = new String(Files.readAllBytes(new File(models, "manifest.sig").toPath()), StandardCharsets.US_ASCII);
        assertThat(sig).matches("unit-test-key:[0-9a-f]+\n");

        assertThat(run("verify", "--dir", models.getPath(), "--trust", "unit-test-key=" + pub)).isEqualTo(0);
        assertThat(out()).contains("OK pad-2026.10.1").contains("pitchBiasDeg=-9.5").contains("yunet_2026may.onnx");

        // Built-in keys do not include this throwaway key: verify fails closed.
        assertThat(run("verify", "--dir", models.getPath())).isEqualTo(1);
        assertThat(err()).contains("MODEL_INTEGRITY_FAILED");

        // Tampering with a model after signing is caught.
        byte[] b = Files.readAllBytes(new File(models, ModelRole.PAD_V2.fileName()).toPath());
        b[0] ^= 1;
        Files.write(new File(models, ModelRole.PAD_V2.fileName()).toPath(), b);
        assertThat(run("verify", "--dir", models.getPath(), "--trust", "unit-test-key=" + pub)).isEqualTo(1);
        assertThat(err()).contains("sha256 mismatch");
    }

    @Test
    void installAndRollbackThroughTheTool(@TempDir File tmp) throws Exception {
        File key = new File(tmp, "k.p8");
        String pub = keygen(key, "k1");
        String trust = "k1=" + pub;
        File store = new File(tmp, "store");
        for (String v : new String[] {"pad-2026.10.1", "pad-2026.10.2"}) {
            File d = new File(tmp, v);
            assertThat(d.mkdir()).isTrue();
            fakeModels(d);
            assertThat(run("manifest", "--dir", d.getPath(), "--version", v, "--key-id", "k1")).isEqualTo(0);
            assertThat(run("sign", "--dir", d.getPath(), "--key", key.getPath(), "--key-id", "k1")).isEqualTo(0);
            assertThat(run("install", "--store", store.getPath(), "--from", d.getPath(), "--trust", trust)).isEqualTo(0);
            assertThat(out()).contains("installed " + v);
        }
        assertThat(run("install", "--store", store.getPath(), "--from", new File(tmp, "pad-2026.10.1").getPath(),
                "--trust", trust)).isEqualTo(1);
        assertThat(err()).contains("not newer than pad-2026.10.2");
        assertThat(run("rollback", "--store", store.getPath(), "--trust", trust)).isEqualTo(0);
        assertThat(out()).contains("current is now pad-2026.10.1");
        assertThat(run("rollback", "--store", store.getPath(), "--trust", trust)).isEqualTo(1);
    }

    @Test
    void packWritesTheZipAndTheIndexForTheUpdater(@TempDir File tmp) throws Exception {
        File key = new File(tmp, "k.p8");
        String trust = "k1=" + keygen(key, "k1");
        File d = new File(tmp, "bundle");
        assertThat(d.mkdir()).isTrue();
        fakeModels(d);
        assertThat(run("manifest", "--dir", d.getPath(), "--version", "pad-2026.10.7", "--key-id", "k1")).isEqualTo(0);
        assertThat(run("sign", "--dir", d.getPath(), "--key", key.getPath(), "--key-id", "k1")).isEqualTo(0);
        File site = new File(tmp, "site");

        assertThat(run("pack", "--dir", d.getPath(), "--out", site.getPath(), "--base-url", "http://127.0.0.1:8000/",
                "--trust", trust)).isEqualTo(0);
        byte[] zip = Files.readAllBytes(new File(site, "pad-2026.10.7.zip").toPath());
        Map<String, Object> index = MiniJson.parseObject(
                new String(Files.readAllBytes(new File(site, "latest.json").toPath()), StandardCharsets.UTF_8));
        assertThat(index.get("schema")).isEqualTo(1.0);
        assertThat(index.get("bundleVersion")).isEqualTo("pad-2026.10.7");
        assertThat(index.get("zipUrl")).isEqualTo("http://127.0.0.1:8000/pad-2026.10.7.zip");
        assertThat(index.get("zipSha256")).isEqualTo(Hex.encode(MessageDigest.getInstance("SHA-256").digest(zip)));
        assertThat(index.get("zipSize")).isEqualTo((double) zip.length);
        List<String> names = new ArrayList<String>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) names.add(e.getName());
        }
        assertThat(names).startsWith("manifest.json", "manifest.sig").hasSize(2 + ModelRole.values().length)
                .contains(ModelRole.MATCHER.fileName());

        // A tampered bundle is not packed; a missing option is a usage error.
        Files.write(new File(d, ModelRole.PAD_V2.fileName()).toPath(), new byte[] {1});
        File site2 = new File(tmp, "site2");
        assertThat(run("pack", "--dir", d.getPath(), "--out", site2.getPath(), "--base-url", "https://example.org/m",
                "--trust", trust)).isEqualTo(1);
        assertThat(err()).contains("MODEL_INTEGRITY_FAILED");
        assertThat(site2).doesNotExist();
        assertThat(run("pack", "--dir", d.getPath(), "--out", site.getPath())).isEqualTo(2);
    }

    @Test
    void keygenRefusesPathsInsideTheRepository() throws Exception {
        File inside = new File("target/should-not-exist/key.p8").getAbsoluteFile();
        assertThat(ModelBundleTool.enclosingGitWorkTree(inside)).isNotNull();
        assertThat(run("keygen", "--key-id", "x", "--out", inside.getPath())).isEqualTo(1);
        assertThat(err()).contains("REFUSED").contains("git working tree");
        assertThat(inside).doesNotExist();
    }

    @Test
    void keygenRefusesToOverwriteWithoutForce(@TempDir File tmp) throws Exception {
        File key = new File(tmp, "k.p8");
        keygen(key, "k");
        byte[] first = Files.readAllBytes(key.toPath());
        assertThat(run("keygen", "--key-id", "k", "--out", key.getPath())).isEqualTo(1);
        assertThat(Files.readAllBytes(key.toPath())).isEqualTo(first);
        assertThat(run("keygen", "--key-id", "k", "--out", key.getPath(), "--force")).isEqualTo(0);
        assertThat(Files.readAllBytes(key.toPath())).isNotEqualTo(first);
        assertThat(ModelBundleTool.enclosingGitWorkTree(tmp)).isNull();
    }

    @Test
    void signRefusesAKeyIdMismatch(@TempDir File tmp) throws Exception {
        fakeModels(tmp);
        File key = new File(tmp, "k.p8");
        keygen(key, "real-key");
        assertThat(run("manifest", "--dir", tmp.getPath(), "--version", "pad-2026.10.1", "--key-id", "real-key")).isEqualTo(0);
        assertThat(run("sign", "--dir", tmp.getPath(), "--key", key.getPath(), "--key-id", "other-key")).isEqualTo(1);
        assertThat(err()).contains("declares keyId 'real-key'");
        assertThat(new File(tmp, "manifest.sig")).doesNotExist();
    }

    @Test
    void usageErrors(@TempDir File tmp) throws Exception {
        fakeModels(tmp);
        assertThat(run()).isEqualTo(2);
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(run("keygen")).isEqualTo(2);
        assertThat(run("keygen", "--key-id", "bad id")).isEqualTo(2);
        assertThat(run("verify", "--dir")).isEqualTo(2);
        assertThat(run("verify", "stray")).isEqualTo(2);
        assertThat(run("verify", "--dir", new File(tmp, "nope").getPath())).isEqualTo(2);
        String d = tmp.getPath();
        assertThat(run("manifest", "--dir", d, "--version", "v1", "--key-id", "k")).isEqualTo(2);
        assertThat(run("manifest", "--dir", d, "--version", "pad-2026.10.1", "--key-id", "k", "--param", "x")).isEqualTo(2);
        assertThat(run("manifest", "--dir", d, "--version", "pad-2026.10.1", "--key-id", "k", "--param", "x=abc")).isEqualTo(2);
        assertThat(run("manifest", "--dir", d, "--version", "pad-2026.10.1", "--key-id", "k", "--param", "x=NaN")).isEqualTo(2);
        assertThat(run("manifest", "--dir", d, "--version", "pad-2026.10.1", "--key-id", "k", "--param", "1x=2")).isEqualTo(2);
        assertThat(run("manifest", "--dir", d, "--version", "pad-2026.10.1", "--key-id", "k", "--param", "x=1", "--param", "x=2"))
                .isEqualTo(2);
        assertThat(run("sign", "--dir", d, "--key", new File(tmp, "missing.p8").getPath(), "--key-id", "k")).isEqualTo(2);
        assertThat(run("verify", "--dir", d, "--trust", "nokey")).isEqualTo(2);
        assertThat(run("verify", "--dir", d, "--trust", "k=00ff")).isEqualTo(2);
        assertThat(run("verify", "--dir", d, "--dir", d)).isEqualTo(2);
        assertThat(err()).contains("usage: ModelBundleTool");
    }
}
