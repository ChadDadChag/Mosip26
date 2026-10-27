package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * {@code docs/ui-liveness-spec.md} §9 on the Desktop: every documented state (the screen demo's, {@code
 * docs/images/part4}) at the client's window size, 720x820, and at 1280x800. The buttons are on screen, 48 high and of
 * equal width; the content is centred and at most 560 wide; the camera card keeps 4:3 at about 45% of the height,
 * with the badge on the oval's top centre; nothing overlaps; the status takes at most 2 lines; a failed attempt
 * shows one attempts line. Arabic is mirrored, except the camera card. Skipped without a JavaFX toolkit (headless).
 */
public class LivenessViewLayoutTest {

	private static final String ARABIC_RETRY = "إعادة المحاولة";

	@BeforeClass
	public static void toolkit() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		try {
			Platform.startup(started::countDown);
		} catch (IllegalStateException alreadyRunning) {
			started.countDown();
		} catch (RuntimeException | UnsatisfiedLinkError headless) {
			Assume.assumeNoException("no JavaFX toolkit", headless);
		}
		assertTrue(started.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void everyStateFitsTheClientWindow() throws Exception {
		everyState(LivenessMessages.english(), 720, 820);
	}

	@Test
	public void everyStateFitsALargeScreen() throws Exception {
		everyState(LivenessMessages.english(), 1280, 800);
	}

	@Test
	public void arabicIsMirroredExceptTheCameraCard() throws Exception {
		ResourceBundle ar = ResourceBundle.getBundle("messages", new Locale("ar"), getClass().getClassLoader(),
				ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
		LivenessMessages messages = LivenessMessages.of(ar, "ara");
		assertTrue(messages.rightToLeft());
		everyState(messages, 720, 820);
		for (LivenessScreenDemo.Screen s : LivenessScreenDemo.screens()) {
			onFx(() -> {
				LivenessView v = LivenessScreenDemo.layOut(s, messages, 720, 820, null);
				assertEquals(NodeOrientation.RIGHT_TO_LEFT, v.root().getEffectiveNodeOrientation());
				assertEquals(NodeOrientation.LEFT_TO_RIGHT, v.camera().getEffectiveNodeOrientation());
				assertEquals(-1, v.camera().preview().getScaleX(), 0); // still a selfie view
				// The status badge right of the text; the step label right of the bar; Retry right of Cancel.
				Node badge = v.statusCard().lookup(".liveness-status-badge");
				assertTrue(s.name, centerX(badge) > centerX(v.status()));
				if (shown(v.segments())) {
					assertTrue(s.name, centerX(v.step()) > centerX(v.segments()));
				}
				Button[] b = v.buttons();
				if (b[0].isManaged() && b[1].isManaged()) {
					assertEquals(ARABIC_RETRY, b[0].getText());
					assertTrue(s.name, centerX(b[0]) > centerX(b[1]));
				}
				v.dispose();
			});
		}
	}

	private static void everyState(LivenessMessages messages, double width, double height) throws Exception {
		for (LivenessScreenDemo.Screen s : LivenessScreenDemo.screens()) {
			onFx(() -> {
				LivenessView v = LivenessScreenDemo.layOut(s, messages, width, height, null);
				String at = s.name + " at " + (int) width + "x" + (int) height;

				// Content: centred, at most 560 wide, above the buttons.
				Bounds content = scene(v.content());
				assertTrue(at, content.getWidth() <= LivenessView.MAX_CONTENT_WIDTH + 0.5);
				assertEquals(at, width / 2, content.getMinX() + content.getWidth() / 2, 0.5);
				List<Button> buttons = new ArrayList<>();
				for (Button b : v.buttons()) {
					if (b.isManaged()) {
						buttons.add(b);
					}
				}
				for (Button b : buttons) {
					Bounds r = scene(b);
					assertTrue(at + " " + b.getText(), r.getMinY() >= 0 && r.getMaxY() <= height);
					assertTrue(at + " " + b.getText(), r.getHeight() >= 48);
					assertEquals(at, scene(buttons.get(0)).getWidth(), r.getWidth(), 0.5);
				}
				double lowest = 0;
				for (Node n : v.content().getChildren()) {
					if (n.isManaged()) {
						lowest = Math.max(lowest, scene(n).getMaxY());
					}
				}
				if (!buttons.isEmpty()) {
					assertTrue(at + ": content ends at " + lowest, lowest <= scene(buttons.get(0)).getMinY());
				}

				// The camera card: 4:3, about 45% of the height (less with the tips), the badge on the oval's top.
				LivenessCameraCard c = v.camera();
				assertEquals(at, 4.0 / 3, c.cardWidth() / c.cardHeight(), 0.01);
				assertTrue(at + ": camera " + c.cardHeight(), c.cardHeight() <= height * LivenessView.CAMERA_SHARE + 0.5);
				assertTrue(at + ": camera " + c.cardHeight(), c.cardHeight() >= height * (shown(v.tips()) ? 0.25 : 0.40));
				Bounds badge = scene(c.badge());
				Bounds oval = scene(c.oval());
				assertEquals(at, oval.getMinX() + oval.getWidth() / 2, badge.getMinX() + badge.getWidth() / 2, 1);
				assertEquals(at, oval.getMinY() + 2, badge.getMinY() + badge.getHeight() / 2, 1.5); // + half the stroke
				assertEquals(at, LivenessCameraCard.BADGE, badge.getWidth(), 0.5);
				assertTrue(at, scene(v.statusCard()).getMinY() >= scene(c).getMaxY());

				// The status: at most 2 lines.
				Label status = v.status();
				assertTrue(at + ": " + status.getText(),
						LivenessView.lines(status.getText(), status.getFont(), status.getMaxWidth()) <= 2);

				// A failed attempt: the attempts left, never "Attempt a of m" as well.
				if (s.name.startsWith("08-") || s.name.startsWith("09-")) {
					assertTrue(at, shown(v.progressText()));
					assertEquals(at, 15, v.progressText().getFont().getSize(), 0.01); // body size, not a caption
					assertTrue(at, !shown(v.attempt()));
					String attemptOf = messages.text("liveness.progress.attempt", 1, 4);
					for (Node n : v.root().lookupAll(".label")) {
						assertTrue(at, !shown(n) || !attemptOf.equals(((Label) n).getText()));
					}
				}
				v.dispose();
			});
		}
	}

	private static Bounds scene(Node n) {
		return n.localToScene(n.getBoundsInLocal());
	}

	private static double centerX(Node n) {
		Bounds b = scene(n);
		return b.getMinX() + b.getWidth() / 2;
	}

	/** Visible, with every parent visible. */
	private static boolean shown(Node n) {
		for (Parent p = n.getParent(); p != null; p = p.getParent()) {
			if (!p.isVisible()) {
				return false;
			}
		}
		return n.isVisible();
	}

	interface FxCheck {
		void run() throws Exception;
	}

	private static void onFx(FxCheck check) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				check.run();
			} catch (Throwable t) {
				failure.set(t);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS));
		if (failure.get() instanceof Exception) {
			throw (Exception) failure.get();
		}
		if (failure.get() != null) {
			throw (Error) failure.get();
		}
	}
}
