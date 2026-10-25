package io.mosip.registration.controller.liveness;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEvent.Guidance;
import io.mosip.registration.liveness.api.LivenessEvent.Type;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.device.mock.MockFrameSource;
import io.mosip.registration.liveness.device.mock.SarxosWebcamFrameGrabber;
import io.mosip.registration.liveness.device.util.PreviewFrameSource;
import io.mosip.registration.liveness.fake.FakeLivenessService;
import io.mosip.registration.liveness.fake.FakeScenario;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

/**
 * Development tool for the Desktop liveness screen, outside the Registration Client (no login, no database, no SBI):
 * <pre>
 *   LivenessScreenDemo --snapshots &lt;dir&gt; [--face &lt;image&gt;]     every screen state as PNG (the UI deliverable)
 *   LivenessScreenDemo --scenario PASS_ACTIVE [--workflow operator] the scripted engine, interactive
 *   LivenessScreenDemo --live webcam|video:&lt;file.mjpeg&gt;          the real engine and models on a webcam or clip
 * </pre>
 * It drives exactly the classes the client uses ({@link LivenessScreenModel}, {@link LivenessView}); only the
 * Spring wiring of {@link LivenessGate} is replaced. It lives in the test sources, so it never ships in the client jar
 * (it writes PNG files and can open a webcam); run it on the test classpath (docs/part4-desktop-integration.md §10).
 */
public final class LivenessScreenDemo {

	private LivenessScreenDemo() {
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> a = new HashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2) {
			a.put(args[i], args[i + 1]);
		}
		CountDownLatch started = new CountDownLatch(1);
		Platform.startup(started::countDown);
		started.await();
		if (a.containsKey("--snapshots")) {
			snapshots(new File(a.get("--snapshots")), a.get("--face"));
			Platform.exit();
			return;
		}
		WorkflowType workflow = WorkflowType.valueOf(a.getOrDefault("--workflow", "resident").toUpperCase());
		Platform.setImplicitExit(true);
		Platform.runLater(() -> {
			try {
				interactive(workflow, a.get("--scenario"), a.get("--live"));
			} catch (Exception e) {
				e.printStackTrace();
				Platform.exit();
			}
		});
	}

	// ------------------------------------------------------------------ interactive

	private static void interactive(WorkflowType workflow, String scenario, String live) throws Exception {
		Map<String, String> cfg = new HashMap<>();
		ConfigProvider config = cfg::get;
		LivenessService service;
		FrameSource device;
		if (live != null) {
			OnnxFaceAnalyzerFactory analyzers = new OnnxFaceAnalyzerFactory();
			analyzers.preload();
			service = DefaultLivenessService.builder().config(config).analyzerFactory(analyzers)
					.auditSink((e, wf, id, d) -> System.out.println("AUDIT " + e + " " + d)).build();
			device = live.startsWith("video:") ? MockFrameSource.ofVideoFile(Paths.get(live.substring(6)), 15.0, true)
					: MockFrameSource.ofWebcam(new SarxosWebcamFrameGrabber(), 15.0);
		} else {
			service = FakeLivenessService.builder(config)
					.scenario(scenario == null ? FakeScenario.RANDOM : FakeScenario.valueOf(scenario.toUpperCase()))
					.auditSink((e, wf, id, d) -> System.out.println("AUDIT " + e + " " + d)).build();
			device = new FrameSource() {
				@Override public DeviceInfo info() { return new DeviceInfo("none", "", "", "", "NONE", 0, 0, 0); }
				@Override public void start(Listener l) { }
				@Override public void stop() { }
				@Override public boolean isRunning() { return false; }
			};
		}
		LivenessSession session = service.newSession(workflow);
		LivenessScreenModel model = new LivenessScreenModel(workflow, service.policy(workflow).maxRetries);
		LivenessView view = LivenessView.load(LivenessMessages.english(), true);
		Stage stage = new Stage();
		stage.setTitle(LivenessMessages.english().text(model.title()));
		stage.setScene(new Scene(view.root()));
		view.render(model);
		PreviewFrameSource source = new PreviewFrameSource(device);
		if (live != null) {
			source.addPreviewListener(view);
		} else {
			view.showPreview(false, "Scripted liveness engine: no camera preview");
		}
		view.setOnRetry(session::retry);
		view.setOnCancel(session::cancel);
		view.setOnClose(stage::close);
		stage.setOnHidden(e -> {
			session.cancel();
			view.dispose();
			Platform.exit();
			System.exit(0);
		});
		stage.show();
		session.start(source, new LivenessListener() {
			@Override
			public void onEvent(LivenessEvent e) {
				long now = System.currentTimeMillis();
				Platform.runLater(() -> {
					model.onEvent(e, now);
					view.render(model);
				});
			}

			@Override
			public void onComplete(LivenessResult r) {
				System.out.println("RESULT " + r.decision + " " + (r.failureCode == null ? r.passPath : r.failureCode));
				Platform.runLater(() -> {
					model.onComplete(r);
					view.render(model);
				});
			}
		});
	}

	// ------------------------------------------------------------------ snapshots

	private static void snapshots(File dir, String face) throws Exception {
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		Frame still = face == null ? null : still(new File(face));
		Map<String, Consumer<LivenessScreenModel>> states = new LinkedHashMap<>();
		states.put("01-checking", m -> {
			ev(m, LivenessEvent.of(Type.STARTED, 1));
			ev(m, LivenessEvent.of(Type.CHECKING_PASSIVE, 1));
		});
		states.put("02-guidance", m -> {
			ev(m, LivenessEvent.of(Type.CHECKING_PASSIVE, 1));
			ev(m, LivenessEvent.guidance(Guidance.MOVE_CLOSER, 1));
		});
		states.put("03-challenge-blink", m -> ev(m, LivenessEvent.prompt(ChallengeType.BLINK, 1, 2, 6000, 1)));
		states.put("04-challenge-progress", m -> {
			ev(m, LivenessEvent.prompt(ChallengeType.TURN_LEFT, 2, 2, 6000, 1));
			ev(m, LivenessEvent.progress(ChallengeType.TURN_LEFT, 2, 2, 0.55f, 1));
		});
		states.put("05-action-detected", m -> {
			ev(m, LivenessEvent.prompt(ChallengeType.TURN_LEFT, 2, 2, 6000, 1));
			ev(m, LivenessEvent.of(Type.ACTION_DETECTED, 1));
		});
		states.put("06-hold-still-look-at-camera", m -> {
			ev(m, LivenessEvent.of(Type.HOLD_STILL, 1));
			ev(m, LivenessEvent.guidance(Guidance.LOOK_AT_CAMERA, 1));
		});
		states.put("07-passed", m -> ev(m, LivenessEvent.of(Type.PASSED, 1)));
		states.put("08-attempt-failed-retry", m -> ev(m,
				LivenessEvent.failure(Type.ATTEMPT_FAILED, ErrorCode.CHALLENGE_TIMEOUT, 1, 3, true)));
		states.put("09-pad-failure-generic", m -> ev(m,
				LivenessEvent.failure(Type.ATTEMPT_FAILED, ErrorCode.PAD_FAILURE, 2, 2, true)));
		states.put("10-device-error", m -> ev(m,
				LivenessEvent.failure(Type.DEVICE_ERROR, ErrorCode.DEVICE_DISCONNECTED, 1, 0, true)));
		states.put("11-max-attempts-resident", m -> ev(m,
				LivenessEvent.failure(Type.FAILED_FINAL, ErrorCode.MAX_RETRIES_EXCEEDED, 4, 0, false)));

		for (Map.Entry<String, Consumer<LivenessScreenModel>> s : states.entrySet()) {
			boolean noFrames = s.getKey().startsWith("10-");
			snap(new File(dir, s.getKey() + ".png"), WorkflowType.RESIDENT, s.getValue(), noFrames ? null : still);
		}
		snap(new File(dir, "12-operator-login-challenge.png"), WorkflowType.OPERATOR,
				m -> ev(m, LivenessEvent.prompt(ChallengeType.SMILE, 1, 1, 6000, 1)), still);
		snap(new File(dir, "13-supervisor-final-failure.png"), WorkflowType.SUPERVISOR,
				m -> ev(m, LivenessEvent.failure(Type.FAILED_FINAL, ErrorCode.PAD_FAILURE, 3, 0, false)), still);
		snap(new File(dir, "14-unavailable-fail-closed.png"), WorkflowType.OPERATOR,
				LivenessScreenModel::showUnavailable, null);
		System.out.println("Wrote liveness screens to " + dir.getAbsolutePath());
	}

	private static void ev(LivenessScreenModel m, LivenessEvent e) {
		m.onEvent(e, System.nanoTime() / 1_000_000 + 10_000);
	}

	private static void snap(File out, WorkflowType workflow, Consumer<LivenessScreenModel> drive, Frame still)
			throws Exception {
		AtomicReference<Exception> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				LivenessScreenModel model = new LivenessScreenModel(workflow, 3);
				drive.accept(model);
				LivenessView view = LivenessView.load(LivenessMessages.english(), false);
				Scene scene = new Scene(view.root());
				if (still != null) {
					view.showStill(still);
				} else {
					view.showPreview(false, ""); // no frames from the device
				}
				view.render(model);
				view.root().applyCss();
				view.root().layout();
				WritableImage img = scene.snapshot(null);
				view.dispose();
				ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
			} catch (Exception e) {
				failure.set(e);
			} finally {
				done.countDown();
			}
		});
		done.await();
		if (failure.get() != null) {
			throw failure.get();
		}
	}

	/** A 4:3 crop around the top of the picture, as a camera would frame a face. */
	private static Frame still(File image) throws IOException {
		BufferedImage src = ImageIO.read(image);
		int w = src.getWidth();
		int h = Math.min(src.getHeight(), w * 3 / 4);
		int y0 = Math.max(0, (int) (src.getHeight() * 0.06));
		h = Math.min(h, src.getHeight() - y0);
		byte[] rgb = new byte[w * h * 3];
		for (int y = 0, o = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = src.getRGB(x, y0 + y);
				rgb[o++] = (byte) (p >> 16);
				rgb[o++] = (byte) (p >> 8);
				rgb[o++] = (byte) p;
			}
		}
		return new Frame(rgb, w, h, 0, 0, "demo");
	}
}
