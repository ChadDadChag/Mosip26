package io.mosip.registration.liveness.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.LivenessPolicy;

/**
 * Face liveness configuration for the Desktop client, over the client's existing configuration framework.
 *
 * <p>Lookup order for a key:
 * <ol>
 * <li>{@link ApplicationContext#map()}: the global params synced from the server's
 * {@code registration-default.properties} (stored in the local Derby {@code GLOBAL_PARAM} table, so they apply
 * offline too) plus the operator's local preferences;</li>
 * <li>the Desktop defaults bundled in {@value #DEFAULTS_RESOURCE};</li>
 * <li>absent: the engine then uses its own bundled policy defaults ({@code liveness-default.properties}).</li>
 * </ol>
 * The development switches {@link #ENGINE_KEY} and {@link #SOURCE_KEY} are read only from JVM system properties,
 * never from synced config, so a config push can never replace the real engine or the SBI device.
 */
public class DesktopLivenessConfig implements ConfigProvider {

	private static final Logger LOGGER = AppConfig.getLogger(DesktopLivenessConfig.class);

	public static final String PREFIX = LivenessPolicy.PREFIX;
	public static final String DEFAULTS_RESOURCE = "liveness-desktop-default.properties";

	/** {@code onnx} (default) or {@code fake} (scripted engine, development only). JVM property only. */
	public static final String ENGINE_KEY = PREFIX + "desktop.engine";
	/** {@code fake} engine scenario name ({@code FakeScenario}). JVM property only. */
	public static final String FAKE_SCENARIO_KEY = PREFIX + "desktop.fake.scenario";
	/** {@code sbi} (default), {@code webcam}, {@code video:<file.mjpeg>} or {@code images:<dir>}. JVM property only. */
	public static final String SOURCE_KEY = PREFIX + "desktop.source";

	public static final String BINDING_ENABLED_KEY = PREFIX + "binding.enabled";
	public static final String SBI_FRAME_TIMEOUT_KEY = PREFIX + "desktop.sbi.frameTimeoutMs";
	public static final String SBI_FRAME_STRIDE_KEY = PREFIX + "desktop.sbi.frameStride";
	public static final String SBI_RECONNECTS_KEY = PREFIX + "desktop.sbi.maxReconnectAttempts";
	public static final String SBI_BACKOFF_KEY = PREFIX + "desktop.sbi.reconnectBackoffMs";
	public static final String AUTO_CONTINUE_KEY = PREFIX + "desktop.autoContinueMs";
	public static final String MODEL_STORE_KEY = PREFIX + "desktop.modelStoreDir";
	public static final String METRICS_AUDIT_EVERY_KEY = PREFIX + "desktop.metricsAuditEvery";
	/** OPERATOR liveness right after a successful password login (as on Android). */
	public static final String GATE_PASSWORD_LOGIN_KEY = PREFIX + "desktop.operator.gatePasswordLogin";

	private final Supplier<Map<String, Object>> appConfig;
	private final Properties defaults;

	public DesktopLivenessConfig() {
		this(ApplicationContext::map, loadDefaults());
	}

	DesktopLivenessConfig(Supplier<Map<String, Object>> appConfig, Properties defaults) {
		this.appConfig = appConfig;
		this.defaults = defaults;
	}

	@Override
	public String get(String key) {
		if (key == null) {
			return null;
		}
		if (isJvmOnly(key)) {
			return trimToNull(System.getProperty(key));
		}
		Map<String, Object> map = appConfig.get();
		Object value = map == null ? null : map.get(key);
		String text = value == null ? null : trimToNull(String.valueOf(value));
		return text != null ? text : trimToNull(defaults.getProperty(key));
	}

	public boolean getBoolean(String key, boolean fallback) {
		String v = get(key);
		return v == null ? fallback : Boolean.parseBoolean(v);
	}

	public long getLong(String key, long fallback) {
		String v = get(key);
		if (v == null) {
			return fallback;
		}
		try {
			return Long.parseLong(v);
		} catch (NumberFormatException e) {
			LOGGER.warn("Ignoring invalid value for {}: {}", key, v);
			return fallback;
		}
	}

	public int getInt(String key, int fallback) {
		long v = getLong(key, fallback);
		return v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? fallback : (int) v;
	}

	/**
	 * All liveness keys currently in effect from the synced config, in key order. Compared before each session so
	 * that a config sync (online) takes effect for the next session without a restart.
	 */
	public String fingerprint() {
		Map<String, String> sorted = new TreeMap<>();
		Map<String, Object> map = appConfig.get();
		if (map != null) {
			for (Map.Entry<String, Object> e : map.entrySet()) {
				if (e.getKey() != null && e.getKey().startsWith(PREFIX) && !isJvmOnly(e.getKey())) {
					sorted.put(e.getKey(), String.valueOf(e.getValue()));
				}
			}
		}
		return sorted.toString();
	}

	static boolean isJvmOnly(String key) {
		return ENGINE_KEY.equals(key) || SOURCE_KEY.equals(key) || FAKE_SCENARIO_KEY.equals(key);
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	static Properties loadDefaults() {
		Properties p = new Properties();
		try (InputStream in = DesktopLivenessConfig.class.getClassLoader().getResourceAsStream(DEFAULTS_RESOURCE)) {
			if (in != null) {
				p.load(in);
			} else {
				LOGGER.warn("{} not found on the classpath; using the engine defaults only", DEFAULTS_RESOURCE);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read {}: {}", DEFAULTS_RESOURCE, e.getMessage());
		}
		return p;
	}
}
