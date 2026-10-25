package io.mosip.registration.liveness.desktop;

import io.mosip.registration.liveness.api.FrameSource;

/** Delivers nothing: the frame source of the scripted development engine, which never analyses frames. */
final class IdleFrameSource implements FrameSource {

	private static final DeviceInfo INFO = new DeviceInfo("scripted", "none", "none", "none", "NONE", 0, 0, 0);
	private volatile boolean running;

	@Override
	public DeviceInfo info() {
		return INFO;
	}

	@Override
	public void start(Listener listener) {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
