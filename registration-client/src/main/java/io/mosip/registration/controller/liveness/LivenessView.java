package io.mosip.registration.controller.liveness;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.device.util.PreviewFrameSource;
import javafx.animation.AnimationTimer;
import javafx.animation.Animation;
import javafx.animation.ScaleTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.util.Duration;

/**
 * Renders a {@link LivenessScreenModel} ({@code fxml/LivenessCheck.fxml}, {@code liveness.css}) and shows the
 * device's live frames in the preview.
 *
 * <p>The preview is mirrored like a selfie by the view only ({@code scaleX=-1}); the frames the engine analyses are
 * never mirrored. Frames arrive on the device thread; only the newest is kept and drawn once per screen refresh.
 * Must be used on the JavaFX thread, except {@link #onPreviewFrame}.
 */
public final class LivenessView implements PreviewFrameSource.PreviewListener {

	static final String FXML = "/fxml/LivenessCheck.fxml";
	static final String CSS = "/liveness.css";

	private static final Map<LivenessScreenModel.Icon, String> ICONS = new EnumMap<>(LivenessScreenModel.Icon.class);
	private static final String WARNING_ICON = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";

	static {
		ICONS.put(LivenessScreenModel.Icon.CAMERA, "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z");
		ICONS.put(LivenessScreenModel.Icon.SCANNING, "M5 15H3v4c0 1.1.9 2 2 2h4v-2H5v-4zM5 5h4V3H5c-1.1 0-2 .9-2 2v4h2V5zm14-2h-4v2h4v4h2V5c0-1.1-.9-2-2-2zm0 16h-4v2h4c1.1 0 2-.9 2-2v-4h-2v4zM12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z");
		ICONS.put(LivenessScreenModel.Icon.EYE, "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z");
		ICONS.put(LivenessScreenModel.Icon.SMILE, "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z");
		ICONS.put(LivenessScreenModel.Icon.ARROW_LEFT, "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z");
		ICONS.put(LivenessScreenModel.Icon.ARROW_RIGHT, "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z");
		ICONS.put(LivenessScreenModel.Icon.ARROW_UP, "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z");
		ICONS.put(LivenessScreenModel.Icon.ARROW_DOWN, "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z");
		ICONS.put(LivenessScreenModel.Icon.CHECK, "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
		ICONS.put(LivenessScreenModel.Icon.CROSS, "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z");
		ICONS.put(LivenessScreenModel.Icon.CAMERA_OFF, "M21 6.5l-4 4V7c0-.55-.45-1-1-1H9.82L21 17.18V6.5zM3.27 2L2 3.27 4.73 6H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.21 0 .39-.08.54-.18L19.73 21 21 19.73 3.27 2z");
	}

	@FXML private BorderPane root;
	@FXML private Label title;
	@FXML private StackPane previewPane;
	@FXML private Label previewPlaceholder;
	@FXML private ImageView preview;
	@FXML private Pane overlay;
	@FXML private Ellipse oval;
	@FXML private StackPane ovalBadge;
	@FXML private SVGPath ovalIcon;
	@FXML private StackPane statusBadge;
	@FXML private SVGPath statusIcon;
	@FXML private Label status;
	@FXML private HBox hintRow;
	@FXML private SVGPath hintIcon;
	@FXML private Label hint;
	@FXML private Label step;
	@FXML private ProgressBar progress;
	@FXML private Label attempt;
	@FXML private VBox tips;
	@FXML private Button retryButton;
	@FXML private Button cancelButton;
	@FXML private Button closeButton;

	private final LivenessMessages messages;
	private final boolean animations;
	private final AtomicReference<Frame> pendingFrame = new AtomicReference<>();
	private WritableImage image;
	private ScaleTransition pulse;
	private Runnable onRetry = () -> { };
	private Runnable onCancel = () -> { };
	private Runnable onClose = () -> { };
	private LivenessScreenModel.Tone lastTone;

	private final AnimationTimer previewTimer = new AnimationTimer() {
		@Override
		public void handle(long now) {
			Frame f = pendingFrame.getAndSet(null);
			if (f != null) {
				draw(f);
			}
		}
	};

	private LivenessView(LivenessMessages messages, boolean animations) {
		this.messages = messages;
		this.animations = animations;
	}

	/** Loads the screen. {@code animations=false} keeps the challenge icon still (reduced motion). */
	public static LivenessView load(LivenessMessages messages, boolean animations) throws IOException {
		LivenessView view = new LivenessView(messages, animations);
		FXMLLoader loader = new FXMLLoader(LivenessView.class.getResource(FXML));
		loader.setController(view);
		loader.load();
		view.init();
		return view;
	}

	private void init() {
		root.getStylesheets().add(LivenessView.class.getResource(CSS).toExternalForm());
		Shape mask = Shape.subtract(new Rectangle(480, 360), new Ellipse(oval.getCenterX(), oval.getCenterY(),
				oval.getRadiusX(), oval.getRadiusY()));
		mask.getStyleClass().add("liveness-mask");
		overlay.getChildren().add(0, mask);
		hintIcon.setContent(WARNING_ICON);
		retryButton.setText(messages.text("liveness.button.retry"));
		cancelButton.setText(messages.text("liveness.button.cancel"));
		closeButton.setText(messages.text("liveness.button.close"));
		showPreview(false, "");
		previewTimer.start();
	}

	public Parent root() {
		return root;
	}

	public void setOnRetry(Runnable r) { onRetry = r; }
	public void setOnCancel(Runnable r) { onCancel = r; }
	public void setOnClose(Runnable r) { onClose = r; }

	@FXML private void onRetry() { onRetry.run(); }
	@FXML private void onCancel() { onCancel.run(); }
	@FXML private void onClose() { onClose.run(); }

	/** Shows the live preview, or the placeholder text instead (no frames, e.g. the scripted engine). */
	public void showPreview(boolean live, String placeholder) {
		preview.setVisible(live);
		previewPlaceholder.setVisible(!live);
		previewPlaceholder.setText(placeholder);
	}

	/** Called on the device thread for every frame. Never blocks. */
	@Override
	public void onPreviewFrame(Frame frame) {
		pendingFrame.set(frame);
	}

	/** Draws one frame now (the screen demo's snapshots, which run without screen refreshes). */
	void showStill(Frame f) {
		draw(f);
	}

	private void draw(Frame f) {
		if (image == null || (int) image.getWidth() != f.width() || (int) image.getHeight() != f.height()) {
			image = new WritableImage(f.width(), f.height());
			preview.setImage(image);
			showPreview(true, "");
		}
		image.getPixelWriter().setPixels(0, 0, f.width(), f.height(), PixelFormat.getByteRgbInstance(), f.rgb(), 0,
				f.width() * 3);
	}

	public void render(LivenessScreenModel m) {
		title.setText(messages.text(m.title()));
		setText(status, m.status());
		setText(hint, m.hint());
		hintIcon.setVisible(m.hint() != null && m.hintIsGuidance());
		hintIcon.setManaged(hintIcon.isVisible());

		String icon = ICONS.get(m.icon());
		ovalIcon.setContent(icon);
		statusIcon.setContent(icon);
		if (m.tone() != lastTone) {
			root.getStyleClass().removeIf(c -> c.startsWith("tone-"));
			root.getStyleClass().add("tone-" + m.tone().name().toLowerCase());
			lastTone = m.tone();
		}
		animateChallengeIcon(m.tone() == LivenessScreenModel.Tone.CHALLENGE);

		switch (m.progressMode()) {
			case INDETERMINATE:
				progress.setVisible(true);
				progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
				break;
			case DETERMINATE:
				progress.setVisible(true);
				progress.setProgress(m.progress());
				break;
			default:
				progress.setVisible(false);
				break;
		}
		setText(step, m.step());
		setText(attempt, m.attempt());

		tips.getChildren().clear();
		tips.setVisible(m.showTips());
		tips.setManaged(m.showTips());
		if (m.showTips()) {
			Label t = new Label(messages.text("liveness.tips.title"));
			t.getStyleClass().add("liveness-tips-title");
			tips.getChildren().add(t);
			for (String key : LivenessMessages.TIP_KEYS) {
				Label l = new Label("• " + messages.text(key));
				l.setWrapText(true);
				tips.getChildren().add(l);
			}
		}

		show(retryButton, m.showRetry());
		show(cancelButton, m.showCancel());
		show(closeButton, m.showClose());
		Button focus = m.showRetry() ? retryButton : m.showClose() ? closeButton : null;
		if (focus != null) {
			focus.requestFocus();
		}

		if (m.announcement() != null) {
			// JavaFX has no live regions: update the accessible text and notify the screen reader.
			status.setAccessibleText(messages.text(m.status()) + (m.hint() == null ? "" : ". " + messages.text(m.hint())));
			status.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
		}
	}

	private void setText(Label label, LivenessScreenModel.Text text) {
		label.setText(text == null ? "" : messages.text(text));
		label.setVisible(text != null);
	}

	private static void show(Button b, boolean visible) {
		b.setVisible(visible);
		b.setManaged(visible);
	}

	private void animateChallengeIcon(boolean on) {
		if (!animations) {
			return;
		}
		if (on && pulse == null) {
			pulse = new ScaleTransition(Duration.millis(700), ovalBadge);
			pulse.setFromX(1);
			pulse.setFromY(1);
			pulse.setToX(1.18);
			pulse.setToY(1.18);
			pulse.setAutoReverse(true);
			pulse.setCycleCount(Animation.INDEFINITE);
			pulse.play();
		} else if (!on && pulse != null) {
			pulse.stop();
			ovalBadge.setScaleX(1);
			ovalBadge.setScaleY(1);
			pulse = null;
		}
	}

	/** Stops the preview timer and any animation; call when the window closes. */
	public void dispose() {
		previewTimer.stop();
		animateChallengeIcon(false);
		pendingFrame.set(null);
	}
}
