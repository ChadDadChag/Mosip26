package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig.Accelerator;
import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.integrity.VerifiedBundle;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;

/**
 * Creates {@link OnnxFaceAnalyzer}s that share one set of verified ONNX sessions.
 *
 * <p>The first {@link #create()} (or {@link #preload()}) loads the model bundle through {@link VerifiedBundle} (signed
 * manifest, sizes, SHA-256) and builds the sessions from those same verified bytes. Only a successful load is cached:
 * a failure is re-tried, and re-thrown as MODEL_INTEGRITY_FAILED or ENGINE_ERROR, on every create(). There is no
 * fallback to unverified models.
 *
 * <p>With a {@link ModelStore}, the bundled bundle must verify, and the store's current one is used, and must verify,
 * only if its version is higher. An older or equal store bundle is not read: it would not be used, and after an app
 * update it may lack a model role the app now requires. The bundle's signed {@code params.pitchBiasDeg} replaces the
 * config's pitch bias unless the host set it explicitly ({@link AnalyzerConfig#pitchBiasExplicit}).
 */
public final class OnnxFaceAnalyzerFactory implements FaceAnalyzerFactory, AutoCloseable {

    static final String PITCH_BIAS_PARAM = "pitchBiasDeg";

    private final BundleSource bundled;
    private final ModelStore store;
    private final TrustedKeys keys;
    private final AnalyzerConfig config;

    private OnnxModels models;
    private AnalyzerConfig effective;
    private String version;
    private boolean closed;

    /** The bundled models, the built-in keys and the default config (used reflectively by TraceRecorder). */
    public OnnxFaceAnalyzerFactory() {
        this(new ClasspathBundleSource(), null, TrustedKeys.builtIn(), AnalyzerConfig.defaults());
    }

    public OnnxFaceAnalyzerFactory(BundleSource source, AnalyzerConfig config) {
        this(source, null, TrustedKeys.builtIn(), config);
    }

    /** The bundled models or the store's current bundle, whichever is newer. */
    public OnnxFaceAnalyzerFactory(ModelStore store, AnalyzerConfig config) {
        this(new ClasspathBundleSource(), store, TrustedKeys.builtIn(), config);
    }

    public OnnxFaceAnalyzerFactory(BundleSource bundled, ModelStore storeOrNull, TrustedKeys keys, AnalyzerConfig config) {
        if (bundled == null || keys == null || config == null) throw new IllegalArgumentException("null argument");
        this.bundled = bundled;
        this.store = storeOrNull;
        this.keys = keys;
        this.config = config;
    }

    @Override
    public FaceAnalyzer create() throws LivenessEngineException {
        synchronized (this) {
            ensureLoaded();
            return new OnnxFaceAnalyzer(models, effective, version);
        }
    }

    /**
     * A face matcher (F1, face binding) on this factory's verified sessions and effective config (loads them if
     * needed). Closing the factory makes the matcher fail with ENGINE_ERROR.
     */
    public FaceMatcher createMatcher() throws LivenessEngineException {
        synchronized (this) {
            ensureLoaded();
            return new FaceMatcher(models, effective);
        }
    }

    /** Verifies the bundle and builds the sessions now instead of on the first create(). */
    public synchronized void preload() throws LivenessEngineException {
        ensureLoaded();
    }

    /** The version of the bundle in use (loads it if needed). */
    public synchronized String modelVersion() throws LivenessEngineException {
        ensureLoaded();
        return version;
    }

    /** The execution provider the sessions actually use (loads them if needed). */
    public synchronized Accelerator activeAccelerator() throws LivenessEngineException {
        ensureLoaded();
        return models.activeAccelerator();
    }

    /** The effective config: the given one with the bundle's pitch bias applied where appropriate. */
    public synchronized AnalyzerConfig effectiveConfig() throws LivenessEngineException {
        ensureLoaded();
        return effective;
    }

    private void ensureLoaded() throws LivenessEngineException {
        if (closed) throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "analyzer factory is closed");
        if (models != null) return;
        VerifiedBundle chosen = VerifiedBundle.load(bundled, keys);
        if (store != null) {
            VerifiedBundle stored = store.loadCurrentIfNewerThan(chosen.version(), keys);
            if (stored != null) chosen = stored;
        }
        AnalyzerConfig eff = config;
        Double bias = chosen.params().get(PITCH_BIAS_PARAM);
        if (bias != null && !config.pitchBiasExplicit) {
            try {
                eff = config.toBuilder().pitchBiasDeg(bias).pitchBiasExplicit(false).build();
            } catch (IllegalArgumentException e) {
                throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                        "bundle " + chosen.version() + " has an invalid " + PITCH_BIAS_PARAM + " param");
            }
        }
        models = OnnxModels.create(chosen.modelBytes(), eff);
        effective = eff;
        version = chosen.version();
    }

    /** Closes the shared sessions; analyzers created earlier stop working and create() fails with ENGINE_ERROR. */
    @Override
    public synchronized void close() {
        closed = true;
        if (models != null) {
            models.close();
            models = null;
        }
    }
}
