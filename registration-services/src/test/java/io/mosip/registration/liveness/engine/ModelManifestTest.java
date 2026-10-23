package io.mosip.registration.liveness.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;

import org.junit.Test;

public class ModelManifestTest {

    @Test
    public void loadsTheRealBundledManifestSuccessfully() throws Exception {
        ModelManifest manifest = ModelManifest.loadAndVerify(
                HeuristicFaceAnalyzer.DEFAULT_PROPERTIES_RESOURCE,
                HeuristicFaceAnalyzer.DEFAULT_MANIFEST_RESOURCE);

        assertEquals("heuristic-pad-2026.09.1", manifest.version());
    }

    @Test
    public void failsClosedWhenThePropertiesResourceIsMissing() {
        try {
            ModelManifest.loadAndVerify("liveness/does-not-exist.properties",
                    HeuristicFaceAnalyzer.DEFAULT_MANIFEST_RESOURCE);
            fail("expected LivenessEngineException for a missing resource");
        } catch (LivenessEngineException expected) {
            assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, expected.code());
        }
    }

    @Test
    public void failsClosedWhenTheManifestResourceIsMissing() {
        try {
            ModelManifest.loadAndVerify(HeuristicFaceAnalyzer.DEFAULT_PROPERTIES_RESOURCE,
                    "liveness/does-not-exist.manifest");
            fail("expected LivenessEngineException for a missing manifest");
        } catch (LivenessEngineException expected) {
            assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, expected.code());
        }
    }

    @Test
    public void failsClosedWhenThePropertiesFileHasBeenTamperedWith() {
        // Points at the real properties resource but a manifest recording a
        // deliberately wrong checksum - simulates a tampered/corrupted model file
        // not matching its recorded manifest checksum.
        try {
            ModelManifest.loadAndVerify(HeuristicFaceAnalyzer.DEFAULT_PROPERTIES_RESOURCE,
                    "liveness/tampered-checksum.manifest");
            fail("expected LivenessEngineException for a checksum mismatch");
        } catch (LivenessEngineException expected) {
            assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, expected.code());
        }
    }
}
