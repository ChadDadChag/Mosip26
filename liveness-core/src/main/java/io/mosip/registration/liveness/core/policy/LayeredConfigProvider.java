package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.ConfigProvider;

/** Returns the first non-null value from the given providers, in order. */
public final class LayeredConfigProvider implements ConfigProvider {
    private final ConfigProvider[] layers;

    public LayeredConfigProvider(ConfigProvider... layers) {
        this.layers = layers.clone();
    }

    @Override
    public String get(String key) {
        for (ConfigProvider layer : layers) {
            if (layer == null) continue;
            String v = layer.get(key);
            if (v != null) return v;
        }
        return null;
    }
}
