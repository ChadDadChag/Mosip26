package io.mosip.registration.liveness.core.statusaudit;

import io.mosip.registration.liveness.api.ConfigProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only ConfigProvider backed by a map (stand-in for Parts 4/5). */
public final class MapConfigProvider implements ConfigProvider {
    public final Map<String, String> values = new ConcurrentHashMap<String, String>();

    public MapConfigProvider put(String key, String value) {
        values.put(key, value);
        return this;
    }

    @Override public String get(String key) { return values.get(key); }
}
