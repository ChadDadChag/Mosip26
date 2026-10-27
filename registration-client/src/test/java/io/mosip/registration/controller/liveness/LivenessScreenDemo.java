package io.mosip.registration.controller.liveness;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceEncoder;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.dto.ResponseDTO;
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
import io.mosip.registration.liveness.desktop.DesktopAuditSink;
import io.mosip.registration.liveness.desktop.DesktopLivenessConfig;
import io.mosip.registration.liveness.desktop.IdentityBinding;
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
 *   LivenessScreenDemo --live webcam --identity enroll|verify     F15 identity binding on a test template
 *   LivenessScreenDemo --identity delete                          deletes the test template
 * </pre>
 * {@code --identity enroll} turns a PASSED session's best frame into an ISO/IEC 19794-5 record (MOSIP's encoder, as
 * an onboarded face) in {@value #DEFAULT_TEMPLATE} (or {@code --template}, which must be under {@code eval-data/}: the
 * folder is gitignored, so the face never leaves this computer). {@code --identity verify} runs the client's
 * {@code IdentityBinding} on a PASSED session against it and prints the outcome and the similarity (the client never
 * shows a score).
 * It drives exactly the classes the client uses ({@link LivenessScreenModel}, {@link LivenessView}); only the
 * Spring wiring of {@link LivenessGate} is replaced. It lives in the test sources, so it never ships in the client jar
 * (it writes PNG files and can open a webcam); run it on the test classpath (docs/part4-desktop-integration.md §10).
 */
public final class LivenessScreenDemo {

	/** F15 diagnostics: the test template, a stand-in for an onboarded face, in the gitignored eval-data/. */
	static final String DEFAULT_TEMPLATE = "eval-data/f15/test-template.iso";

	private LivenessScreenDemo() {
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> a = new HashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2) {
			a.put(args[i], args[i + 1]);
		}
		String identity = a.get("--identity");
		if (identity != null && !Arrays.asList("enroll", "verify", "delete").contains(identity)) {
			throw new IllegalArgumentException("--identity enroll|verify|delete");
		}
		File template = templateFile(a.get("--template"));
		if ("delete".equals(identity)) {
			System.out.println(Files.deleteIfExists(template.toPath()) ? "F15 test template deleted: " + template
					: "No F15 test template at " + template);
			return;
		}
		if (identity != null && a.get("--live") == null) {
			throw new IllegalArgumentException("--identity " + identity + " needs --live webcam|video:<file>");
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
				interactive(workflow, a.get("--scenario"), a.get("--live"), identity, template);
			} catch (Exception e) {
				e.printStackTrace();
				Platform.exit();
			}
		});
	}

	/** The test template's file; refused outside an eval-data folder, so a face can never land in a tracked path. */
	static File templateFile(String path) throws IOException {
		File f = new File(path == null ? DEFAULT_TEMPLATE : path);
		if (!f.getCanonicalPath().replace('\\', '/').contains("/eval-data/")) {
			throw new IllegalArgumentException("the F15 test template must be under eval-data/ (gitignored): " + f);
		}
		return f;
	}

	// ------------------------------------------------------------------ interactive

	private static void interactive(WorkflowType workflow, String scenario, String live, String identity,
			File template) throws Exception {
		Map<String, String> cfg = new HashMap<>();
		ConfigProvider config = cfg::get;
		LivenessService service;
		FrameSource device;
		OnnxFaceAnalyzerFactory analyzers = null;
		if (live != null) {
			analyzers = new OnnxFaceAnalyzerFactory();
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
		final OnnxFaceAnalyzerFactory matchers = analyzers;
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
				if (identity != null && r.passed()) {
					identity(identity, template, matchers, workflow, r);
				}
				Platform.runLater(() -> {
					model.onComplete(r);
					view.render(model);
				});
			}
		});
	}

	/** F15 on a PASSED session: enroll its best frame as the test template, or verify it against the template. */
	private static void identity(String mode, File template, OnnxFaceAnalyzerFactory matchers, WorkflowType workflow,
			LivenessResult passed) {
		try {
			if ("enroll".equals(mode)) {
				ByteArrayOutputStream png = new ByteArrayOutputStream();
				ImageIO.write(image(passed.bestFrame), "png", png);
				ConvertRequestDto request = new ConvertRequestDto();
				request.setVersion("ISO19794_5_2011");
				request.setModality("Face");
				request.setPurpose("REGISTRATION");
				request.setImageType(0);
				request.setInputBytes(png.toByteArray());
				byte[] iso = FaceEncoder.convertFaceImageToISO(request);
				Files.createDirectories(template.getAbsoluteFile().getParentFile().toPath());
				Files.write(template.toPath(), iso);
				System.out.println("F15 test template enrolled: " + template + " (" + iso.length
						+ " bytes, gitignored; delete it with --identity delete)");
				return;
			}
			final byte[] iso = template.isFile() ? Files.readAllBytes(template.toPath()) : null;
			ApplicationContext.getInstance(); // DesktopLivenessConfig reads the (empty) application map first
			IdentityBinding binding = new IdentityBinding(w -> true, new DesktopLivenessConfig(), null,
					IdentityBinding.onnx(matchers::createMatcher),
					userId -> iso == null ? Collections.<IdentityBinding.Template>emptyList()
							: Collections.singletonList(new IdentityBinding.Template(iso, template.lastModified())),
					new DesktopAuditSink(PRINTED_AUDIT, Runnable::run), false);
			IdentityBinding.Verdict v = binding.verifyLive(workflow, "f15-test-user", IdentityBinding.GATE_DIAGNOSTICS, 1,
					passed.sessionId, passed.bestFrame);
			System.out.println(String.format(Locale.ROOT, "F15 identity check: %s, similarity %.3f (development only)", v,
					v.similarity));
		} catch (Exception e) {
			System.out.println("F15 " + mode + " failed: " + e);
		}
	}

	/** The audit records of the demo's identity check, printed (the client writes them to its audit table). */
	private static final AuditManagerService PRINTED_AUDIT = new AuditManagerService() {
		@Override
		public void audit(AuditEvent e, Components c, String refId, String refIdType) {
			audit(e, c, refId, refIdType, null);
		}

		@Override
		public void audit(AuditEvent e, Components c, String refId, String refIdType, String description) {
			System.out.println("AUDIT " + e.getId() + " " + e.getName() + " " + description);
		}

		@Override
		public ResponseDTO deleteAuditLogs() {
			return null;
		}
	};

	private static BufferedImage image(Frame f) {
		BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
		byte[] rgb = f.rgb();
		for (int y = 0, i = 0; y < f.height(); y++) {
			for (int x = 0; x < f.width(); x++, i += 3) {
				img.setRGB(x, y, (rgb[i] & 0xff) << 16 | (rgb[i + 1] & 0xff) << 8 | (rgb[i + 2] & 0xff));
			}
		}
		return img;
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
