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
 * <p>{@link #create} builds the per-frame sessions (detector, mesh, both PAD models). The {@link #LAZY} session (the
 * face matcher, F1) is needed once per capture, not per frame, and is the slowest to build, so it is built on first
 * use ({@link #ensureSession}). Until then the caller's verified bytes for it are kept; they are released once the
 * session exists.
 *
 * <p>Accelerators are best-effort: if the requested execution provider cannot be added or a model does not load
 * with it (for example NNAPI on a desktop), ALL per-frame sessions are created on the CPU instead; see
 * {@link #activeAccelerator()}. The lazy session uses the per-frame sessions' provider, and the CPU if that fails.
 *
 * <p>ONNX Runtime's own telemetry is switched off on the shared environment once per process
 * ({@code OrtEnvironment.setTelemetry(false)}); the engine works offline and sends nothing. A runtime without the
 * switch never blocks liveness.
 */
public final class OnnxModels implements AutoCloseable {

    /** The role whose session is built on first use instead of in {@link #create}. */
    public static final ModelRole LAZY = ModelRole.MATCHER;

    private static boolean telemetryAttempted; // guarded by OnnxModels.class
    private static boolean telemetryOff;       // guarded by OnnxModels.class

    private final OrtEnvironment env;
    private final Map<ModelRole, OrtSession> sessions;
    private final Accelerator active;
    private final int threads;

    private final Object lazyLock = new Object();
    private byte[] lazyBytes;       // guarded by lazyLock; null once the session exists, and after close
    private OrtSession lazySession; // guarded by lazyLock
    private boolean closed;         // guarded by lazyLock

    private OnnxModels(OrtEnvironment env, Map<ModelRole, OrtSession> sessions, Accelerator active, int threads,
                       byte[] lazyBytes) {
        this.env = env;
        this.sessions = sessions;
        this.active = active;
        this.threads = threads;
        this.lazyBytes = lazyBytes;
    }

    /** CPU sessions with the given number of intra-op threads. */
    public static OnnxModels create(Map<ModelRole, byte[]> modelBytes, int intraOpThreads) throws LivenessEngineException {
        if (intraOpThreads < 1) throw new IllegalArgumentException("intraOpThreads must be positive");
        return create(modelBytes, AnalyzerConfig.builder().intraOpThreads(intraOpThreads).build());
    }

    /**
     * Creates one session per per-frame role (ALL_OPT graph optimisation, the configured threads and accelerator) and
     * keeps the {@link #LAZY} role's bytes for {@link #ensureSession}.
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
        try (OrtSession.SessionOptions options = options(accelerator, threads)) {
            for (ModelRole role : ModelRole.values()) {
                if (role == LAZY) continue;
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
        return new OnnxModels(env, sessions, accelerator, threads, modelBytes.get(LAZY));
    }

    private static OrtSession.SessionOptions options(Accelerator accelerator, int threads) throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
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
            return options;
        } catch (OrtException e) {
            options.close();
            throw e;
        } catch (RuntimeException e) {
            options.close();
            throw e;
        }
    }

    /**
     * A per-frame role's session. For {@link #LAZY}, only once {@link #ensureSession} has built it.
     *
     * @throws IllegalStateException if the models are closed, or the lazy session is not built yet
     */
    public OrtSession session(ModelRole role) {
        if (role == LAZY) {
            synchronized (lazyLock) {
                if (lazySession == null) {
                    throw new IllegalStateException(closed ? "models closed" : role + " session not built yet");
                }
                return lazySession;
            }
        }
        OrtSession s = sessions.get(role);
        if (s == null) throw new IllegalStateException("models closed");
        return s;
    }

    /**
     * The session for {@code role}. For {@link #LAZY}, builds it on the calling thread the first time (the slow part
     * of the first face binding; see {@code OnnxFaceAnalyzerFactory.warmUpMatcher}) and then releases its bytes. A
     * failed build keeps the bytes, so a later call retries.
     *
     * @throws LivenessEngineException ENGINE_ERROR if the models are closed or ONNX Runtime rejects the model
     */
    public OrtSession ensureSession(ModelRole role) throws LivenessEngineException {
        if (role != LAZY) {
            try {
                return session(role);
            } catch (IllegalStateException e) {
                throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, e.getMessage());
            }
        }
        synchronized (lazyLock) {
            if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "models closed");
            if (lazySession == null) {
                lazySession = buildLazy(lazyBytes);
                lazyBytes = null;
            }
            return lazySession;
        }
    }

    private OrtSession buildLazy(byte[] bytes) throws LivenessEngineException {
        Exception cause;
        try {
            return createSession(bytes, active);
        } catch (OrtException e) {
            cause = e;
        } catch (RuntimeException e) {
            cause = e;
        }
        if (active != Accelerator.CPU) {
            try {
                return createSession(bytes, Accelerator.CPU);
            } catch (OrtException e) {
                cause = e;
            }
        }
        throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "ONNX Runtime could not load the " + LAZY + " model",
                cause);
    }

    private OrtSession createSession(byte[] bytes, Accelerator accelerator) throws OrtException {
        try (OrtSession.SessionOptions options = options(accelerator, threads)) {
            return env.createSession(bytes, options);
        }
    }

    /** Whether the {@link #LAZY} session has been built (tests and diagnostics). */
    public boolean lazySessionBuilt() {
        synchronized (lazyLock) {
            return lazySession != null;
        }
    }

    /** Whether the verified bytes of the {@link #LAZY} model are still held (tests and diagnostics). */
    public boolean lazyBytesRetained() {
        synchronized (lazyLock) {
            return lazyBytes != null;
        }
    }

    public OrtEnvironment env() {
        return env;
    }

    /** The execution provider the per-frame sessions actually use (CPU when the requested one was unavailable). */
    public Accelerator activeAccelerator() {
        return active;
    }

    /** Closes the sessions. The process-wide OrtEnvironment is shared and never closed here. */
    @Override
    public void close() {
        closeAll(sessions);
        synchronized (lazyLock) {
            closed = true;
            lazyBytes = null;
            if (lazySession != null) {
                try {
                    lazySession.close();
                } catch (OrtException ignored) {
                    // nothing useful to do
                }
                lazySession = null;
            }
        }
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
