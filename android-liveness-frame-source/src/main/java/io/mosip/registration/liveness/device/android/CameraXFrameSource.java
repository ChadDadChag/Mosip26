package io.mosip.registration.liveness.device.android;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.mosip.registration.liveness.api.DeviceCapabilities;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

/**
 * CameraX-backed {@link FrameSource} for the Android Registration Client.
 *
 * <p><b>Build/test status:</b> written against the CameraX API shape without an Android SDK, then
 * compiled (AGP 8.3.2, CameraX 1.3.4, compileSdk 34) in the Android Registration Client's
 * {@code liveness} module, which copies this file in with Person 5's {@code android-integration/scripts/sync-engine.ps1} (pushed separately).
 * Runtime results on a phone are recorded with the Person 5 Android integration, not here.</p>
 *
 * <p><b>Why CameraX over the SBI-intent path:</b> the task explicitly allows
 * preferring "CameraX fallback if that is cleaner than implementing the SBI-intent
 * path". An SBI-over-intents design would need to define and maintain a private,
 * app-to-app IPC/streaming protocol against an Android SBI provider app that does
 * not appear to exist yet in this project, which is materially more moving parts
 * than a single, well-documented Jetpack library call for a straightforward camera
 * feed. CameraX is also already the Android-recommended path for exactly this use
 * case (continuous frame analysis), keeping the dependency footprint to one
 * well-maintained Jetpack library rather than a bespoke IPC layer.</p>
 *
 * <p><b>Preview:</b> with a {@link Preview.SurfaceProvider} (for example a {@code PreviewView}'s), a
 * {@link Preview} is bound together with the {@link ImageAnalysis} in one {@code bindToLifecycle} call,
 * so both use cases share the camera session and the same 4:3 framing. Without one, only the analysis
 * is bound. The preview is display-only: frames come from the analysis use case.</p>
 *
 * <p><b>Exposure assist (dim faces):</b> on by default ({@link #setExposureAssist}). The mean luma of the face region
 * is taken from the already-copied Y plane of each delivered frame; while it stays below about 70 the camera's
 * exposure compensation is stepped up, and back toward 0 above about 150 (see {@code ExposureAssist}).</p>
 *
 * <p><b>Orientation:</b> each frame is rotated by CameraX's
 * {@code getRotationDegrees()} so faces arrive upright, matching the Desktop sources.
 * Frames are not mirrored (the front-camera preview may be; the analysis frames are not).
 * {@code getRotationDegrees()} is relative to the use cases' target rotation, which is set from the app window's
 * rotation ({@link WindowRotation}; by default the lifecycle owner's window when it is an Activity) at bind time and
 * again whenever a display changes. CameraX's own default is the physical display's rotation, which differs from the
 * window's while another app holds a fixed-rotation transform: then every face arrived sideways (F19).</p>
 *
 * <p><b>Every-Nth-frame:</b> {@code frameStride} skips conversion of unselected frames;
 * {@link Frame#sequence()} stays the raw frame index, so skips show up as gaps,
 * the same as the Desktop sources.</p>
 *
 * <p><b>Pacing:</b> {@code maxDeliverFps} (default {@value #DEFAULT_MAX_DELIVER_FPS}, the engine's highest analysis
 * rate, during challenges; 0 = unlimited) skips, before any conversion, frames whose camera timestamp is less than
 * 1/maxDeliverFps after the last delivered one, less 10% for timestamp jitter, so a 30 fps camera delivers 15 fps
 * and not 10. It applies after {@code frameStride}; skipped frames are gaps in {@link Frame#sequence()} too.</p>
 *
 * <p>Depends only on {@code liveness-api} (pure Java, no Android/Desktop-specific
 * types) plus CameraX - it does not reference any Desktop-side class
 * ({@code SbiStreamFrameSource}, {@code MockFrameSource}, {@code BioService}, etc.),
 * so it cannot affect or break the Desktop Maven build; this module is
 * intentionally not wired into the Desktop reactor's {@code <modules>} list.</p>
 */
public final class CameraXFrameSource implements FrameSource {

    private static final String TAG = "CameraXFrameSource";
    private static final int UNKNOWN_CAPABILITY = 0;
    private static final long TIMING_WINDOW_NS = 1_000_000_000L;
    public static final int DEFAULT_MAX_DELIVER_FPS = 15;
    private static final long NONE = Long.MIN_VALUE;

    // BT.601 full-range coefficients in 16.16 fixed point: 1.402, 0.344136, 0.714136, 1.772.
    private static final int R_V = 91881;
    private static final int G_U = 22554;
    private static final int G_V = 46802;
    private static final int B_U = 116130;
    private static final int HALF = 1 << 15;

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final CameraSelector cameraSelector;
    private final Size targetResolution;
    private final int frameStride;
    private final long minDeliverIntervalNs;
    private final Preview.SurfaceProvider previewSurface;
    private final ExecutorService analysisExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();

    private volatile Listener listener;
    private volatile ImageAnalysis imageAnalysis;
    private volatile Preview boundPreview;
    private volatile Camera boundCamera;
    // Where the use cases' target rotation comes from (F19); null: CameraX's default, the display's rotation.
    private volatile WindowRotation windowRotation;
    private final Object rotationLock = new Object();
    private Runnable stopWatchingRotation; // guarded by rotationLock
    private volatile DeviceInfo deviceInfo;
    private volatile ProcessCameraProvider cameraProvider;
    private volatile boolean debugTiming;
    // Exposure assist for dim faces (on by default): see ExposureAssist.
    private final ExposureAssist exposure = new ExposureAssist();
    private volatile double lastCentreLuma = Double.NaN;

    // Analysis-thread only: the pacing state, plane copies reused across frames (the RGB output is not, see
    // onImageAvailable) and the debug timing window.
    private long lastDeliveredNs = NONE;
    private int lastRotationDegrees = -1;
    private byte[] yBytes = new byte[0];
    private byte[] uBytes = new byte[0];
    private byte[] vBytes = new byte[0];
    private long windowStartNs;
    private int windowReceived;
    private int windowConverted;
    private long windowConvertNs;
    private long windowConvertMaxNs;

    /**
     * @param context          application (not Activity) context, to avoid leaking an Activity
     * @param lifecycleOwner   the Activity/Fragment CameraX binds the camera's lifecycle to; an Activity's window
     *                         also gives the target rotation ({@link #setWindowRotation})
     * @param cameraSelector   e.g. {@link CameraSelector#DEFAULT_FRONT_CAMERA} for a face capture flow
     * @param targetResolution requested analysis resolution; CameraX may choose the closest supported one
     */
    public CameraXFrameSource(Context context, LifecycleOwner lifecycleOwner,
                              CameraSelector cameraSelector, Size targetResolution) {
        this(context, lifecycleOwner, cameraSelector, targetResolution, 1);
    }

    /**
     * @param frameStride deliver every Nth camera frame (1 = every frame); must be &gt;= 1
     */
    public CameraXFrameSource(Context context, LifecycleOwner lifecycleOwner,
                              CameraSelector cameraSelector, Size targetResolution, int frameStride) {
        this(context, lifecycleOwner, cameraSelector, targetResolution, frameStride, null);
    }

    /**
     * @param previewSurface where to show the camera preview (e.g. {@code PreviewView.getSurfaceProvider()}),
     *                       or null for analysis only
     */
    public CameraXFrameSource(Context context, LifecycleOwner lifecycleOwner,
                              CameraSelector cameraSelector, Size targetResolution, int frameStride,
                              @Nullable Preview.SurfaceProvider previewSurface) {
        this(context, lifecycleOwner, cameraSelector, targetResolution, frameStride, previewSurface,
                DEFAULT_MAX_DELIVER_FPS);
    }

    /**
     * @param maxDeliverFps deliver at most this many frames per second (camera time), skipping the rest before
     *                      conversion; 0 = no limit
     */
    public CameraXFrameSource(Context context, LifecycleOwner lifecycleOwner,
                              CameraSelector cameraSelector, Size targetResolution, int frameStride,
                              @Nullable Preview.SurfaceProvider previewSurface, int maxDeliverFps) {
        if (context == null || lifecycleOwner == null || cameraSelector == null || targetResolution == null) {
            throw new IllegalArgumentException("context, lifecycleOwner, cameraSelector and targetResolution are required");
        }
        if (frameStride < 1) {
            throw new IllegalArgumentException("frameStride must be >= 1, was " + frameStride);
        }
        if (maxDeliverFps < 0) {
            throw new IllegalArgumentException("maxDeliverFps must be >= 0, was " + maxDeliverFps);
        }
        // 10% early is still on time: camera timestamps jitter, and a strict 1/fps would halve a 30 fps camera to 10.
        this.minDeliverIntervalNs = maxDeliverFps == 0 ? 0L : 900_000_000L / maxDeliverFps;
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
        this.cameraSelector = cameraSelector;
        this.targetResolution = targetResolution;
        this.frameStride = frameStride;
        this.previewSurface = previewSurface;
        this.windowRotation = lifecycleOwner instanceof Activity ? windowRotationOf((Activity) lifecycleOwner) : null;
        this.analysisExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LIVENESS_CAMERAX_ANALYSIS_THREAD");
            t.setDaemon(true);
            return t;
        });
        // Populated properly once the camera is bound and its real characteristics
        // are known; a conservative placeholder is used until then so info() never
        // returns null (matches the Desktop implementations' contract).
        this.deviceInfo = new DeviceInfo("android-camera:unbound", "unknown", "unknown",
                "camerax", "NONE", UNKNOWN_CAPABILITY, UNKNOWN_CAPABILITY, UNKNOWN_CAPABILITY);
    }

    /**
     * Logs (at debug level, once a second) the frames received and delivered, the YUV conversion
     * time and its share of one core. Off by default. Never logs pixels.
     */
    public void setDebugTiming(boolean enabled) {
        this.debugTiming = enabled;
    }

    /**
     * Exposure assist for dim faces (on by default): while the face region (the centre 40% x 50% of the upright
     * frame) stays dark, the camera's exposure compensation is stepped up, and back toward 0 once it is bright; it
     * goes back to 0 on {@link #stop()}. Cameras without exposure compensation are left alone.
     */
    public void setExposureAssist(boolean enabled) {
        exposure.setEnabled(enabled);
    }

    public boolean exposureAssist() {
        return exposure.enabled();
    }

    /** The exposure compensation index the assist has set (0 = the camera's own exposure). */
    public int exposureIndex() {
        return exposure.index();
    }

    /** Mean luma (0..255) of the face region in the last delivered frame; NaN before the first one. */
    public double lastCentreLuma() {
        return lastCentreLuma;
    }

    /**
     * Where the use cases' target rotation comes from (set before {@link #start}); null leaves CameraX's default, the
     * physical display's rotation. The constructor sets it from the lifecycle owner when that is an Activity.
     */
    public void setWindowRotation(@Nullable WindowRotation windowRotation) {
        this.windowRotation = windowRotation;
    }

    /**
     * Gives the bound use cases the window's current rotation, when it changed. A display change calls it; the
     * Activity's {@code onConfigurationChanged} should too, as the window's new rotation may arrive after the display's
     * change. Main thread.
     */
    public void refreshTargetRotation() {
        WindowRotation rotation = windowRotation;
        ImageAnalysis analysis = imageAnalysis;
        if (rotation == null || analysis == null || !running.get()) {
            return;
        }
        int window = rotation.window();
        if (window == analysis.getTargetRotation()) {
            return;
        }
        analysis.setTargetRotation(window);
        Preview preview = boundPreview;
        if (preview != null) {
            preview.setTargetRotation(window);
        }
        Log.i(TAG, "target rotation " + degrees(window) + " (display " + degrees(rotation.display()) + "), changed");
    }

    @Override
    public DeviceInfo info() {
        return deviceInfo;
    }

    @Override
    public void start(Listener listener) {
        if (listener == null || !running.compareAndSet(false, true)) {
            return;
        }
        this.listener = listener;
        this.sequence.set(0L);

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(context);

        providerFuture.addListener(() -> {
            if (!running.get()) {
                return; // stop() was called before the provider finished initializing
            }
            try {
                ProcessCameraProvider provider = providerFuture.get();
                bindUseCases(provider);
            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                running.set(false);
                notifyError(ErrorCode.CAMERA_UNAVAILABLE, safeMessage(e), false);
            } catch (RuntimeException e) {
                // e.g. no camera matching the selector, camera in use by another app
                running.set(false);
                notifyError(ErrorCode.CAMERA_UNAVAILABLE, safeMessage(e), false);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * Binds the use cases to the camera, then follows the window's rotation until {@link #stop()}. Main thread.
     * Package-private: the rotation test runs this bind with a mock provider. Returns the use cases it bound.
     */
    UseCases bindUseCases(ProcessCameraProvider provider) {
        UseCases useCases = buildUseCases();
        ImageAnalysis analysis = useCases.analysis;
        Preview preview = useCases.preview;

        provider.unbindAll();
        Camera camera = preview == null
                ? provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
                : provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis);

        this.cameraProvider = provider;
        this.imageAnalysis = analysis;
        this.boundPreview = preview;
        this.boundCamera = camera;
        this.deviceInfo = buildDeviceInfo(camera, cameraSelector, targetResolution);

        // stop() may have run on another thread while binding was in progress; it
        // would have found no provider to unbind, so release the camera here
        // rather than leaving it bound with nobody listening.
        if (!running.get()) {
            analysis.clearAnalyzer();
            provider.unbindAll();
            this.cameraProvider = null;
            this.imageAnalysis = null;
            this.boundPreview = null;
            this.boundCamera = null;
            return useCases;
        }
        exposure.attach(cameraXExposure(camera));
        followRotation();
        return useCases;
    }

    /** The use cases of one bind. */
    static final class UseCases {
        final ImageAnalysis analysis;
        @Nullable final Preview preview;

        UseCases(ImageAnalysis analysis, @Nullable Preview preview) {
            this.analysis = analysis;
            this.preview = preview;
        }
    }

    /**
     * The analysis and, with a preview surface, the preview, both at the window's rotation (F19: not CameraX's default,
     * the physical display's).
     */
    private UseCases buildUseCases() {
        int aspectRatio = aspectRatioOf(targetResolution);
        ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(new AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        // Sizes are matched in the sensor's (landscape) orientation; frames are rotated upright later.
                        .setResolutionStrategy(new ResolutionStrategy(landscape(targetResolution),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888);
        // Same aspect ratio as the analysis, so the user sees what the engine analyses.
        Preview.Builder previewBuilder = previewSurface == null ? null : new Preview.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(new AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        .build());

        WindowRotation rotation = windowRotation;
        if (rotation != null) {
            int window = rotation.window();
            analysisBuilder.setTargetRotation(window);
            if (previewBuilder != null) {
                previewBuilder.setTargetRotation(window);
            }
            // A mismatch between the two shows here: the frames follow the first.
            Log.i(TAG, "target rotation " + degrees(window) + " (display " + degrees(rotation.display()) + ")");
        } else {
            Log.i(TAG, "target rotation: CameraX default (no window known)");
        }

        ImageAnalysis analysis = analysisBuilder.build();
        analysis.setAnalyzer(analysisExecutor, this::onImageAvailable);
        Preview preview = null;
        if (previewBuilder != null) {
            preview = previewBuilder.build();
            preview.setSurfaceProvider(previewSurface);
        }
        return new UseCases(analysis, preview);
    }

    /** Until {@link #stop()}: each display change re-applies the window's rotation. Main thread, after a bind. */
    private void followRotation() {
        WindowRotation rotation = windowRotation;
        if (rotation == null) {
            return;
        }
        synchronized (rotationLock) {
            // stop() takes the lock after clearing running: either it sees this watch, or this sees it stopped.
            if (!running.get() || stopWatchingRotation != null) {
                return;
            }
            stopWatchingRotation = rotation.watch(this::refreshTargetRotation);
        }
    }

    private static ExposureAssist.Control cameraXExposure(final Camera camera) {
        return new ExposureAssist.Control() {
            @Override
            public boolean supported() {
                return camera.getCameraInfo().getExposureState().isExposureCompensationSupported();
            }

            @Override
            public int min() {
                return camera.getCameraInfo().getExposureState().getExposureCompensationRange().getLower();
            }

            @Override
            public int max() {
                return camera.getCameraInfo().getExposureState().getExposureCompensationRange().getUpper();
            }

            @Override
            public void setIndex(int index) {
                camera.getCameraControl().setExposureCompensationIndex(index); // async; any thread
            }
        };
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        synchronized (rotationLock) {
            if (stopWatchingRotation != null) {
                stopWatchingRotation.run();
                stopWatchingRotation = null;
            }
        }
        ImageAnalysis analysis = imageAnalysis;
        if (analysis != null) {
            analysis.clearAnalyzer();
        }
        exposure.reset(); // back to the camera's own exposure (index 0)
        // Reuse the ProcessCameraProvider obtained in start()/bindUseCases()
        // rather than calling ProcessCameraProvider.getInstance(context).get() again
        // here: that call's Future is resolved on first use and CameraX caches the
        // provider internally, but blocking on .get() a second time is still an
        // avoidable, real anti-pattern when we already hold the exact instance we
        // bound with. unbindAll() itself must run on the main thread; posting keeps
        // stop() itself non-blocking and safe to call from any thread, matching the
        // Desktop FrameSource implementations' contract.
        ProcessCameraProvider provider = cameraProvider;
        if (provider != null) {
            ContextCompat.getMainExecutor(context).execute(provider::unbindAll);
        }
        cameraProvider = null;
        imageAnalysis = null;
        boundPreview = null;
        boundCamera = null;
        listener = null;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * CameraX analyzer callback, invoked on {@link #analysisExecutor}. Converts the
     * YUV_420_888 image to packed RGB888 using the standard BT.601 conversion
     * (the same coefficients {@code ImageQualityMetrics} on the desktop side uses
     * for luma, for consistency), then delivers a {@link Frame}. Package-private for the pacing test.
     */
    void onImageAvailable(@NonNull ImageProxy imageProxy) {
        if (!running.get()) {
            imageProxy.close();
            return;
        }
        long frameIndex = sequence.getAndIncrement();
        boolean timing = debugTiming;
        try {
            if (frameIndex == 0) {
                lastDeliveredNs = NONE; // a new start(): pace from its first frame
                lastRotationDegrees = -1;
            }
            if (frameIndex % frameStride != 0) {
                return; // skipped: no conversion cost
            }
            if (tooSoon(imageProxy.getImageInfo().getTimestamp())) {
                return; // paced: no conversion cost
            }
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            boolean swap = rotation == 90 || rotation == 270;
            int width = swap ? imageProxy.getHeight() : imageProxy.getWidth();
            int height = swap ? imageProxy.getWidth() : imageProxy.getHeight();
            long convertStartNs = System.nanoTime();
            // A new array per frame: Frame keeps the reference, and the engine may hold frames (best frame).
            byte[] rgb = yuv420ToRgb888(imageProxy, rotation);
            long convertNs = System.nanoTime() - convertStartNs;
            // The Y plane was just copied: the face region's mean luma costs a few thousand samples.
            ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
            double luma = centreMeanLuma(yBytes, imageProxy.getWidth(), imageProxy.getHeight(),
                    yPlane.getRowStride(), yPlane.getPixelStride(), rotation);
            lastCentreLuma = luma;
            exposure.onLuma(luma, imageProxy.getImageInfo().getTimestamp());
            if (rotation != lastRotationDegrees) {
                // The first frame, or a new target rotation during the session.
                lastRotationDegrees = rotation;
                observeResolution(width, height);
                Log.i(TAG, "analysis " + imageProxy.getWidth() + "x" + imageProxy.getHeight()
                        + " rotation " + rotation + " -> frames " + width + "x" + height);
            }
            if (timing) {
                windowConverted++;
                windowConvertNs += convertNs;
                windowConvertMaxNs = Math.max(windowConvertMaxNs, convertNs);
            }
            long timestampMs = System.nanoTime() / 1_000_000L;
            deliverFrame(new Frame(rgb, width, height, timestampMs, frameIndex, deviceInfo.deviceId));
        } catch (RuntimeException e) {
            notifyError(ErrorCode.INVALID_FRAME, safeMessage(e), true);
        } finally {
            imageProxy.close(); // must always be closed or CameraX stalls the pipeline
            if (timing) {
                windowReceived++;
                logTimingWindow();
            }
        }
    }

    /** True when a frame comes too soon after the last delivered one; otherwise records it as delivered. */
    private boolean tooSoon(long timestampNs) {
        if (minDeliverIntervalNs == 0L) {
            return false;
        }
        if (lastDeliveredNs != NONE && timestampNs - lastDeliveredNs < minDeliverIntervalNs) {
            return true;
        }
        lastDeliveredNs = timestampNs;
        return false;
    }

    /** Test seam: marks the source running with {@code listener}, without binding a camera. */
    void attachForTest(Listener listener) {
        this.listener = listener;
        this.sequence.set(0L);
        running.set(true);
    }

    private void logTimingWindow() {
        long now = System.nanoTime();
        if (windowStartNs == 0L) {
            windowStartNs = now;
            return;
        }
        long elapsed = now - windowStartNs;
        if (elapsed < TIMING_WINDOW_NS) {
            return;
        }
        double seconds = elapsed / 1e9;
        Log.d(TAG, String.format(Locale.ROOT,
                "camera %.1f fps, delivered %.1f fps, yuv->rgb avg %.2f ms max %.2f ms, %.1f%% of one core, "
                        + "face-region luma %.0f, exposure assist %s index %d",
                windowReceived / seconds, windowConverted / seconds,
                windowConverted == 0 ? 0.0 : windowConvertNs / 1e6 / windowConverted, windowConvertMaxNs / 1e6,
                100.0 * windowConvertNs / elapsed, lastCentreLuma, exposure.enabled() ? "on" : "off",
                exposure.index()));
        windowStartNs = now;
        windowReceived = 0;
        windowConverted = 0;
        windowConvertNs = 0L;
        windowConvertMaxNs = 0L;
    }

    /**
     * Standard YUV_420_888 (planar, with possible row/pixel stride padding) to
     * packed RGB888 conversion using BT.601 coefficients:
     * R = Y + 1.402*(V-128); G = Y - 0.344136*(U-128) - 0.714136*(V-128); B = Y + 1.772*(U-128),
     * in 16.16 fixed point with rounding, rotated clockwise by {@code rotationDegrees}
     * (0/90/180/270) while writing. Each plane is bulk-copied once into a reused array
     * (per-pixel {@code ByteBuffer.get} was the main cost), then indexed with its row and pixel stride.
     * Package-private for the conversion test; call it from the analysis thread only.
     */
    byte[] yuv420ToRgb888(ImageProxy imageProxy, int rotationDegrees) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

        yBytes = copyPlane(planes[0].getBuffer(), yBytes);
        uBytes = copyPlane(planes[1].getBuffer(), uBytes);
        vBytes = copyPlane(planes[2].getBuffer(), vBytes);
        byte[] yPlane = yBytes;
        byte[] uPlane = uBytes;
        byte[] vPlane = vBytes;

        int yRowStride = planes[0].getRowStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        // Output pixel index of input (row, col) = base + row * rowStep + col * colStep.
        int base;
        int rowStep;
        int colStep;
        switch (rotationDegrees) {
            case 90:  base = height - 1;           rowStep = -1;     colStep = height;  break;
            case 180: base = width * height - 1;   rowStep = -width; colStep = -1;      break;
            case 270: base = (width - 1) * height; rowStep = 1;      colStep = -height; break;
            default:  base = 0;                    rowStep = width;  colStep = 1;       break;
        }

        byte[] rgb = new byte[width * height * 3];
        for (int row = 0; row < height; row++) {
            int yRowOffset = row * yRowStride;
            int uRowOffset = (row >> 1) * uRowStride;
            int vRowOffset = (row >> 1) * vRowStride;
            int out = base + row * rowStep;

            for (int col = 0; col < width; col++, out += colStep) {
                int yValue = (yPlane[yRowOffset + col * yPixelStride] & 0xFF) << 16;
                int uValue = (uPlane[uRowOffset + (col >> 1) * uPixelStride] & 0xFF) - 128;
                int vValue = (vPlane[vRowOffset + (col >> 1) * vPixelStride] & 0xFF) - 128;

                int outIndex = out * 3;
                rgb[outIndex] = clampByte((yValue + R_V * vValue + HALF) >> 16);
                rgb[outIndex + 1] = clampByte((yValue - G_U * uValue - G_V * vValue + HALF) >> 16);
                rgb[outIndex + 2] = clampByte((yValue + B_U * uValue + HALF) >> 16);
            }
        }
        return rgb;
    }

    /** Copies the whole plane (from index 0, as the stride arithmetic assumes) into {@code reuse} or a larger array. */
    private static byte[] copyPlane(ByteBuffer plane, byte[] reuse) {
        ByteBuffer buffer = plane.duplicate();
        buffer.rewind();
        int size = buffer.remaining();
        byte[] out = reuse.length >= size ? reuse : new byte[size];
        buffer.get(out, 0, size);
        return out;
    }

    /**
     * Mean Y (0..255) of the face region, the centre 40% x 50% of the UPRIGHT frame, sampled every 4th pixel in both
     * directions. {@code width}/{@code height} are the plane's (unrotated) size; at 90/270 the region is transposed.
     */
    static double centreMeanLuma(byte[] y, int width, int height, int rowStride, int pixelStride,
                                 int rotationDegrees) {
        boolean swap = rotationDegrees == 90 || rotationDegrees == 270;
        double fx = swap ? 0.5 : 0.4;
        double fy = swap ? 0.4 : 0.5;
        int x0 = (int) (width * (1 - fx) / 2);
        int y0 = (int) (height * (1 - fy) / 2);
        long sum = 0;
        int n = 0;
        for (int row = y0; row < height - y0; row += 4) {
            int offset = row * rowStride;
            for (int col = x0; col < width - x0; col += 4) {
                sum += y[offset + col * pixelStride] & 0xFF;
                n++;
            }
        }
        return n == 0 ? Double.NaN : (double) sum / n;
    }

    /**
     * Steps the camera's exposure compensation up while the face region stays dark and back toward 0 once it is
     * bright. Dark: mean luma below {@value #DARK_LUMA} for {@value #DARK_FOR_MS} ms, then one step up at most every
     * {@value #STEP_EVERY_MS} ms, up to the camera's maximum. Bright: above {@value #BRIGHT_LUMA}, one step back toward
     * 0 at most every {@value #STEP_EVERY_MS} ms. Never below 0: the camera's own auto-exposure handles bright scenes.
     * Thread-safe; times are camera timestamps in ns. Package-private for its test.
     */
    static final class ExposureAssist {

        /** The camera side; read once when attached. */
        interface Control {
            boolean supported();

            int min();

            int max();

            void setIndex(int index);
        }

        static final double DARK_LUMA = 70;
        static final double BRIGHT_LUMA = 150;
        static final long DARK_FOR_MS = 1000;
        static final long STEP_EVERY_MS = 500;

        private Control control;
        private int maxIndex;
        private boolean enabled = true;
        private int index;
        private long darkSinceNs = NONE;
        private long lastStepNs = NONE;

        synchronized void setEnabled(boolean on) {
            if (!on) toZero();
            enabled = on;
        }

        synchronized boolean enabled() {
            return enabled;
        }

        synchronized int index() {
            return index;
        }

        /** A newly bound camera: start from 0. A camera without exposure compensation is never touched. */
        synchronized void attach(Control camera) {
            control = null;
            index = 0;
            darkSinceNs = NONE;
            lastStepNs = NONE;
            try {
                if (camera != null && camera.supported() && camera.max() > 0) {
                    control = camera;
                    maxIndex = camera.max();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "exposure state unavailable: " + e.getMessage());
            }
        }

        synchronized void onLuma(double meanLuma, long nowNs) {
            if (!enabled || control == null || Double.isNaN(meanLuma)) {
                return;
            }
            if (meanLuma < DARK_LUMA) {
                if (darkSinceNs == NONE) darkSinceNs = nowNs;
                if (nowNs - darkSinceNs >= DARK_FOR_MS * 1_000_000L && index < maxIndex && canStep(nowNs)) {
                    step(index + 1, nowNs);
                }
            } else {
                darkSinceNs = NONE;
                if (meanLuma > BRIGHT_LUMA && index > 0 && canStep(nowNs)) {
                    step(index - 1, nowNs);
                }
            }
        }

        /** The camera is being released: back to its own exposure, and forget it. */
        synchronized void reset() {
            toZero();
            control = null;
            darkSinceNs = NONE;
            lastStepNs = NONE;
        }

        private void toZero() {
            if (control != null && index != 0) {
                try {
                    control.setIndex(0);
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not reset exposure compensation: " + e.getMessage());
                }
            }
            index = 0;
        }

        private boolean canStep(long nowNs) {
            return lastStepNs == NONE || nowNs - lastStepNs >= STEP_EVERY_MS * 1_000_000L;
        }

        private void step(int to, long nowNs) {
            try {
                control.setIndex(to);
                index = to;
                lastStepNs = nowNs;
            } catch (RuntimeException e) {
                Log.w(TAG, "could not set exposure compensation " + to + ": " + e.getMessage());
            }
        }
    }

    /**
     * The rotation of the window the user and the preview are in, which the use cases are bound with. It can differ from
     * the physical display's: while another app holds a fixed-rotation transform, the display stayed at 90 while the app
     * was portrait (F19). Rotations are {@link Surface#ROTATION_0} to {@link Surface#ROTATION_270}.
     */
    public interface WindowRotation {
        /** The window's rotation. */
        int window();

        /** The physical display's rotation, CameraX's default: logged next to the window's, not used. */
        int display();

        /** Runs {@code onChange} on the main thread after each display change, until the returned Runnable is run. */
        Runnable watch(Runnable onChange);
    }

    /**
     * {@code activity}'s window: its own display's rotation (API 30+), which includes the fixed-rotation adjustments,
     * else its WindowManager's; the display's from the DisplayManager; changes from a DisplayListener.
     */
    public static WindowRotation windowRotationOf(Activity activity) {
        return new ActivityWindowRotation(activity);
    }

    private static final class ActivityWindowRotation implements WindowRotation {
        private final Activity activity;
        private final DisplayManager displays;

        ActivityWindowRotation(Activity activity) {
            this.activity = activity;
            this.displays = (DisplayManager) activity.getApplicationContext().getSystemService(Context.DISPLAY_SERVICE);
        }

        @Override
        public int window() {
            Display display = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    display = activity.getDisplay();
                } catch (UnsupportedOperationException e) {
                    display = null; // not attached to a display yet
                }
            }
            if (display == null) {
                display = activity.getWindowManager().getDefaultDisplay();
            }
            return display.getRotation();
        }

        @Override
        public int display() {
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            return display == null ? window() : display.getRotation();
        }

        @Override
        public Runnable watch(Runnable onChange) {
            if (displays == null) {
                return () -> { };
            }
            DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    onChange.run();
                }
            };
            displays.registerDisplayListener(listener, new Handler(Looper.getMainLooper()));
            return () -> displays.unregisterDisplayListener(listener);
        }
    }

    private static int degrees(int surfaceRotation) {
        return 90 * surfaceRotation;
    }

    private static byte clampByte(int value) {
        return (byte) (value < 0 ? 0 : (value > 255 ? 255 : value));
    }

    private static int aspectRatioOf(Size size) {
        int longSide = Math.max(size.getWidth(), size.getHeight());
        int shortSide = Math.min(size.getWidth(), size.getHeight());
        return Math.abs(longSide * 9 - shortSide * 16) < Math.abs(longSide * 3 - shortSide * 4)
                ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;
    }

    private static Size landscape(Size size) {
        return size.getWidth() >= size.getHeight() ? size : new Size(size.getHeight(), size.getWidth());
    }

    private void deliverFrame(Frame frame) {
        Listener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        try {
            currentListener.onFrame(frame);
        } catch (Throwable listenerFailure) {
            Log.e(TAG, "Listener.onFrame() threw; continuing stream", listenerFailure);
        }
    }

    private void notifyError(ErrorCode code, String detail, boolean recoverable) {
        Listener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        try {
            currentListener.onError(code, detail, recoverable);
        } catch (Throwable listenerFailure) {
            Log.e(TAG, "Listener.onError() threw", listenerFailure);
        }
    }

    /** Replaces the requested resolution with the one CameraX actually chose (after rotation). */
    private void observeResolution(int width, int height) {
        DeviceCapabilities caps = new DeviceCapabilities(
                Collections.singletonList("YUV_420_888"),
                Collections.singletonList(new DeviceCapabilities.Resolution(width, height)),
                Collections.<Integer>emptyList(), DeviceCapabilities.Origin.OBSERVED);
        deviceInfo = deviceInfo.withCapabilities(caps);
    }

    private static DeviceInfo buildDeviceInfo(Camera camera, CameraSelector selector, Size resolution) {
        String facing = selector == CameraSelector.DEFAULT_FRONT_CAMERA ? "front" : "back";
        String deviceId = "android-camera:" + facing;
        // CameraX's CameraInfo does not expose a stable vendor/model string the way
        // MdmBioDevice does for SBI devices - Android's Camera2 CameraCharacteristics
        // would be needed for that level of detail (and for the full list of supported
        // sizes/FPS ranges), which is a further enhancement, not fabricated here.
        DeviceCapabilities requested = new DeviceCapabilities(
                Collections.singletonList("YUV_420_888"),
                Collections.singletonList(new DeviceCapabilities.Resolution(resolution.getWidth(), resolution.getHeight())),
                Collections.<Integer>emptyList(), DeviceCapabilities.Origin.DECLARED);
        return new DeviceInfo(deviceId, "android", "camerax", "camerax", "NONE",
                UNKNOWN_CAPABILITY, UNKNOWN_CAPABILITY, UNKNOWN_CAPABILITY).withCapabilities(requested);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }
}
