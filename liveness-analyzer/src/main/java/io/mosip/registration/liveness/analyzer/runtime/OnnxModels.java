package io.mosip.registration.liveness.analyzer.runtime;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig.Accelerator;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The shared ONNX Runtime sessions, one per {@link ModelRole}. Sessions are built only from byte arrays that the
 * caller has already verified: there is deliberately no file or classpath loading here. {@code OrtSession.run}
 * is thread-safe, so every analyzer created by one factory shares these sessions.
 *
 * <p>Accelerators are best-effort: if the requested execution provider cannot be added or a model does not load
 * with it (for example NNAPI on a desktop), ALL sessions are created on the CPU instead; see
 * {@link #activeAccelerator()}.
 *
 * <p>ONNX Runtime's own telemetry is switched off on the shared environment once per process
 * ({@code OrtEnvironment.setTelemetry(false)}); the engine works offline and sends nothing. A runtime without the
 * switch never blocks liveness.
 */
public final class OnnxModels implements AutoCloseable {

    private static boolean telemetryAttempted; // guarded by OnnxModels.class
    private static boolean telemetryOff;       // guarded by OnnxModels.class

    private final OrtEnvironment env;
    private final Map<ModelRole, OrtSession> sessions;
    private final Accelerator active;

    private OnnxModels(OrtEnvironment env, Map<ModelRole, OrtSession> sessions, Accelerator active) {
        this.env = env;
        this.sessions = sessions;
        this.active = active;
    }

    /** CPU sessions with the given number of intra-op threads. */
    public static OnnxModels create(Map<ModelRole, byte[]> modelBytes, int intraOpThreads) throws LivenessEngineException {
        if (intraOpThreads < 1) throw new IllegalArgumentException("intraOpThreads must be positive");
        return create(modelBytes, AnalyzerConfig.builder().intraOpThreads(intraOpThreads).build());
    }

    /**
     * Creates one session per role (ALL_OPT graph optimisation, the configured threads and accelerator).
     *
     * @throws IllegalArgumentException if a role is missing
     * @throws LivenessEngineException  ENGINE_ERROR if ONNX Runtime rejects a model even on the CPU
     */
    public static OnnxModels create(Map<ModelRole, byte[]> modelBytes, AnalyzerConfig config) throws LivenessEngineException {
        if (modelBytes == null) throw new IllegalArgumentException("modelBytes is null");
        if (config == null) throw new IllegalArgumentException("config is null");
        for (ModelRole role : ModelRole.values()) {
            if (modelBytes.get(role) == null) throw new IllegalArgumentException("missing model for " + role);
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        disableTelemetry(env);
        ModelRole[] failed = new ModelRole[1];
        if (config.accelerator != Accelerator.CPU) {
            try {
                return build(env, modelBytes, config.intraOpThreads, config.accelerator, failed);
            } catch (OrtException e) {
                // fall through to the CPU
            } catch (RuntimeException e) {
                // e.g. a missing native provider library; fall through to the CPU
            }
        }
        try {
            return build(env, modelBytes, config.intraOpThreads, Accelerator.CPU, failed);
        } catch (OrtException e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR,
                    "ONNX Runtime could not load the " + failed[0] + " model", e);
        }
    }

    /** Switches ONNX Runtime's telemetry off, the first time only; returns whether it is off. Never throws. */
    static synchronized boolean disableTelemetry(OrtEnvironment env) {
        if (!telemetryAttempted) {
            telemetryAttempted = true;
            try {
                env.setTelemetry(false);
                telemetryOff = true;
            } catch (OrtException e) {
                // the runtime refused; liveness does not depend on it
            } catch (RuntimeException e) {
                // likewise
            } catch (LinkageError e) {
                // an older runtime without the method or its native part
            }
        }
        return telemetryOff;
    }

    private static OnnxModels build(OrtEnvironment env, Map<ModelRole, byte[]> modelBytes, int threads,
                                    Accelerator accelerator, ModelRole[] failed) throws OrtException {
        Map<ModelRole, OrtSession> sessions = new EnumMap<ModelRole, OrtSession>(ModelRole.class);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            // SFace lists its 174 initializers as graph inputs, and ONNX Runtime warns once per initializer on every
            // load (as in the Python spec, only errors are logged).
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            switch (accelerator) {
                case XNNPACK:
                    options.addXnnpack(Collections.singletonMap("intra_op_num_threads", String.valueOf(threads)));
                    options.setIntraOpNumThreads(1);
                    options.addConfigEntry("session.intra_op.allow_spinning", "0");
                    break;
                case NNAPI:
                    options.addNnapi();
                    options.setIntraOpNumThreads(threads);
                    break;
                default:
                    options.setIntraOpNumThreads(threads);
                    break;
            }
            for (ModelRole role : ModelRole.values()) {
                failed[0] = role;
                sessions.put(role, env.createSession(modelBytes.get(role), options));
            }
        } catch (OrtException e) {
            closeAll(sessions);
            throw e;
        } catch (RuntimeException e) {
            closeAll(sessions);
            throw e;
        }
        return new OnnxModels(env, sessions, accelerator);
    }

    public OrtSession session(ModelRole role) {
        OrtSession s = sessions.get(role);
        if (s == null) throw new IllegalStateException("models closed");
        return s;
    }

    public OrtEnvironment env() {
        return env;
    }

    /** The execution provider the sessions actually use (CPU when the requested one was unavailable). */
    public Accelerator activeAccelerator() {
        return active;
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
