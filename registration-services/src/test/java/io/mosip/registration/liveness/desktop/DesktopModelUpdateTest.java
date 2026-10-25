package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import com.sun.net.httpserver.HttpServer;

import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.integrity.VerifiedBundle;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.LivenessEngineException;

/**
 * The online model update after a config sync: a local HTTP server, bundles signed with a throwaway key, and the reload
 * that leaves running sessions alone.
 */
public class DesktopModelUpdateTest {

	private static final String BUNDLED = "pad-2026.10.5";
	private static final String NEWER = "pad-2099.01.1";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Map<String, Object> synced = new HashMap<>();
	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final Map<String, byte[]> routes = new ConcurrentHashMap<>();
	private final List<String> hits = new CopyOnWriteArrayList<>();
	private HttpServer server;
	private ExecutorService executor;
	private File storeDir;
	private KeyPair kp;
	private TrustedKeys trust;

	@Before
	public void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/", x -> {
			String path = x.getRequestURI().getPath();
			hits.add(path);
			byte[] body = routes.get(path);
			if (body == null) {
				x.sendResponseHeaders(404, -1);
				x.close();
				return;
			}
			x.sendResponseHeaders(200, body.length);
			try (OutputStream o = x.getResponseBody()) {
				o.write(body);
			}
		});
		server.start();
		storeDir = tmp.newFolder("store");
		synced.put(DesktopLivenessConfig.MODEL_STORE_KEY, storeDir.getAbsolutePath());
		System.setProperty(DesktopLivenessConfig.MODEL_UPDATE_INSECURE_LOCALHOST_KEY, "true");
		kp = SignedTestBundles.keyPair();
		trust = SignedTestBundles.trust(kp);
	}

	@After
	public void stop() {
		server.stop(0);
		executor.shutdownNow();
		System.clearProperty(DesktopLivenessConfig.MODEL_UPDATE_INSECURE_LOCALHOST_KEY);
	}

	private DesktopLivenessService service() throws Exception {
		return new DesktopLivenessService(new DesktopLivenessConfig(() -> synced, DesktopLivenessConfig.loadDefaults()),
				audit, null, null).withModels(
						SignedTestBundles.source(SignedTestBundles.bundle(BUNDLED, kp, SignedTestBundles.realModels())),
						trust);
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	/** Serves a packed bundle at /m/<version>.zip with its index at /m/latest.json, as ModelBundleTool pack writes. */
	private void serve(String version) throws Exception {
		ByteArrayOutputStream zip = new ByteArrayOutputStream();
		ModelUpdater.writeZip(VerifiedBundle.load(
				SignedTestBundles.source(SignedTestBundles.bundle(version, kp, SignedTestBundles.realModels())), trust), zip);
		byte[] z = zip.toByteArray();
		StringBuilder sha = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(z)) {
			sha.append(String.format("%02x", b & 0xff));
		}
		routes.put("/m/" + version + ".zip", z);
		routes.put("/m/latest.json", ModelUpdater.indexJson(version, version + ".zip", sha.toString(), z.length)
				.getBytes(StandardCharsets.UTF_8));
	}

	private void enable(String indexPath) {
		synced.put(DesktopLivenessConfig.MODEL_UPDATE_ENABLED_KEY, "true");
		synced.put(DesktopLivenessConfig.MODEL_UPDATE_URL_KEY, url(indexPath));
	}

	@Test(timeout = 180_000)
	public void anEnabledNewerBundleIsInstalledAfterASyncAndNewSessionsUseIt() throws Exception {
		serve(NEWER);
		DesktopLivenessService s = service();
		assertTrue(s.preload());
		audit.await(AuditEvent.LIVENESS_MODEL_LOADED, "modelVersion=" + BUNDLED);
		enable("/m/latest.json");

		s.onConfigSynced(new ConfigSyncedEvent(this, "System"));

		String update = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "");
		assertTrue(update, update.contains("outcome=INSTALLED") && update.contains("version=" + NEWER)
				&& update.contains("trigger=System"));
		audit.await(AuditEvent.LIVENESS_MODEL_LOADED, "modelVersion=" + NEWER);
		ModelStore store = new ModelStore(storeDir);
		assertEquals(NEWER, store.currentVersion());
		assertEquals("the client's own bundle is the rollback base", Arrays.asList(BUNDLED, NEWER),
				store.installedVersions());
		assertTrue(hits.toString(), hits.contains("/m/" + NEWER + ".zip"));
	}

	@Test(timeout = 60_000)
	public void disabledOrWithoutAUrlTheSyncMakesNoNetworkCall() throws Exception {
		serve(NEWER);
		DesktopLivenessService s = service();
		synced.put(DesktopLivenessConfig.MODEL_UPDATE_URL_KEY, url("/m/latest.json"));

		s.onConfigSynced(new ConfigSyncedEvent(this, "System")); // enabled is false by default
		synced.put(DesktopLivenessConfig.MODEL_UPDATE_ENABLED_KEY, "true");
		synced.remove(DesktopLivenessConfig.MODEL_UPDATE_URL_KEY);
		s.onConfigSynced(new ConfigSyncedEvent(this, "User")); // enabled, but no URL
		Thread.sleep(1000);

		assertTrue(hits.toString(), hits.isEmpty());
		assertFalse(audit.events().contains(AuditEvent.LIVENESS_MODEL_UPDATE));
		assertNull(new ModelStore(storeDir).currentVersion());
	}

	@Test(timeout = 120_000)
	public void aFailedUpdateNeverFailsTheSyncAndKeepsTheModels() throws Exception {
		DesktopLivenessService s = service();
		enable("/missing/latest.json");

		long start = System.currentTimeMillis();
		s.onConfigSynced(new ConfigSyncedEvent(this, "System"));
		assertTrue("the sync never waits for the update", System.currentTimeMillis() - start < 1000);

		String update = audit.await(AuditEvent.LIVENESS_MODEL_UPDATE, "");
		assertTrue(update, update.contains("outcome=FAILED") && update.contains("reason=HTTP_STATUS"));
		assertNull(new ModelStore(storeDir).currentVersion());
		assertTrue(s.preload());
		audit.await(AuditEvent.LIVENESS_MODEL_LOADED, "modelVersion=" + BUNDLED);

		// A listener that throws never reaches the sync.
		ApplicationEventPublisher failing = new ApplicationEventPublisher() {
			@Override
			public void publishEvent(ApplicationEvent event) {
				throw new IllegalStateException("listener failed");
			}

			@Override
			public void publishEvent(Object event) {
				throw new IllegalStateException("listener failed");
			}
		};
		ConfigSyncedEvent.publish(failing, this, "System");
		ConfigSyncedEvent.publish(null, this, "System");
	}

	@Test(timeout = 120_000)
	public void aRunningSessionKeepsItsModelsUntilItEnds() throws Exception {
		OnnxFaceAnalyzerFactory first = new OnnxFaceAnalyzerFactory(new ClasspathBundleSource(), null,
				TrustedKeys.builtIn(), AnalyzerConfig.defaults());
		OnnxFaceAnalyzerFactory second = new OnnxFaceAnalyzerFactory(new ClasspathBundleSource(), null,
				TrustedKeys.builtIn(), AnalyzerConfig.defaults());
		SwappableAnalyzerFactory factory = new SwappableAnalyzerFactory(first);
		FaceAnalyzer running = factory.create();

		factory.swap(second);
		assertSame(second, factory.current());
		first.create().close(); // still open: the running session uses it
		running.close();
		try {
			first.create();
			fail("the replaced factory closes with its last analyzer");
		} catch (LivenessEngineException expected) {
			// closed
		}
		factory.create().close();
		second.close();
	}
}
