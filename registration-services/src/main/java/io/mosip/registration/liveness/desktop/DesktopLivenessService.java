package io.mosip.registration.liveness.desktop;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.match.MatchResult;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.metrics.LivenessMetrics;
import io.mosip.registration.liveness.device.mock.MockFrameSource;
import io.mosip.registration.liveness.device.mock.SarxosWebcamFrameGrabber;
import io.mosip.registration.liveness.device.sbi.SbiStreamFrameSourceFactory;
import io.mosip.registration.liveness.fake.FakeLivenessService;
import io.mosip.registration.liveness.fake.FakeScenario;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;
import io.mosip.registration.service.bio.BioService;
import io.mosip.registration.util.advice.AuthenticationAdvice;

/**
 * The Desktop client's single entry point to face liveness: Parts 1 to 3 wired into the Registration Client.
 *
 * <ul>
 * <li><b>Engine:</b> {@link DefaultLivenessService} (Part 3) with the ONNX analyzer (Part 2), whose models load only
 * from the signed bundle in the analyzer jar, or from a newer signed bundle installed in the offline model store.
 * Everything runs on this machine, so liveness behaves the same online and offline.</li>
 * <li><b>Device:</b> the face device's SBI {@code /stream} through Part 1's {@link SbiStreamFrameSourceFactory}, i.e.
 * the client's existing MDS discovery: any compliant L0/L1 face device, no vendor SDK.</li>
 * <li><b>Config:</b> {@link DesktopLivenessConfig}, the synced global config. A change (after a config sync) is picked
 * up before the next session; a running session keeps its policy.</li>
 * <li><b>Audit:</b> {@link DesktopAuditSink}, the client's audit module.</li>
 * <li><b>Face binding:</b> {@link #binding()}.</li>
 * <li><b>Online model update:</b> after each successful config sync ({@link ConfigSyncedEvent}), when enabled, a newer
 * signed bundle is downloaded into the model store and new sessions use it ({@link #checkModelUpdateAsync}).</li>
 * <li><b>Offline model install (F17):</b> supervisors and admins install a signed bundle zip from removable media, or
 * roll back, from the home page ({@link #installOfflineBundle}, {@link #rollbackModelBundle}).</li>
 * </ul>
 * The JVM property {@code -Dmosip.registration.liveness.face.desktop.engine=fake} selects the scripted engine and
 * {@code ...desktop.source=webcam|video:<file>|images:<dir>} a mock device, for development without an SBI device.
 */
@Service
public class DesktopLivenessService {

	private static final Logger LOGGER = AppConfig.getLogger(DesktopLivenessService.class);
	private static final String FACE_MODALITY = RegistrationConstants.FACE_FULLFACE;

	@Autowired
	private AuditManagerService auditManagerService;

	@Autowired
	private MosipDeviceSpecificationFactory deviceSpecificationFactory;

	@Autowired
	private BioService bioService;

	private final DesktopLivenessConfig config;
	private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "liveness-background");
		t.setDaemon(true);
		return t;
	});
	private final AtomicInteger completedSessions = new AtomicInteger();

	private DesktopAuditSink auditSink;
	private SwappableAnalyzerFactory analyzers;
	private BundleSource bundledModels = new ClasspathBundleSource();
	private TrustedKeys trustedKeys = TrustedKeys.builtIn();
	private ModelStore modelStore;
	private LivenessService engine;
	private FaceBinding binding;
	private String configFingerprint;

	public DesktopLivenessService() {
		this(new DesktopLivenessConfig());
	}

	DesktopLivenessService(DesktopLivenessConfig config) {
		this.config = config;
	}

	/** Tests: the collaborators Spring injects in the client. */
	DesktopLivenessService(DesktopLivenessConfig config, AuditManagerService auditManagerService,
			MosipDeviceSpecificationFactory deviceSpecificationFactory, BioService bioService) {
		this(config);
		this.auditManagerService = auditManagerService;
		this.deviceSpecificationFactory = deviceSpecificationFactory;
		this.bioService = bioService;
	}

	public DesktopLivenessConfig config() {
		return config;
	}

	/** Tests: another bundled model source and its trusted keys (bundles signed with a throwaway key). */
	synchronized DesktopLivenessService withModels(BundleSource bundled, TrustedKeys keys) {
		this.bundledModels = bundled;
		this.trustedKeys = keys;
		return this;
	}

	/**
	 * Verifies the model bundle and builds the analyzer sessions in the background (about 1 s), so that the first
	 * liveness check does not wait for it. Called once at start-up. A failure is audited and retried by the first
	 * session, which then fails closed with MODEL_INTEGRITY_FAILED or ENGINE_ERROR.
	 */
	public void preloadAsync() {
		background.execute(this::preload);
	}

	/**
	 * The preload itself: verifies the bundle and builds the per-frame sessions (audited as MODEL_LOADED, or
	 * MODEL_INTEGRITY_FAILED on failure), then builds the face matcher's session. A matcher warm-up failure is only
	 * logged: that session is built on the first face binding anyway, and it is not a model integrity failure (as on
	 * Android).
	 *
	 * @return true when the models verified and loaded
	 */
	boolean preload() {
		long start = System.currentTimeMillis();
		OnnxFaceAnalyzerFactory factory;
		try {
			synchronized (this) {
				engine();
				factory = analyzers == null ? null : analyzers.current();
			}
			if (factory == null) {
				return false;
			}
			factory.preload();
			Map<String, String> d = new LinkedHashMap<>();
			d.put("modelVersion", factory.modelVersion());
			d.put("accelerator", String.valueOf(factory.activeAccelerator()));
			d.put("loadMs", String.valueOf(System.currentTimeMillis() - start));
			d.put("phase", "preload");
			auditSink().record(AuditEvent.LIVENESS_MODEL_LOADED, null, null, d);
		} catch (LivenessEngineException | RuntimeException e) {
			Map<String, String> d = new LinkedHashMap<>();
			d.put("errorCode", e instanceof LivenessEngineException
					? String.valueOf(((LivenessEngineException) e).code()) : "ENGINE_ERROR");
			d.put("reason", e.getClass().getSimpleName());
			d.put("phase", "preload");
			auditSink().record(AuditEvent.LIVENESS_MODEL_INTEGRITY_FAILED, null, null, d);
			return false;
		}
		try {
			factory.warmUpMatcher();
		} catch (LivenessEngineException | RuntimeException e) {
			LOGGER.warn("Face matcher warm-up failed ({}); the first face binding builds it", e.getClass().getSimpleName());
		}
		return true;
	}

	/** After every successful config sync: the online model update, when enabled. Never blocks or fails the sync. */
	@EventListener
	public void onConfigSynced(ConfigSyncedEvent event) {
		checkModelUpdateAsync(event.triggerPoint());
	}

	/**
	 * The online model update ({@link ModelUpdater}), when {@value DesktopLivenessConfig#MODEL_UPDATE_ENABLED_KEY} is
	 * true and {@value DesktopLivenessConfig#MODEL_UPDATE_URL_KEY} is set; otherwise nothing, and no network call. The
	 * check runs on the background thread; this method returns at once and never throws.
	 */
	public void checkModelUpdateAsync(String trigger) {
		try {
			if (!config.getBoolean(DesktopLivenessConfig.MODEL_UPDATE_ENABLED_KEY, false)) {
				return;
			}
			final String url = config.get(DesktopLivenessConfig.MODEL_UPDATE_URL_KEY);
			if (url == null) {
				LOGGER.warn("Face liveness model update is enabled but {} is not set",
						DesktopLivenessConfig.MODEL_UPDATE_URL_KEY);
				return;
			}
			background.execute(() -> checkModelUpdate(url, trigger));
		} catch (RuntimeException e) {
			LOGGER.error("Face liveness model update not started: {}", e.getMessage());
		}
	}

	/**
	 * One update check: the index, then a newer bundle's zip, then {@code ModelStore.install} (signature, sizes, hashes,
	 * anti-rollback; the app's own bundle becomes the rollback base). Audits the outcome (codes and numbers only). On
	 * INSTALLED, new sessions use the new models; running sessions keep theirs.
	 */
	ModelUpdater.UpdateResult checkModelUpdate(String url, String trigger) {
		long start = System.currentTimeMillis();
		Map<String, String> d = new LinkedHashMap<>();
		ModelUpdater.UpdateResult r = null;
		try {
			BundleSource bundled;
			TrustedKeys keys;
			synchronized (this) {
				bundled = bundledModels;
				keys = trustedKeys;
			}
			r = ModelUpdater.builder()
					.allowInsecureLocalhost(config.getBoolean(DesktopLivenessConfig.MODEL_UPDATE_INSECURE_LOCALHOST_KEY, false))
					.build().check(new URL(url), modelStore(), bundled, keys);
			d.put("outcome", r.status().name());
			if (r.version() != null) {
				d.put("version", r.version());
			}
			if (r.reason() != null) {
				d.put("reason", r.reason().name());
			}
		} catch (MalformedURLException e) {
			d.put("outcome", ModelUpdater.Status.FAILED.name());
			d.put("reason", "INVALID_URL");
		} catch (RuntimeException e) {
			LOGGER.error("Face liveness model update failed: {}", e.getClass().getSimpleName());
			d.put("outcome", ModelUpdater.Status.FAILED.name());
			d.put("reason", "ERROR");
		}
		d.put("durationMs", String.valueOf(System.currentTimeMillis() - start));
		d.put("trigger", trigger == null ? "NA" : trigger);
		auditSink().record(AuditEvent.LIVENESS_MODEL_UPDATE, null, null, d);
		if (r != null && r.status() == ModelUpdater.Status.INSTALLED) {
			reloadModels();
		}
		return r;
	}

	/** New sessions use the newest verified bundle (store or bundled); running sessions keep their analyzers. */
	private void reloadModels() {
		synchronized (this) {
			if (analyzers == null) {
				return; // not built yet (or the scripted engine): the first build reads the store
			}
			analyzers.swap(newAnalyzerFactory());
		}
		preload();
	}

	// ---- F17: offline model install and rollback (supervisors and admins) ----

	/** What an offline install or a rollback did; the UI maps it to a generic text, never to paths or exceptions. */
	public enum ModelBundleOutcome {
		/** A newer signed bundle is installed; new sessions use it. */
		INSTALLED,
		/** The previous bundle is current again; new sessions use it. */
		ROLLED_BACK,
		/** The zip's bundle is not newer than the models in use: refused, nothing changed. */
		NOT_NEWER,
		/** The zip or bundle is invalid, tampered or unsigned (MODEL_INTEGRITY_FAILED): refused, nothing changed. */
		INVALID,
		/** There is no earlier bundle to roll back to. */
		NOTHING_TO_ROLL_BACK,
		/** The user may not do this, or the feature is off. */
		NOT_ALLOWED,
		/** A local storage problem: nothing changed. */
		FAILED
	}

	/** The outcome and the versions in use before and after (null when unknown). */
	public static final class ModelBundleResult {
		private final ModelBundleOutcome outcome;
		private final String fromVersion, toVersion;

		ModelBundleResult(ModelBundleOutcome outcome, String fromVersion, String toVersion) {
			this.outcome = outcome;
			this.fromVersion = fromVersion;
			this.toVersion = toVersion;
		}

		public ModelBundleOutcome outcome() {
			return outcome;
		}

		public String fromVersion() {
			return fromVersion;
		}

		public String toVersion() {
			return toVersion;
		}
	}

	private Supplier<Collection<String>> userRoles = () -> SessionContext.isSessionContextAvailable()
			? SessionContext.userContext().getRoles() : Collections.<String>emptyList();
	private Supplier<String> userId = SessionContext::userId;

	/** Tests: the logged-in user's roles and id (the client reads them from the session context). */
	synchronized DesktopLivenessService withUser(Supplier<Collection<String>> roles, Supplier<String> id) {
		this.userRoles = roles;
		this.userId = id;
		return this;
	}

	/**
	 * True when {@value DesktopLivenessConfig#MODEL_OFFLINE_INSTALL_ENABLED_KEY} is on (the default) and the logged-in
	 * user is a supervisor or an admin. The UI shows the install and rollback actions only then; the actions check it
	 * again.
	 */
	public boolean isOfflineModelInstallAllowed() {
		Collection<String> roles;
		synchronized (this) {
			roles = userRoles.get();
		}
		return config.getBoolean(DesktopLivenessConfig.MODEL_OFFLINE_INSTALL_ENABLED_KEY, true) && roles != null
				&& (roles.contains(AuthenticationAdvice.SUPERVISOR_ROLE) || roles.contains(AuthenticationAdvice.ADMIN_ROLE));
	}

	/** The model bundle version new sessions use (store or bundled), or null when unreadable. */
	public String activeModelVersion() {
		BundleSource bundled;
		synchronized (this) {
			bundled = bundledModels;
		}
		return ModelUpdater.activeVersion(modelStore(), bundled);
	}

	/**
	 * Installs a signed bundle zip from removable media ({@link ModelUpdater#installZip}): the same zip and the same
	 * verification as the online update (signature, sizes, hashes, anti-rollback), unpacked into a temporary directory
	 * that is deleted afterwards. New sessions use the new models; running sessions keep theirs. Audited as
	 * LIVENESS_MODEL_UPDATE with trigger=offline (codes and versions only). Blocking: call it off the UI thread.
	 */
	public ModelBundleResult installOfflineBundle(File zip) {
		long start = System.currentTimeMillis();
		String user = currentUserId();
		String from = activeModelVersion();
		if (!isOfflineModelInstallAllowed()) {
			return auditBundleAction("offline", user, from, null, "REFUSED", "NOT_ALLOWED", null, start,
					ModelBundleOutcome.NOT_ALLOWED);
		}
		BundleSource bundled;
		TrustedKeys keys;
		synchronized (this) {
			bundled = bundledModels;
			keys = trustedKeys;
		}
		ModelUpdater.UpdateResult r = ModelUpdater.builder().build().installZip(zip, modelStore(), bundled, keys);
		switch (r.status()) {
		case INSTALLED:
			reloadModels();
			return auditBundleAction("offline", user, from, r.version(), r.status().name(), null, null, start,
					ModelBundleOutcome.INSTALLED);
		case UP_TO_DATE:
			return auditBundleAction("offline", user, from, from, r.status().name(), "NOT_NEWER", null, start,
					ModelBundleOutcome.NOT_NEWER);
		default:
			boolean storage = r.reason() == ModelUpdater.Reason.STORAGE;
			return auditBundleAction("offline", user, from, from, r.status().name(), r.reason().name(),
					storage ? null : ErrorCode.MODEL_INTEGRITY_FAILED.name(), start,
					storage ? ModelBundleOutcome.FAILED : ModelBundleOutcome.INVALID);
		}
	}

	/**
	 * Rolls the model store back to the previous bundle ({@link ModelStore#rollback}: an explicit operator action that
	 * bypasses anti-rollback; the previous bundle is verified again). New sessions use it. Audited as
	 * LIVENESS_MODEL_UPDATE with trigger=rollback. Blocking: call it off the UI thread.
	 */
	public ModelBundleResult rollbackModelBundle() {
		long start = System.currentTimeMillis();
		String user = currentUserId();
		String from = activeModelVersion();
		if (!isOfflineModelInstallAllowed()) {
			return auditBundleAction("rollback", user, from, null, "REFUSED", "NOT_ALLOWED", null, start,
					ModelBundleOutcome.NOT_ALLOWED);
		}
		TrustedKeys keys;
		synchronized (this) {
			keys = trustedKeys;
		}
		try {
			modelStore().rollback(keys);
			reloadModels();
			return auditBundleAction("rollback", user, from, activeModelVersion(), "ROLLED_BACK", null, null, start,
					ModelBundleOutcome.ROLLED_BACK);
		} catch (IllegalStateException nothingEarlier) {
			return auditBundleAction("rollback", user, from, from, "REFUSED", "NOTHING_TO_ROLL_BACK", null, start,
					ModelBundleOutcome.NOTHING_TO_ROLL_BACK);
		} catch (LivenessEngineException e) {
			return auditBundleAction("rollback", user, from, from, "FAILED", "BUNDLE_INVALID", e.code().name(), start,
					ModelBundleOutcome.INVALID);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Face liveness model rollback failed: {}", e.getClass().getSimpleName());
			return auditBundleAction("rollback", user, from, from, "FAILED", "STORAGE", null, start,
					ModelBundleOutcome.FAILED);
		}
	}

	private String currentUserId() {
		Supplier<String> id;
		synchronized (this) {
			id = userId;
		}
		try {
			return id.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** One LIVENESS_MODEL_UPDATE audit (codes, versions and the user id only), and the result for the UI. */
	private ModelBundleResult auditBundleAction(String trigger, String user, String from, String to, String outcome,
			String reason, String errorCode, long start, ModelBundleOutcome result) {
		Map<String, String> d = new LinkedHashMap<>();
		d.put("outcome", outcome);
		if (errorCode != null) {
			d.put("errorCode", errorCode);
		}
		if (reason != null) {
			d.put("reason", reason);
		}
		d.put("fromVersion", from == null ? "NA" : from);
		d.put("toVersion", to == null ? "NA" : to);
		d.put("durationMs", String.valueOf(System.currentTimeMillis() - start));
		d.put("trigger", trigger);
		d.put("userId", user == null ? "NA" : user);
		auditSink().record(AuditEvent.LIVENESS_MODEL_UPDATE, null, null, d);
		return new ModelBundleResult(result, from, to);
	}

	/** A new session, or null when liveness is disabled for the workflow (the caller keeps the legacy flow). */
	public LivenessSession newSession(WorkflowType workflow) {
		return engine().newSession(workflow);
	}

	public LivenessPolicy policy(WorkflowType workflow) {
		return engine().policy(workflow);
	}

	public boolean isEnabled(WorkflowType workflow) {
		try {
			return policy(workflow).enabled;
		} catch (RuntimeException e) {
			return true; // fail closed: an unreadable policy never switches liveness off
		}
	}

	/** True for the scripted development engine: it never opens a device and has no best frame. */
	public synchronized boolean isScriptedEngine() {
		return engine() instanceof FakeLivenessService;
	}

	/**
	 * The face device as a liveness frame source. The device is opened when the session starts it, so an unplugged
	 * device becomes a recoverable device error with Retry. Never null.
	 */
	public FrameSource openFaceSource() {
		final String source = config.get(DesktopLivenessConfig.SOURCE_KEY);
		if (source == null && isScriptedEngine()) {
			return new IdleFrameSource(); // the scripted engine ignores frames; never touch the device for it
		}
		if (source == null || "sbi".equalsIgnoreCase(source)) {
			return new DeferredFrameSource(() -> new SbiStreamFrameSourceFactory(deviceSpecificationFactory,
					bioService, config.getLong(DesktopLivenessConfig.SBI_FRAME_TIMEOUT_KEY, 15_000L),
					Math.max(1, config.getInt(DesktopLivenessConfig.SBI_FRAME_STRIDE_KEY, 1)),
					Math.max(0, config.getInt(DesktopLivenessConfig.SBI_RECONNECTS_KEY, 3)),
					Math.max(0L, config.getLong(DesktopLivenessConfig.SBI_BACKOFF_KEY, 500L)))
					.create(FACE_MODALITY));
		}
		LOGGER.warn("Face liveness uses the development frame source '{}', not the SBI device", source);
		return new DeferredFrameSource(() -> mockSource(source));
	}

	private static FrameSource mockSource(String spec) throws Exception {
		if ("webcam".equalsIgnoreCase(spec)) {
			return MockFrameSource.ofWebcam(new SarxosWebcamFrameGrabber(), 15.0);
		}
		if (spec.toLowerCase(Locale.ROOT).startsWith("video:")) {
			return MockFrameSource.ofVideoFile(Paths.get(spec.substring(6)), 15.0, true);
		}
		if (spec.toLowerCase(Locale.ROOT).startsWith("images:")) {
			return new MockFrameSource(Paths.get(spec.substring(7)), 10.0);
		}
		throw new IllegalArgumentException("Unknown liveness frame source " + spec);
	}

	public synchronized FaceBinding binding() {
		engine();
		return binding;
	}

	/**
	 * Called by the gate after every session: logs the anonymised metrics in diagnostic mode, and audits them every
	 * {@code desktop.metricsAuditEvery} sessions.
	 */
	public void sessionCompleted(WorkflowType workflow, LivenessResult result) {
		Map<String, String> metrics = metricsSnapshot();
		if (policyDiagnostic(workflow)) {
			LOGGER.info("LIVENESS-DIAG session={} workflow={} decision={} metrics={}", result.sessionId, workflow,
					result.decision, metrics);
		}
		int every = config.getInt(DesktopLivenessConfig.METRICS_AUDIT_EVERY_KEY, 20);
		if (every > 0 && completedSessions.incrementAndGet() % every == 0 && !metrics.isEmpty()) {
			auditSink().record(AuditEvent.LIVENESS_METRICS, null, null, metrics);
		}
	}

	/**
	 * Counts and averages since start-up (no ids, no scores of a person): average processing time, challenge
	 * completion time, retry rate, failure rate and the counts behind them.
	 */
	public synchronized Map<String, String> metricsSnapshot() {
		LivenessMetrics m = engine instanceof DefaultLivenessService ? ((DefaultLivenessService) engine).metrics()
				: engine instanceof FakeLivenessService ? ((FakeLivenessService) engine).metrics() : null;
		return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m.snapshot().toMap());
	}

	public boolean policyDiagnostic(WorkflowType workflow) {
		try {
			return workflow != null && policy(workflow).diagnosticMode;
		} catch (RuntimeException e) {
			return false;
		}
	}

	// ------------------------------------------------------------------ wiring

	synchronized DesktopAuditSink auditSink() {
		if (auditSink == null) {
			auditSink = new DesktopAuditSink(auditManagerService, background);
		}
		return auditSink;
	}

	private synchronized LivenessService engine() {
		if (engine == null) {
			engine = buildEngine();
			configFingerprint = config.fingerprint();
		} else {
			String now = config.fingerprint();
			if (!now.equals(configFingerprint)) {
				LOGGER.info("Liveness config changed (sync): reloading for the next session");
				configFingerprint = now;
				engine.reloadConfig(config);
			}
		}
		return engine;
	}

	private LivenessService buildEngine() {
		DesktopAuditSink audit = auditSink();
		if ("fake".equalsIgnoreCase(config.get(DesktopLivenessConfig.ENGINE_KEY))) {
			String scenario = config.get(DesktopLivenessConfig.FAKE_SCENARIO_KEY);
			LOGGER.warn("Face liveness uses the SCRIPTED development engine ({}): no real liveness check",
					scenario == null ? "RANDOM" : scenario);
			FakeLivenessService fake = FakeLivenessService.builder(config).auditSink(audit)
					.scenario(scenario == null ? FakeScenario.RANDOM : FakeScenario.valueOf(scenario.toUpperCase()))
					.build();
			binding = new FaceBinding(this::isEnabled, config, (a, b) -> {
				throw new LivenessEngineException(io.mosip.registration.liveness.api.ErrorCode.ENGINE_ERROR,
						"scripted engine has no matcher");
			}, new CapturedFaceDecoder(), audit, System::currentTimeMillis, true);
			return fake;
		}
		analyzers = new SwappableAnalyzerFactory(newAnalyzerFactory());
		final SwappableAnalyzerFactory matcherSource = analyzers;
		binding = new FaceBinding(this::isEnabled, config, (live, captured) -> {
			try (FaceMatcher matcher = matcherSource.current().createMatcher()) {
				MatchResult m = matcher.compare(live, captured);
				return new FaceBinding.Comparison(m.outcome.name(), m.similarity, m.threshold);
			}
		}, new CapturedFaceDecoder(), audit, System::currentTimeMillis, false);
		return DefaultLivenessService.builder().config(config).analyzerFactory(analyzers).auditSink(audit).build();
	}

	private synchronized OnnxFaceAnalyzerFactory newAnalyzerFactory() {
		return new OnnxFaceAnalyzerFactory(bundledModels, modelStore(), trustedKeys, AnalyzerConfig.fromConfig(config));
	}

	/** One store for the analyzers and the online update, so an install and a load never race. */
	private synchronized ModelStore modelStore() {
		if (modelStore == null) {
			modelStore = new ModelStore(modelStoreDir());
		}
		return modelStore;
	}

	private File modelStoreDir() {
		String dir = config.get(DesktopLivenessConfig.MODEL_STORE_KEY);
		return dir != null ? new File(dir) : new File(System.getProperty("user.dir"), ".liveness-models");
	}
}
