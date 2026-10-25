package io.mosip.registration.liveness.desktop;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;

/**
 * Published by the client's config sync ({@code GlobalParamServiceImpl.synchConfigData}) after the global params were
 * synced successfully. {@link DesktopLivenessService} listens for it to run the online model update.
 */
public class ConfigSyncedEvent extends ApplicationEvent {

	private static final long serialVersionUID = 1L;
	private static final Logger LOGGER = AppConfig.getLogger(ConfigSyncedEvent.class);

	private final String triggerPoint;

	public ConfigSyncedEvent(Object source, String triggerPoint) {
		super(source);
		this.triggerPoint = triggerPoint;
	}

	/** System (the sync job) or User (a sync started by the operator). */
	public String triggerPoint() {
		return triggerPoint;
	}

	/** Publishes the event; a listener's failure is logged and never fails the sync. */
	public static void publish(ApplicationEventPublisher publisher, Object source, String triggerPoint) {
		if (publisher == null) {
			return;
		}
		try {
			publisher.publishEvent(new ConfigSyncedEvent(source, triggerPoint));
		} catch (RuntimeException e) {
			LOGGER.error("A config sync listener failed: {}", e.getMessage());
		}
	}
}
