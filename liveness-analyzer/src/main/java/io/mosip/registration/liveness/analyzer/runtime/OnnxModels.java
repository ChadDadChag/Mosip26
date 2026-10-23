package io.mosip.registration.liveness.analyzer.runtime;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.util.EnumMap;
import java.util.Map;

/**
 * The shared ONNX Runtime sessions, one per {@link ModelRole}. Sessions are built only from byte arrays that the
 * caller has already verified: there is deliberately no file or classpath loading here. {@code OrtSession.run}
 * is thread-safe, so every analyzer created by one factory shares these sessions.
 */
public final class OnnxModels implements AutoCloseable {

    private final OrtEnvironment env;
    private final Map<ModelRole, OrtSession> sessions;

    private OnnxModels(OrtEnvironment env, Map<ModelRole, OrtSession> sessions) {
        this.env = env;
        this.sessions = sessions;
    }

    /**
     * Creates one session per role from the given model bytes (ALL_OPT graph optimisation, the given number of
     * intra-op threads).
     *
     * @throws IllegalArgumentException if a role is missing or the thread count is not positive
     * @throws LivenessEngineException  ENGINE_ERROR if ONNX Runtime rejects a model
     */
    public static OnnxModels create(Map<ModelRole, byte[]> modelBytes, int intraOpThreads) throws LivenessEngineException {
        if (modelBytes == null) throw new IllegalArgumentException("modelBytes is null");
        if (intraOpThreads < 1) throw new IllegalArgumentException("intraOpThreads must be positive");
        for (ModelRole role : ModelRole.values()) {
            if (modelBytes.get(role) == null) throw new IllegalArgumentException("missing model for " + role);
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        Map<ModelRole, OrtSession> sessions = new EnumMap<ModelRole, OrtSession>(ModelRole.class);
        ModelRole current = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setIntraOpNumThreads(intraOpThreads);
            for (ModelRole role : ModelRole.values()) {
                current = role;
                sessions.put(role, env.createSession(modelBytes.get(role), options));
            }
        } catch (OrtException e) {
            closeAll(sessions);
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR,
                    "ONNX Runtime could not load the " + current + " model", e);
        }
        return new OnnxModels(env, sessions);
    }

    public OrtSession session(ModelRole role) {
        OrtSession s = sessions.get(role);
        if (s == null) throw new IllegalStateException("models closed");
        return s;
    }

    public OrtEnvironment env() {
        return env;
    }

    /** Closes the sessions. The process-wide OrtEnvironment is shared and never closed here. */
    @Override
    public void close() {
        closeAll(sessions);
    }

    private static void closeAll(Map<ModelRole, OrtSession> sessions) {
        for (OrtSession s : sessions.values()) {
            try {
                s.close();
            } catch (OrtException ignored) {
                // nothing useful to do; the next session still gets closed
            }
        }
        sessions.clear();
    }
}
