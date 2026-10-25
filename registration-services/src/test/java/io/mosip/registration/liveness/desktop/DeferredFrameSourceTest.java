package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

public class DeferredFrameSourceTest {

	private final List<String> errors = new ArrayList<>();
	private final FrameSource.Listener listener = new FrameSource.Listener() {
		@Override
		public void onFrame(Frame frame) {
		}

		@Override
		public void onError(ErrorCode code, String detail, boolean recoverable) {
			errors.add(code + ":" + recoverable);
		}
	};

	@Test
	public void anUnavailableDeviceIsARecoverableErrorAndRetryOpensItAgain() {
		AtomicInteger opens = new AtomicInteger();
		FakeSource device = new FakeSource();
		DeferredFrameSource source = new DeferredFrameSource(() -> {
			if (opens.incrementAndGet() == 1) {
				throw new IllegalStateException("MDS_BIODEVICE_NOT_FOUND");
			}
			return device;
		});

		source.start(listener);
		assertEquals("DEVICE_UNAVAILABLE:true", errors.get(0));
		assertFalse(source.isRunning());
		assertEquals("unknown", source.info().deviceId);

		source.start(listener); // Retry: the operator plugged the device in
		assertTrue(source.isRunning());
		assertEquals(2, opens.get());
		assertEquals("face-1", source.info().deviceId);

		source.stop();
		assertFalse(device.running);
	}

	private static final class FakeSource implements FrameSource {
		boolean running;

		@Override
		public DeviceInfo info() {
			return new DeviceInfo("face-1", "v", "m", "0.9.5", "L1", 640, 480, 15);
		}

		@Override
		public void start(Listener l) {
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
}
