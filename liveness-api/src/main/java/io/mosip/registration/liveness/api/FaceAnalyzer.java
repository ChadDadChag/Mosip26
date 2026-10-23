package io.mosip.registration.liveness.api;

/**
 * Runs ALL per-frame ML once: face detection, landmarks, head pose, quality, passive liveness/PAD.
 * Owner: Person 2. Pure Java + ONNX Runtime (same ai.onnxruntime API on Desktop and Android).
 * Not thread-safe: one instance per session, called from the session worker thread only.
 */
public interface FaceAnalyzer extends AutoCloseable {
    FrameAnalysis analyze(Frame frame) throws LivenessEngineException;
    /** Version string from the verified model manifest, e.g. "pad-2026.09.1". Logged in audit. */
    String modelVersion();
    @Override void close();
}
