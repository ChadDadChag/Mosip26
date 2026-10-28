package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
import javafx.scene.text.Text;
import javafx.stage.Stage;

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

	/**
	 * The window is 720x820 and not resizable. A 1366x768 screen, or 1920x1080 at 150%, has about 728 px of usable
	 * height: with the title bar, the buttons were below the screen (task 19 review). The window now takes at most the
	 * screen's usable height, and every state fits there.
	 */
	@Test
	public void theWindowFitsAShortScreenAndEveryStateFitsThere() throws Exception {
		AtomicReference<double[]> sizes = new AtomicReference<>();
		onFx(() -> {
			LivenessView short768 = LivenessView.load(LivenessMessages.english(), false);
			short768.fitTo(new Rectangle2D(0, 0, 1366, 728)); // 1366x768 less a 40 px taskbar
			LivenessView large = LivenessView.load(LivenessMessages.english(), false);
			large.fitTo(new Rectangle2D(0, 0, 1920, 1040));
			Region s768 = (Region) short768.root(), big = (Region) large.root();
			sizes.set(new double[] { s768.getPrefWidth(), s768.getPrefHeight(), big.getPrefWidth(), big.getPrefHeight() });
			short768.dispose();
			large.dispose();
		});
		double[] s = sizes.get();
		assertEquals(720, s[0], 0);
		assertEquals(728 - LivenessView.TITLE_BAR, s[1], 0);
		assertEquals(720, s[2], 0);
		assertEquals(820, s[3], 0);
		everyState(LivenessMessages.english(), s[0], s[1]);
	}

	/**
	 * A render sets the status label's text once, already in its lines, and not at all when the status is the same:
	 * each change is a relayout and, for a screen reader, a text change (task 19 review).
	 */
	@Test
	public void aRenderChangesTheStatusTextOnlyWhenTheStatusChanges() throws Exception {
		int[] wrapped = new int[1];
		for (double width : new double[] { 720, 320 }) {
			for (LivenessScreenDemo.Screen s : LivenessScreenDemo.screens()) {
				onFx(() -> {
					LivenessView v = LivenessScreenDemo.layOut(s, LivenessMessages.english(), width, 820, null);
					wrapped[0] += v.status().getText().contains("\n") ? 1 : 0;
					List<String> changes = new ArrayList<>();
					v.status().textProperty().addListener((o, a, b) -> changes.add(b));
					LivenessScreenModel same = new LivenessScreenModel(s.workflow, 3);
					s.drive.accept(same);
					v.render(same);
					assertEquals(s.name + " at " + (int) width + ": " + changes, 0, changes.size());
					v.dispose();
				});
			}
		}
		assertTrue("a wrapped status was rendered", wrapped[0] > 0);
	}

	/** At 125%, 150% and 175% display scaling no status, tip or button text is cut short. */
	@Test
	public void noTextIsCutShortAtFractionalScaling() throws Exception {
		for (double scale : new double[] { 1.25, 1.5, 1.75 }) {
			for (double width : new double[] { 720, 480, 320 }) {
				for (LivenessScreenDemo.Screen s : LivenessScreenDemo.screens()) {
					onFx(() -> {
						LivenessView v = LivenessScreenDemo.layOut(s, LivenessMessages.english(), width, 820, null);
						Stage stage = new Stage();
						stage.setRenderScaleX(scale);
						stage.setRenderScaleY(scale);
						stage.setScene(v.root().getScene());
						for (int i = 0; i < 2; i++) {
							v.root().applyCss();
							v.root().layout();
						}
						String at = s.name + " at " + (int) width + " x" + scale;
						assertTrue(at + ": sizes snap to the scale's pixels", Math.abs(v.status().snapSizeX(1) - 1) > 0.1);
						for (Node n : v.root().lookupAll(".label")) {
							Label l = (Label) n;
							Text drawn = (Text) l.lookup(".text");
							if (shown(l) && drawn != null) {
								assertEquals(at, l.getText(), drawn.getText());
							}
						}
						for (Button b : v.buttons()) {
							Text drawn = (Text) b.lookup(".text");
							if (b.isManaged() && drawn != null) {
								assertEquals(at, b.getText(), drawn.getText());
							}
						}
						v.dispose();
					});
				}
			}
		}
	}

	/**
	 * A wrapped status keeps its badge next to its longest line, the two centred in the card; a wrapped tip continues
	 * under its text, not under the bullet. Narrow windows (480, 320 and 240 wide) make both happen, left to right and
	 * in Arabic.
	 */
	@Test
	public void wrappedStatusesHugTheirBadgeAndWrappedTipsHang() throws Exception {
		Map<String, Integer> met = new HashMap<>();
		for (LivenessMessages messages : new LivenessMessages[] { LivenessMessages.english(), arabic() }) {
			for (double width : new double[] { 720, 480, 320, 240 }) {
				for (LivenessScreenDemo.Screen s : LivenessScreenDemo.screens()) {
					onFx(() -> {
						LivenessView v = LivenessScreenDemo.layOut(s, messages, width, 820, null);
						checkWrapping(v, s.name + " at " + (int) width + (messages.rightToLeft() ? " ar" : " en"), met);
						v.dispose();
					});
				}
			}
		}
		for (String k : new String[] { "status ltr", "status rtl", "tip ltr", "tip rtl" }) {
			assertTrue(k + " met: " + met, met.getOrDefault(k, 0) > 0);
		}
	}

	private static void checkWrapping(LivenessView v, String at, Map<String, Integer> met) {
		boolean rtl = v.root().getEffectiveNodeOrientation() == NodeOrientation.RIGHT_TO_LEFT;
		String dir = rtl ? " rtl" : " ltr";
		List<double[]> lines = lines(v.status());
		if (lines.size() > 1) {
			met.merge("status" + dir, 1, Integer::sum);
			double left = Double.MAX_VALUE, right = -Double.MAX_VALUE;
			for (double[] line : lines) {
				left = Math.min(left, line[0]);
				right = Math.max(right, line[1]);
			}
			Bounds badge = scene(v.statusCard().lookup(".liveness-status-badge"));
			double gap = rtl ? badge.getMinX() - right : left - badge.getMaxX();
			assertTrue(at + ": badge to the longest line " + gap, gap >= 0 && gap <= 14);
			double group = rtl ? (left + badge.getMaxX()) / 2 : (badge.getMinX() + right) / 2;
			Bounds card = scene(v.statusCard());
			assertEquals(at + ": group centred", card.getMinX() + card.getWidth() / 2, group, 2);
			// The label draws exactly its lines, none cut short.
			assertEquals(at, v.status().getText(), ((Text) v.status().lookup(".text")).getText());
			assertEquals(at + ": " + v.status().getText(), v.status().getText().split("\n").length, lines.size());
		}
		for (Node row : v.tips().getChildren()) {
			if (!(row instanceof HBox)) {
				continue; // the title
			}
			Node bullet = ((HBox) row).getChildren().get(0);
			Label text = (Label) ((HBox) row).getChildren().get(1);
			List<double[]> tip = lines(text);
			assertTrue(at, !text.getText().startsWith("•"));
			// Every tip starts next to its bullet, wrapped or not.
			Bounds dot = scene(bullet);
			double start = rtl ? dot.getMinX() - tip.get(0)[1] : tip.get(0)[0] - dot.getMaxX();
			assertEquals(at + ": bullet to text, " + text.getText(), LivenessView.BULLET_GAP, start, 2);
			if (tip.size() < 2) {
				continue;
			}
			met.merge("tip" + dir, 1, Integer::sum);
			Bounds b = scene(bullet);
			for (double[] line : tip) {
				if (rtl) {
					assertEquals(at + ": " + text.getText(), tip.get(0)[1], line[1], 1);
					assertTrue(at, line[1] < b.getMinX());
				} else {
					assertEquals(at + ": " + text.getText(), tip.get(0)[0], line[0], 1);
					assertTrue(at, line[0] > b.getMaxX());
				}
			}
		}
	}

	/** A label's lines, top to bottom, as {left, right} in scene coordinates: its visible characters only. */
	private static List<double[]> lines(Label label) {
		Text text = (Text) label.lookup(".text");
		List<double[]> boxes = new ArrayList<>(); // {left, right, top, bottom}
		Matcher word = Pattern.compile("\\S+").matcher(text.getText());
		while (word.find()) {
			double[] box = null;
			for (PathElement e : text.rangeShape(word.start(), word.end())) {
				double x, y;
				if (e instanceof MoveTo) {
					x = ((MoveTo) e).getX();
					y = ((MoveTo) e).getY();
					if (box != null) {
						boxes.add(box);
					}
					box = null;
				} else if (e instanceof LineTo) {
					x = ((LineTo) e).getX();
					y = ((LineTo) e).getY();
				} else {
					continue;
				}
				Point2D p = text.localToScene(x, y);
				box = box == null ? new double[] { p.getX(), p.getX(), p.getY(), p.getY() }
						: new double[] { Math.min(box[0], p.getX()), Math.max(box[1], p.getX()),
								Math.min(box[2], p.getY()), Math.max(box[3], p.getY()) };
			}
			if (box != null) {
				boxes.add(box);
			}
		}
		boxes.sort((a, c) -> Double.compare(a[2], c[2]));
		List<double[]> lines = new ArrayList<>();
		for (double[] b : boxes) {
			double[] last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
			if (last != null && (b[2] + b[3]) / 2 < last[3]) {
				last[0] = Math.min(last[0], b[0]);
				last[1] = Math.max(last[1], b[1]);
				last[3] = Math.max(last[3], b[3]);
			} else {
				lines.add(new double[] { b[0], b[1], b[2], b[3] });
			}
		}
		return lines;
	}

	private LivenessMessages arabic() {
		ResourceBundle ar = ResourceBundle.getBundle("messages", new Locale("ar"), getClass().getClassLoader(),
				ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
		return LivenessMessages.of(ar, "ara");
	}

	@Test
	public void arabicIsMirroredExceptTheCameraCard() throws Exception {
		LivenessMessages messages = arabic();
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
