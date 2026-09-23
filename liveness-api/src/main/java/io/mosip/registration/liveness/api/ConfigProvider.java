package io.mosip.registration.liveness.api;

/**
 * Adapter over the host app's config. Desktop: ApplicationContext.map() (synced global/local config).
 * Android: the synced config the Android client already stores. Returns null if a key is absent.
 */
public interface ConfigProvider {
    String get(String key);
}
