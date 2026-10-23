package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.integrity.VerifiedBundle;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.testing.GoldenFixtures;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The committed, signed bundle (models/manifest.json + manifest.sig) with the built-in dev key. */
class BundledModelsTest {

    static final String VERSION = "pad-2026.10.1";

    @Test
    void theCommittedBundleVerifiesWithTheBuiltInKey() throws Exception {
        VerifiedBundle b = VerifiedBundle.load(new ClasspathBundleSource(), TrustedKeys.builtIn());
        assertThat(b.version()).isEqualTo(VERSION);
        assertThat(b.keyId()).isEqualTo("mosip-liveness-dev-2026-10");
        assertThat(b.params()).containsEntry("pitchBiasDeg", -9.5);
        assertThat(b.sha256().get(ModelRole.DETECTOR)).isEqualTo("ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0");
        assertThat(b.sha256().get(ModelRole.LANDMARKS)).isEqualTo("4bcf3c8ce1f19627bb2e0e790850372653cb5ad39b07cc798e269e56f2e65fbb");
        assertThat(b.sha256().get(ModelRole.PAD_V2)).isEqualTo("7a08c271aedc4c7722c28be076d278163eb971e638e6bffafaac7a80f5d72665");
        assertThat(b.sha256().get(ModelRole.PAD_V1SE)).isEqualTo("d61727ec68d9b94e08dc7b07f8597d07a57985c490fdd3ea48df26f75e843b84");
        assertThat(TrustedKeys.builtIn().keyIds()).containsExactly("mosip-liveness-dev-2026-10");
    }

    @Test
    void theNoArgFactoryLoadsTheBundledModels() throws Exception {
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory()) {
            FaceAnalyzer a = f.create(), b = f.create();
            assertThat(f.modelVersion()).isEqualTo(VERSION);
            assertThat(a.modelVersion()).isEqualTo(VERSION);
            assertThat(((OnnxFaceAnalyzer) a).models()).isSameAs(((OnnxFaceAnalyzer) b).models());
            assertThat(f.effectiveConfig().pitchBiasDeg).isEqualTo(-9.5); // from the signed param
            FrameAnalysis r = a.analyze(GoldenFixtures.frame(GoldenFixtures.image("portrait_frontal")));
            assertThat(r.faceCount).isEqualTo(1);
            assertThat((double) r.livenessScore).isCloseTo(0.99504, within(1e-4));
            a.close();
            b.close();
        }
    }

    @Test
    void traceRecorderCanInstantiateTheFactoryReflectively() throws Exception {
        // As liveness-tools' TraceRecorder does: Class.forName(...).getDeclaredConstructor().newInstance()
        Object o = Class.forName("io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory")
                .getDeclaredConstructor().newInstance();
        assertThat(o).isInstanceOf(FaceAnalyzerFactory.class);
        FaceAnalyzer a = ((FaceAnalyzerFactory) o).create();
        assertThat(a.modelVersion()).isEqualTo(VERSION);
        a.close();
        ((OnnxFaceAnalyzerFactory) o).close();
    }
}
