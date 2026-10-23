package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.LivenessPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The contract's {@code liveness-default.properties}, read from the liveness-api jar.
 *
 * <p>{@link LivenessPolicy#load} falls back to hard-coded field defaults, which do NOT include the per-workflow
 * overrides in the properties file (operator/supervisor minChallenges=1, operator COOLDOWN). Layering this file
 * under the host config means those overrides apply even if an app forgot to merge the file into its config.
 */
public final class BundledDefaults implements ConfigProvider {
    public static final String RESOURCE = "/liveness-default.properties";

    private static final BundledDefaults INSTANCE = new BundledDefaults();

    private final Properties props = new Properties();

    private BundledDefaults() {
        InputStream in = LivenessPolicy.class.getResourceAsStream(RESOURCE);
        if (in == null) return; // behaves like an empty layer; hard-coded defaults still apply
        try {
            props.load(in);
        } catch (IOException ignored) {
            props.clear();
        } finally {
            try { in.close(); } catch (IOException ignored) { /* nothing to do */ }
        }
    }

    public static BundledDefaults get() {
        return INSTANCE;
    }

    public boolean isLoaded() {
        return !props.isEmpty();
    }

    @Override
    public String get(String key) {
        return props.getProperty(key);
    }
}
