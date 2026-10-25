package io.mosip.registration.liveness.device.android;

import android.content.Context;
import android.util.Log;
import android.util.Size;

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
 * <p><b>Orientation:</b> each frame is rotated by CameraX's
 * {@code getRotationDegrees()} so faces arrive upright, matching the Desktop sources.
 * Frames are not mirrored (the front-camera preview may be; the analysis frames are not).</p>
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
    private volatile Camera boundCamera;
    private volatile DeviceInfo deviceInfo;
    private volatile ProcessCameraProvider cameraProvider;
    private volatile boolean debugTiming;

    // Analysis-thread only: the pacing state, plane copies reused across frames (the RGB output is not, see
    // onImageAvailable) and the debug timing window.
    private long lastDeliveredNs = NONE;
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
     * @param lifecycleOwner   the Activity/Fragment CameraX binds the camera's lifecycle to
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

    private void bindUseCases(ProcessCameraProvider provider) {
        int aspectRatio = aspectRatioOf(targetResolution);
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(new AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        // Sizes are matched in the sensor's (landscape) orientation; frames are rotated upright later.
                        .setResolutionStrategy(new ResolutionStrategy(landscape(targetResolution),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        analysis.setAnalyzer(analysisExecutor, this::onImageAvailable);

        Preview preview = null;
        if (previewSurface != null) {
            // Same aspect ratio as the analysis, so the user sees what the engine analyses.
            preview = new Preview.Builder()
                    .setResolutionSelector(new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(new AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                            .build())
                    .build();
            preview.setSurfaceProvider(previewSurface);
        }

        provider.unbindAll();
        Camera camera = preview == null
                ? provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
                : provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis);

        this.cameraProvider = provider;
        this.imageAnalysis = analysis;
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
            this.boundCamera = null;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ImageAnalysis analysis = imageAnalysis;
        if (analysis != null) {
            analysis.clearAnalyzer();
        }
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
            if (frameIndex == 0) {
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
                "camera %.1f fps, delivered %.1f fps, yuv->rgb avg %.2f ms max %.2f ms, %.1f%% of one core",
                windowReceived / seconds, windowConverted / seconds,
                windowConverted == 0 ? 0.0 : windowConvertNs / 1e6 / windowConverted, windowConvertMaxNs / 1e6,
                100.0 * windowConvertNs / elapsed));
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
