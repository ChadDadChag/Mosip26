package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.LivenessEngineException;

/**
 * Creates one {@link FaceAnalyzer} per session (the contract says analyzers are not thread-safe). Called on the
 * session worker thread. Part 2 provides the implementation; it may share loaded model weights between instances.
 * Throw {@link LivenessEngineException} with MODEL_INTEGRITY_FAILED if the model manifest/checksum does not verify.
 */
public interface FaceAnalyzerFactory {
    FaceAnalyzer create() throws LivenessEngineException;
}
