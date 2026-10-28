package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessSession;

/** The screen demo's exit order (F13b) and its F15 test-template path guard; no camera, no JavaFX window. */
public class LivenessScreenDemoTest {

	private final List<String> steps = new CopyOnWriteArrayList<>();

	@Test
	public void theCameraIsReleasedBeforeTheJvmExits() {
		LivenessSession session = (LivenessSession) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { LivenessSession.class }, (proxy, method, args) -> {
					steps.add(method.getName() + " session");
					return null;
				});
		FrameSource camera = new FrameSource() {
			@Override
			public DeviceInfo info() {
				return null;
			}

			@Override
			public void start(Listener listener) {
			}

			@Override
			public void stop() {
				steps.add("stop camera");
			}

			@Override
			public boolean isRunning() {
				return true;
			}
		};
		LivenessScreenDemo.shutdown(session, camera, () -> steps.add("close window"), s -> steps.add("exit " + s),
				s -> steps.add("halt " + s));
		assertEquals(Arrays.asList("cancel session", "stop camera", "close window", "exit 0"), steps);
	}

	@Test
	public void theTestTemplateStaysUnderEvalData() throws Exception {
		File inside = LivenessScreenDemo.templateFile(null);
		assertTrue(inside.getPath(), inside.getPath().replace('\\', '/').endsWith("eval-data/f15/test-template.iso"));
		assertTrue(LivenessScreenDemo.templateFile("eval-data/f15/other.iso").getPath().contains("eval-data"));
		for (String outside : new String[] { "test-template.iso", "docs/f15/test-template.iso",
				new File(System.getProperty("java.io.tmpdir"), "t.iso").getPath() }) {
			try {
				LivenessScreenDemo.templateFile(outside);
				fail("accepted " + outside);
			} catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("eval-data"));
			}
		}
	}
}
