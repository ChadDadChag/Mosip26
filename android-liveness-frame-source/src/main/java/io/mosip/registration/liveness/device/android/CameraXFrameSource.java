package io.mosip.registration.liveness.device.android;

import android.content.Context;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.Collections;
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
 * <p><b>Build/test status - stated plainly, not glossed over:</b> this file was
 * written and manually reviewed against the CameraX API shape (
 * {@code ImageAnalysis.Analyzer}, {@code ImageProxy}, YUV_420_888 plane layout),
 * but this sandbox has no Android SDK, no Gradle, and no network access to fetch
 * the {@code androidx.camera:*} artifacts, so it has <b>not been compiled and not
 * been runtime-tested</b>, on an emulator or otherwise. It is delivered as a
 * complete, reviewed source file plus the Gradle dependency notes in this module's
 * {@code README.md}, ready for a real Android build environment to compile and
 * test. Do not treat this as verified working code the way the Desktop
 * {@code SbiStreamFrameSource} and {@code MockFrameSource} are (those were
 * actually compiled and exercised with real, executed tests).</p>
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
 * <p><b>Orientation:</b> each frame is rotated by CameraX's
 * {@code getRotationDegrees()} so faces arrive upright, matching the Desktop sources.
 * Frames are not mirrored (the front-camera preview may be; the analysis frames are not).</p>
 *
 * <p><b>Every-Nth-frame:</b> {@code frameStride} skips conversion of unselected frames;
 * {@link Frame#sequence()} stays the raw frame index, so skips show up as gaps,
 * the same as the Desktop sources.</p>
 *
 * <p>Depends only on {@code liveness-api} (pure Java, no Android/Desktop-specific
 * types) plus CameraX - it does not reference any Desktop-side class
 * ({@code SbiStreamFrameSource}, {@code MockFrameSource}, {@code BioService}, etc.),
 * so it cannot affect or break the Desktop Maven build; this module is
 * intentionally not wired into the Desktop reactor's {@code <modules>} list.</p>
 */
public final class CameraXFrameSource implements FrameSource {

    private static final int UNKNOWN_CAPABILITY = 0;

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final CameraSelector cameraSelector;
    private final Size targetResolution;
    private final int frameStride;
    private final ExecutorService analysisExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();

    private volatile Listener listener;
    private volatile ImageAnalysis imageAnalysis;
    private volatile Camera boundCamera;
    private volatile DeviceInfo deviceInfo;
    private volatile ProcessCameraProvider cameraProvider;

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
        if (context == null || lifecycleOwner == null || cameraSelector == null || targetResolution == null) {
            throw new IllegalArgumentException("context, lifecycleOwner, cameraSelector and targetResolution are required");
        }
        if (frameStride < 1) {
            throw new IllegalArgumentException("frameStride must be >= 1, was " + frameStride);
        }
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
        this.cameraSelector = cameraSelector;
        this.targetResolution = targetResolution;
        this.frameStride = frameStride;
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
                bindAnalysisUseCase(provider);
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

    private void bindAnalysisUseCase(ProcessCameraProvider provider) {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(targetResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        analysis.setAnalyzer(analysisExecutor, this::onImageAvailable);

        provider.unbindAll();
        Camera camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis);

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
        // Reuse the ProcessCameraProvider obtained in start()/bindAnalysisUseCase()
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
     * for luma, for consistency), then delivers a {@link Frame}.
     */
    private void onImageAvailable(@NonNull ImageProxy imageProxy) {
        if (!running.get()) {
            imageProxy.close();
            return;
        }
        long frameIndex = sequence.getAndIncrement();
        try {
            if (frameIndex % frameStride != 0) {
                return; // skipped: no conversion cost
            }
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            boolean swap = rotation == 90 || rotation == 270;
            int width = swap ? imageProxy.getHeight() : imageProxy.getWidth();
            int height = swap ? imageProxy.getWidth() : imageProxy.getHeight();
            byte[] rgb = yuv420ToRgb888(imageProxy, rotation);
            if (frameIndex == 0) {
                observeResolution(width, height);
            }
            long timestampMs = System.nanoTime() / 1_000_000L;
            deliverFrame(new Frame(rgb, width, height, timestampMs, frameIndex, deviceInfo.deviceId));
        } catch (RuntimeException e) {
            notifyError(ErrorCode.INVALID_FRAME, safeMessage(e), true);
        } finally {
            imageProxy.close(); // must always be closed or CameraX stalls the pipeline
        }
    }

    /**
     * Standard YUV_420_888 (planar, with possible row/pixel stride padding) to
     * packed RGB888 conversion using BT.601 coefficients:
     * R = Y + 1.402*(V-128); G = Y - 0.344136*(U-128) - 0.714136*(V-128); B = Y + 1.772*(U-128),
     * rotated clockwise by {@code rotationDegrees} (0/90/180/270) while writing.
     */
    private static byte[] yuv420ToRgb888(ImageProxy imageProxy, int rotationDegrees) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] rgb = new byte[width * height * 3];
        int outWidth = (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;

        for (int row = 0; row < height; row++) {
            int yRowOffset = row * yRowStride;
            int uvRow = row / 2;
            int uRowOffset = uvRow * uRowStride;
            int vRowOffset = uvRow * vRowStride;

            for (int col = 0; col < width; col++) {
                int yValue = yBuffer.get(yRowOffset + col) & 0xFF;
                int uCol = (col / 2) * uPixelStride;
                int vCol = (col / 2) * vPixelStride;
                int uValue = (uBuffer.get(uRowOffset + uCol) & 0xFF) - 128;
                int vValue = (vBuffer.get(vRowOffset + vCol) & 0xFF) - 128;

                int r = (int) (yValue + 1.402 * vValue);
                int g = (int) (yValue - 0.344136 * uValue - 0.714136 * vValue);
                int b = (int) (yValue + 1.772 * uValue);

                int outX;
                int outY;
                switch (rotationDegrees) {
                    case 90:  outX = height - 1 - row; outY = col; break;
                    case 180: outX = width - 1 - col;  outY = height - 1 - row; break;
                    case 270: outX = row;              outY = width - 1 - col; break;
                    default:  outX = col;              outY = row; break;
                }
                int outIndex = (outY * outWidth + outX) * 3;
                rgb[outIndex] = (byte) clampByte(r);
                rgb[outIndex + 1] = (byte) clampByte(g);
                rgb[outIndex + 2] = (byte) clampByte(b);
            }
        }
        return rgb;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void deliverFrame(Frame frame) {
        Listener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        try {
            currentListener.onFrame(frame);
        } catch (Throwable listenerFailure) {
            android.util.Log.e("CameraXFrameSource", "Listener.onFrame() threw; continuing stream", listenerFailure);
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
            android.util.Log.e("CameraXFrameSource", "Listener.onError() threw", listenerFailure);
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
