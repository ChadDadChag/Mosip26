package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.ConfigProvider;

import java.util.HashMap;
import java.util.Map;

final class MapConfig implements ConfigProvider {
    final Map<String, String> values = new HashMap<String, String>();

    MapConfig put(String key, String value) {
        values.put(key, value);
        return this;
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }
}
