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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelStoreTest {

    private static final String V1 = "pad-2026.10.1", V2 = "pad-2026.10.2", V3 = "pad-2026.11.1";
    private static KeyPair kp;
    private static TrustedKeys trust;

    @BeforeAll
    static void setUp() throws Exception {
        kp = TestBundles.keyPair();
        trust = TestBundles.trust(TestBundles.KEY_ID, kp.getPublic());
    }

    private static MapSource staged(String version) throws Exception {
        return new MapSource(TestBundles.bundle(version, kp, TestBundles.fakeModels(version.hashCode()), "{}"));
    }

    private static void assertIntegrityFailure(ModelStore store) {
        assertThatThrownBy(() -> store.loadCurrent(trust)).isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED));
    }

    private static void write(File f, String text) throws Exception {
        ModelStore.writeSynced(f, text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void anEmptyStoreHasNoCurrentBundle(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        assertThat(store.loadCurrent(trust)).isNull();
        assertThat(store.currentVersion()).isNull();
        assertThat(store.installedVersions()).isEmpty();
        assertThat(store.root()).isEqualTo(root);
        assertThatThrownBy(() -> store.rollback(trust)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void installsANewerVersionAndLoadsIt(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        assertThat(store.install(staged(V1), trust)).isEqualTo(V1);
        assertThat(store.install(staged(V2), trust)).isEqualTo(V2);
        VerifiedBundle b = store.loadCurrent(trust);
        assertThat(b.version()).isEqualTo(V2);
        assertThat(b.modelBytes().get(ModelRole.LANDMARKS)).isEqualTo(TestBundles.fakeModels(V2.hashCode()).get(ModelRole.LANDMARKS));
        assertThat(store.installedVersions()).containsExactly(V1, V2);
        assertThat(new File(root, "current.tmp")).doesNotExist();
    }

    @Test
    void sameOrOlderVersionsAreRejectedAndTheStoreIsUnchanged(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V2), trust);
        assertThatThrownBy(() -> store.install(staged(V2), trust)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not newer than " + V2);
        assertThatThrownBy(() -> store.install(staged(V1), trust)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not newer than " + V2);
        assertThat(store.currentVersion()).isEqualTo(V2);
        assertThat(store.installedVersions()).containsExactly(V2);
        assertThat(new File(root, "bundles").list()).containsExactly(V2);
    }

    @Test
    void aTamperedStagedBundleIsRejectedAndTheStoreIsUnchanged(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        Map<String, byte[]> files = TestBundles.bundle(V2, kp, TestBundles.fakeModels(7), "{}");
        byte[] model = files.get(ModelRole.PAD_V2.fileName()).clone();
        model[3] ^= 1;
        files.put(ModelRole.PAD_V2.fileName(), model);
        assertThatThrownBy(() -> store.install(new MapSource(files), trust)).isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED));
        assertThat(store.currentVersion()).isEqualTo(V1);
        assertThat(new File(root, "bundles").list()).containsExactly(V1);
    }

    @Test
    void leftoverTemporaryDirectoriesAreIgnoredThenCleanedUp(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        File leftover = new File(new File(root, "bundles"), V2 + ".tmp-deadbeef");
        assertThat(leftover.mkdirs()).isTrue();
        write(new File(leftover, "manifest.json"), "half-written");
        assertThat(store.loadCurrent(trust).version()).isEqualTo(V1);
        assertThat(store.installedVersions()).containsExactly(V1);
        store.install(staged(V2), trust);
        assertThat(leftover).doesNotExist();
        assertThat(new File(root, "bundles").list()).containsExactlyInAnyOrder(V1, V2);
    }

    @Test
    void anInterruptedPointerSwitchIsCompleted(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        store.install(staged(V2), trust);
        // Crash after "delete current" and before "rename current.tmp": only current.tmp is left.
        assertThat(new File(root, "current").delete()).isTrue();
        write(new File(root, "current.tmp"), V2 + "\n");
        assertThat(store.loadCurrent(trust).version()).isEqualTo(V2);
        assertThat(new File(root, "current")).exists();
        assertThat(new File(root, "current.tmp")).doesNotExist();
        // A current.tmp naming a bundle that does not verify is ignored.
        assertThat(new File(root, "current").delete()).isTrue();
        write(new File(root, "current.tmp"), "pad-2030.01.1\n");
        assertThat(store.loadCurrent(trust)).isNull();
    }

    @Test
    void rollbackIsAnExplicitOperatorAction(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        assertThatThrownBy(() -> store.rollback(trust)).isInstanceOf(IllegalStateException.class);
        store.install(staged(V2), trust);
        assertThat(store.rollback(trust)).isEqualTo(V1);
        assertThat(store.loadCurrent(trust).version()).isEqualTo(V1);
        // V2 is still installed and newer, so it may be installed again (it replaces the stale copy).
        assertThat(store.install(staged(V2), trust)).isEqualTo(V2);
    }

    @Test
    void pruningKeepsTheCurrentAndThePreviousBundle(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        store.install(staged(V2), trust);
        store.install(staged(V3), trust);
        assertThat(store.installedVersions()).containsExactly(V2, V3);
    }

    @Test
    void aByteFlippedOnDiskFailsClosed(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        RandomAccessFile f = new RandomAccessFile(new File(new File(new File(root, "bundles"), V1), ModelRole.DETECTOR.fileName()), "rw");
        try {
            f.seek(5);
            int b = f.read();
            f.seek(5);
            f.write(b ^ 0x40);
        } finally {
            f.close();
        }
        assertIntegrityFailure(store);
    }

    @Test
    void pointerDirectoryAndManifestVersionMustAgree(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(staged(V1), trust);
        File bundles = new File(root, "bundles");
        // A copy of V1's files under another version's directory name.
        File fake = new File(bundles, "pad-2026.12.1");
        assertThat(fake.mkdir()).isTrue();
        for (File f : new File(bundles, V1).listFiles()) {
            ModelStore.writeSynced(new File(fake, f.getName()), java.nio.file.Files.readAllBytes(f.toPath()));
        }
        write(new File(root, "current"), "pad-2026.12.1\n");
        assertIntegrityFailure(store);
        write(new File(root, "current"), "garbage\n");
        assertIntegrityFailure(store);
        write(new File(root, "current"), "pad-2027.01.1\n"); // points at nothing
        assertIntegrityFailure(store);
    }
}
