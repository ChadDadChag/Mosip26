package io.mosip.registration.liveness.desktop;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameSource;

/**
 * Opens the real device only when the session starts it, and again on every restart (Retry).
 *
 * <p>{@code SbiStreamFrameSourceFactory.create()} fails fast when no face device is connected. Doing that inside
 * {@link #start} turns the failure into {@code onError(DEVICE_UNAVAILABLE, recoverable)}: the engine shows the
 * device error with Retry (problem statement §9 "Device unavailable"), and Retry tries the device again, so the
 * operator can plug it in without leaving the screen.
 */
final class DeferredFrameSource implements FrameSource {

	/** Creates a fresh device source; throws when the device is unavailable. */
	interface Opener {
		FrameSource open() throws Exception;
	}

	private static final DeviceInfo UNKNOWN = new DeviceInfo("unknown", "unknown", "unknown", "unknown", "NONE", 0,
			0, 0);

	private final Opener opener;
	private volatile FrameSource current;

	DeferredFrameSource(Opener opener) {
		this.opener = opener;
	}

	@Override
	public DeviceInfo info() {
		FrameSource s = current;
		return s == null ? UNKNOWN : s.info();
	}

	@Override
	public void start(Listener listener) {
		stop();
		FrameSource s;
		try {
			s = opener.open();
		} catch (Exception e) {
			listener.onError(ErrorCode.DEVICE_UNAVAILABLE, e.getClass().getSimpleName() + ": " + e.getMessage(),
					true);
			return;
		}
		current = s;
		s.start(listener);
	}

	@Override
	public void stop() {
		FrameSource s = current;
		current = null;
		if (s != null) {
			s.stop();
		}
	}

	@Override
	public boolean isRunning() {
		FrameSource s = current;
		return s != null && s.isRunning();
	}
}
