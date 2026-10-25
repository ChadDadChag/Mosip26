package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.analyzer.testing.TestBundles;
import io.mosip.registration.liveness.analyzer.testing.TestBundles.MapSource;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.LivenessEngineException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.security.KeyPair;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The factory over real models signed with a throwaway test key. */
class OnnxFaceAnalyzerFactoryTest {

    private static KeyPair kp;
    private static TrustedKeys trust;
    private static Map<ModelRole, byte[]> realModels;

    @BeforeAll
    static void setUp() throws Exception {
        kp = TestBundles.keyPair();
        trust = TestBundles.trust(TestBundles.KEY_ID, kp.getPublic());
        realModels = TestModels.load();
    }

    private static MapSource bundle(String version, String params) throws Exception {
        return new MapSource(TestBundles.bundle(version, kp, realModels, params));
    }

    private static void assertCode(ThrowingRunnable r, ErrorCode code) {
        assertThatThrownBy(r::run).isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(code));
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void analyzersShareOneSetOfVerifiedSessions() throws Exception {
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"), null, trust,
                AnalyzerConfig.defaults())) {
            FaceAnalyzer a = f.create(), b = f.create();
            assertThat(a).isNotSameAs(b);
            assertThat(((OnnxFaceAnalyzer) a).models()).isSameAs(((OnnxFaceAnalyzer) b).models());
            assertThat(a.modelVersion()).isEqualTo("pad-2026.10.1");
            assertThat(f.modelVersion()).isEqualTo("pad-2026.10.1");
            assertThat(f.activeAccelerator()).isEqualTo(AnalyzerConfig.Accelerator.CPU);
            assertThat(a.analyze(GoldenFixtures.frame(GoldenFixtures.image("portrait_frontal"))).faceCount).isEqualTo(1);
            a.close();
            b.close();
        }
    }

    @Test
    void aTamperedSourceFailsOnEveryCreate() throws Exception {
        Map<String, byte[]> files = TestBundles.bundle("pad-2026.10.1", kp, realModels, "{}");
        byte[] mesh = files.get(ModelRole.LANDMARKS.fileName()).clone();
        mesh[1000] ^= 1;
        files.put(ModelRole.LANDMARKS.fileName(), mesh);
        OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(new MapSource(files), null, trust, AnalyzerConfig.defaults());
        assertCode(f::create, ErrorCode.MODEL_INTEGRITY_FAILED);
        assertCode(f::create, ErrorCode.MODEL_INTEGRITY_FAILED);
        assertCode(f::preload, ErrorCode.MODEL_INTEGRITY_FAILED);
        // A factory trusting nothing (no built-in or matching key) also fails closed.
        OnnxFaceAnalyzerFactory untrusted = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"), null,
                new TrustedKeys(java.util.Collections.<String, java.security.PublicKey>emptyMap()), AnalyzerConfig.defaults());
        assertCode(untrusted::create, ErrorCode.MODEL_INTEGRITY_FAILED);
    }

    @Test
    void theNewerOfBundledAndStoredIsUsed(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(bundle("pad-2026.11.1", "{}"), trust);
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"), store, trust,
                AnalyzerConfig.defaults())) {
            assertThat(f.modelVersion()).isEqualTo("pad-2026.11.1");
        }
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.12.1", "{}"), store, trust,
                AnalyzerConfig.defaults())) {
            assertThat(f.modelVersion()).isEqualTo("pad-2026.12.1");
        }
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"),
                new ModelStore(new File(root, "empty")), trust, AnalyzerConfig.defaults())) {
            assertThat(f.modelVersion()).isEqualTo("pad-2026.10.1");
        }
    }

    @Test
    void aStoreThatFailsVerificationFailsClosed(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        store.install(bundle("pad-2026.11.1", "{}"), trust);
        File model = new File(new File(new File(root, "bundles"), "pad-2026.11.1"), ModelRole.PAD_V2.fileName());
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(model, "rw");
        try {
            raf.seek(100);
            int b = raf.read();
            raf.seek(100);
            raf.write(b ^ 1);
        } finally {
            raf.close();
        }
        OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"), store, trust,
                AnalyzerConfig.defaults());
        assertCode(f::create, ErrorCode.MODEL_INTEGRITY_FAILED);
    }

    @Test
    void anOlderStoreBundleIsNotReadSoAPreF1OneDoesNotBlockTheApp(@TempDir File root) throws Exception {
        // A device that installed pad-2026.10.1 (four models), then got the app whose bundled models have five roles.
        TestBundles.writeDir(new File(new File(root, "bundles"), "pad-2026.10.1"),
                TestBundles.bundleWithoutMatcher("pad-2026.10.1", kp, TestBundles.fakeModels(1)));
        TestBundles.writeDir(root, java.util.Collections.singletonMap("current",
                "pad-2026.10.1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        ModelStore store = new ModelStore(root);
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.4", "{}"), store, trust,
                AnalyzerConfig.defaults())) {
            assertThat(f.modelVersion()).isEqualTo("pad-2026.10.4");
        }
        // Were the store bundle newer than the app's, it would be used, so it must verify.
        OnnxFaceAnalyzerFactory older = new OnnxFaceAnalyzerFactory(bundle("pad-2026.09.1", "{}"), store, trust,
                AnalyzerConfig.defaults());
        assertCode(older::create, ErrorCode.MODEL_INTEGRITY_FAILED);
    }

    @Test
    void thePitchBiasComesFromTheSignedParamUnlessSetExplicitly() throws Exception {
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{\"pitchBiasDeg\": -7.25}"),
                null, trust, AnalyzerConfig.defaults())) {
            assertThat(f.effectiveConfig().pitchBiasDeg).isEqualTo(-7.25);
            assertThat(((OnnxFaceAnalyzer) f.create()).config().pitchBiasDeg).isEqualTo(-7.25);
        }
        AnalyzerConfig explicit = AnalyzerConfig.builder().pitchBiasDeg(-9.5).build();
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{\"pitchBiasDeg\": -7.25}"),
                null, trust, explicit)) {
            assertThat(f.effectiveConfig().pitchBiasDeg).isEqualTo(-9.5);
        }
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"),
                null, trust, AnalyzerConfig.defaults())) {
            assertThat(f.effectiveConfig().pitchBiasDeg).isEqualTo(-9.5);
        }
        OnnxFaceAnalyzerFactory bad = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{\"pitchBiasDeg\": 100}"),
                null, trust, AnalyzerConfig.defaults());
        assertCode(bad::create, ErrorCode.MODEL_INTEGRITY_FAILED);
    }

    @Test
    void createAfterCloseIsAnEngineError() throws Exception {
        OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundle("pad-2026.10.1", "{}"), null, trust,
                AnalyzerConfig.defaults());
        FaceAnalyzer a = f.create();
        f.close();
        f.close(); // idempotent
        assertCode(f::create, ErrorCode.ENGINE_ERROR);
        // An analyzer created before close() can no longer run: its sessions are closed.
        assertCode(() -> a.analyze(GoldenFixtures.frame(GoldenFixtures.image("portrait_frontal"))), ErrorCode.ENGINE_ERROR);
        assertThatThrownBy(() -> new OnnxFaceAnalyzerFactory(null, null, trust, AnalyzerConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convenienceConstructorsDoNotLoadEagerly(@TempDir File root) {
        // Construction never throws (TraceRecorder instantiates the no-arg factory reflectively).
        new OnnxFaceAnalyzerFactory().close();
        new OnnxFaceAnalyzerFactory(new MapSource(java.util.Collections.<String, byte[]>emptyMap()), AnalyzerConfig.defaults()).close();
        new OnnxFaceAnalyzerFactory(new ModelStore(root), AnalyzerConfig.defaults()).close();
    }
}
