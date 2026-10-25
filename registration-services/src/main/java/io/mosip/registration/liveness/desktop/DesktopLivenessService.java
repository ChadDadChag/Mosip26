package io.mosip.registration.liveness.desktop;

import java.io.File;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.match.MatchResult;
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
	private OnnxFaceAnalyzerFactory analyzers;
	private BundleSource bundledModels = new ClasspathBundleSource();
	private TrustedKeys trustedKeys = TrustedKeys.builtIn();
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
				factory = analyzers;
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
		analyzers = new OnnxFaceAnalyzerFactory(bundledModels, new ModelStore(modelStoreDir()), trustedKeys,
				AnalyzerConfig.fromConfig(config));
		final OnnxFaceAnalyzerFactory matcherSource = analyzers;
		binding = new FaceBinding(this::isEnabled, config, (live, captured) -> {
			try (FaceMatcher matcher = matcherSource.createMatcher()) {
				MatchResult m = matcher.compare(live, captured);
				return new FaceBinding.Comparison(m.outcome.name(), m.similarity, m.threshold);
			}
		}, new CapturedFaceDecoder(), audit, System::currentTimeMillis, false);
		return DefaultLivenessService.builder().config(config).analyzerFactory(analyzers).auditSink(audit).build();
	}

	private File modelStoreDir() {
		String dir = config.get(DesktopLivenessConfig.MODEL_STORE_KEY);
		return dir != null ? new File(dir) : new File(System.getProperty("user.dir"), ".liveness-models");
	}
}
