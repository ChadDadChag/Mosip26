package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.liveness.api.DeviceCapabilities;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.device.util.JpegToRgb888Converter;
import io.mosip.registration.liveness.device.util.MjpegFrameReader;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.BioService;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desktop FrameSource adapter for an existing MOSIP SBI /stream endpoint.
 *
 * <p>The existing Registration Client remains responsible for MDS discovery and
 * the SBI HTTP request. This class adapts the resulting InputStream into the
 * platform-neutral liveness-api Frame contract.</p>
 *
 * <p><b>Error handling has two distinct layers</b>, deliberately not conflated:</p>
 * <ul>
 *   <li><b>Per-frame errors</b> (a single frame's JPEG payload fails to decode, or
 *       fails {@link Frame}'s own validation) do not end the session. The MJPEG
 *       Content-Length framing is self-synchronizing across frame boundaries: if
 *       the header parsed correctly, the reader consumed exactly the right number
 *       of bytes even when those bytes are not a valid JPEG, so the stream position
 *       is still correct for the next frame's header. These are reported via
 *       {@code onError(INVALID_FRAME, ..., recoverable=true)} and the loop simply
 *       continues to the next frame.</li>
 *   <li><b>Stream-level errors</b> (the underlying connection drops, stalls past
 *       {@code frameTimeoutMs}, or the MJPEG framing itself is unparseable) trigger
 *       an automatic reconnect: a fresh call to {@code BioService.getStream()}, up
 *       to {@code maxReconnectAttempts} times with a fixed backoff, before finally
 *       reporting failure. Reconnection is only attempted after this session has
 *       delivered at least one frame - the very first connection attempt failing is
 *       reported immediately as a connection failure, matching the existing
 *       Registration Client's "device unavailable"/"connection failed" semantics.
 *       An incomplete frame caused by the stream ending mid-payload is this kind of
 *       error.</li>
 * </ul>
 *
 * <p>Every stream-level failure is reported to the listener exactly once via
 * {@code onError}, with {@code recoverable} reflecting whether this class itself
 * is about to retry automatically ({@code true}) or has given up ({@code false}).</p>
 *
 * <p><b>Sessions.</b> Each {@code start()} creates a {@link Session} that owns all
 * per-run state (listener, stream, watchdog, sequence). {@code stop()} detaches it.
 * A worker still blocked in {@code getStream()} after {@code stop()} can only touch
 * its own, already-stopped session, so a quick stop()/start() (a UI retry) is safe.</p>
 *
 * <p><b>Capabilities.</b> MDS device info has no resolution/FPS fields, so
 * {@link #info()} declares the stream format (MJPEG) up front and fills in
 * resolution and frame rate as OBSERVED once frames arrive.</p>
 */
public class SbiStreamFrameSource implements FrameSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(SbiStreamFrameSource.class);

    static final long DEFAULT_FRAME_TIMEOUT_MS = 15_000L;
    static final int DEFAULT_FRAME_STRIDE = 1;
    static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 3;
    static final long DEFAULT_RECONNECT_BACKOFF_MS = 500L;

    /** Raw frames over which the device frame rate is measured. */
    private static final int FPS_SAMPLE_FRAMES = 15;
    private static final int MAX_OBSERVED_RESOLUTIONS = 8;
    private static final int UNKNOWN_CAPABILITY = 0;
    static final String STREAM_FORMAT = "MJPEG";

    private final BioService bioService;
    private final MdmBioDevice device;
    private final String modality;
    private final long frameTimeoutMs;
    private final int frameStride;
    private final int maxReconnectAttempts;
    private final long reconnectBackoffMs;

    private final Object lifecycleLock = new Object();
    private volatile Session current;

    private final Object capabilityLock = new Object();
    private final List<DeviceCapabilities.Resolution> observedResolutions = new ArrayList<>();
    private int observedFps;
    private volatile DeviceInfo deviceInfo;

    /**
     * Creates an SBI stream source using the existing BioService/device path, with
     * default timeout, no frame skipping (stride 1) and default reconnect policy.
     */
    public SbiStreamFrameSource(BioService bioService, MdmBioDevice device, String modality) {
        this(bioService, device, modality, DEFAULT_FRAME_TIMEOUT_MS);
    }

    /**
     * @param frameTimeoutMs maximum allowed interval without receiving a byte
     *                       from the device stream; must be positive
     */
    public SbiStreamFrameSource(BioService bioService, MdmBioDevice device,
                                String modality, long frameTimeoutMs) {
        this(bioService, device, modality, frameTimeoutMs, DEFAULT_FRAME_STRIDE);
    }

    /**
     * @param frameStride deliver every Nth physical frame (1 = every frame, the
     *                    default; 2 = every other frame, etc.); must be &gt;= 1.
     *                    The first physical frame of a session (raw index 0) is
     *                    always delivered, and the delivered {@link Frame#sequence()}
     *                    is always the raw physical frame index, so skipped frames
     *                    show up as gaps (stride 3 delivers 0, 3, 6, 9, ...).
     */
    public SbiStreamFrameSource(BioService bioService, MdmBioDevice device,
                                String modality, long frameTimeoutMs, int frameStride) {
        this(bioService, device, modality, frameTimeoutMs, frameStride,
                DEFAULT_MAX_RECONNECT_ATTEMPTS, DEFAULT_RECONNECT_BACKOFF_MS);
    }

    /**
     * @param maxReconnectAttempts how many times to retry {@code BioService.getStream()}
     *                             after a stream that had previously delivered at
     *                             least one frame drops; 0 disables automatic
     *                             reconnection entirely
     * @param reconnectBackoffMs   delay between reconnect attempts; must be &gt;= 0
     */
    public SbiStreamFrameSource(BioService bioService, MdmBioDevice device, String modality,
                                long frameTimeoutMs, int frameStride,
                                int maxReconnectAttempts, long reconnectBackoffMs) {
        if (bioService == null) {
            throw new IllegalArgumentException("bioService must not be null");
        }
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (modality == null || modality.trim().isEmpty()) {
            throw new IllegalArgumentException("modality must not be empty");
        }
        if (frameTimeoutMs <= 0) {
            throw new IllegalArgumentException("frameTimeoutMs must be positive");
        }
        if (frameStride < 1) {
            throw new IllegalArgumentException("frameStride must be >= 1, was " + frameStride);
        }
        if (maxReconnectAttempts < 0) {
            throw new IllegalArgumentException("maxReconnectAttempts must be >= 0, was " + maxReconnectAttempts);
        }
        if (reconnectBackoffMs < 0) {
            throw new IllegalArgumentException("reconnectBackoffMs must be >= 0, was " + reconnectBackoffMs);
        }

        this.bioService = bioService;
        this.device = device;
        this.modality = modality;
        this.frameTimeoutMs = frameTimeoutMs;
        this.frameStride = frameStride;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectBackoffMs = reconnectBackoffMs;
        this.deviceInfo = buildDeviceInfo(device);
    }

    @Override
    public DeviceInfo info() {
        return deviceInfo;
    }

    @Override
    public void start(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (lifecycleLock) {
            Session session = current;
            if (session != null && session.running.get()) {
                return;
            }
            session = new Session(listener);
            current = session;
            session.begin();
        }
    }

    @Override
    public void stop() {
        Session session;
        synchronized (lifecycleLock) {
            session = current;
            current = null;
        }
        if (session != null) {
            session.end();
        }
    }

    @Override
    public boolean isRunning() {
        Session session = current;
        return session != null && session.running.get();
    }

    private void observeResolution(int width, int height) {
        synchronized (capabilityLock) {
            DeviceCapabilities.Resolution resolution = new DeviceCapabilities.Resolution(width, height);
            if (!observedResolutions.contains(resolution) && observedResolutions.size() < MAX_OBSERVED_RESOLUTIONS) {
                observedResolutions.add(resolution);
                publishObservedCapabilities();
            }
        }
    }

    private void observeFrameRate(int fps) {
        synchronized (capabilityLock) {
            if (fps > 0 && fps != observedFps) {
                observedFps = fps;
                publishObservedCapabilities();
            }
        }
    }

    private void publishObservedCapabilities() {
        DeviceCapabilities caps = new DeviceCapabilities(
                Collections.singletonList(STREAM_FORMAT), observedResolutions,
                observedFps > 0 ? Collections.singletonList(observedFps) : Collections.<Integer>emptyList(),
                DeviceCapabilities.Origin.OBSERVED);
        deviceInfo = deviceInfo.withCapabilities(caps);
    }

    private enum ConnectionOutcome {
        /** stop() was called, or the connection loop ended normally; nothing more to do. */
        DONE,
        /** A stream-level failure was reported with recoverable=true; the outer loop should retry. */
        RETRY
    }

    /** One start()..stop() run. All mutable per-run state lives here. */
    private final class Session {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean stopping = new AtomicBoolean(false);
        private final AtomicReference<InputStream> activeStream = new AtomicReference<>();
        private final AtomicLong lastByteReadNanos = new AtomicLong();

        private volatile Listener listener;
        private volatile Thread worker;
        private volatile ScheduledExecutorService watchdogExecutor;
        private volatile ScheduledFuture<?> watchdogTask;
        private volatile boolean timedOut;
        private long sequence;              // worker thread only
        private boolean everDeliveredAFrame; // worker thread only
        private int reconnectAttemptsUsed;   // worker thread only
        private long fpsWindowStartNanos;    // worker thread only
        private long fpsWindowStartSequence; // worker thread only
        private boolean fpsMeasured;         // worker thread only

        Session(Listener listener) {
            this.listener = listener;
        }

        void begin() {
            Thread thread = new Thread(this::runWithReconnect, "LIVENESS_SBI_STREAM_THREAD");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }

        void end() {
            // Keep `stopping` set: a close-induced IOException seen by the worker
            // after this point must never be reported as a device failure.
            stopping.set(true);
            running.set(false);
            listener = null;
            cancelWatchdog();
            closeActiveStream();
            Thread thread = worker;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }

        private boolean active() {
            return running.get() && !stopping.get();
        }

        /**
         * Outer reconnect loop. Each iteration is one connection attempt, which
         * notifies the listener exactly once if it fails, with {@code recoverable}
         * reflecting whether this loop is about to retry.
         */
        private void runWithReconnect() {
            try {
                while (active()) {
                    ConnectionOutcome outcome = runSingleConnection();
                    if (outcome != ConnectionOutcome.RETRY || !active()) {
                        return;
                    }
                    reconnectAttemptsUsed++;
                    if (!sleepUnlessStopped(reconnectBackoffMs)) {
                        return;
                    }
                }
            } finally {
                running.set(false);
                cancelWatchdog();
                closeActiveStream();
            }
        }

        private ConnectionOutcome runSingleConnection() {
            InputStream stream = null;
            try {
                stream = bioService.getStream(device, modality);

                // stop() may have run while getStream() was blocking. Don't start
                // consuming a stream nobody wants anymore.
                if (!active()) {
                    return ConnectionOutcome.DONE;
                }
                if (stream == null) {
                    notifyError(ErrorCode.DEVICE_CONNECTION_FAILED, "SBI /stream returned no InputStream", false);
                    return ConnectionOutcome.DONE;
                }

                lastByteReadNanos.set(System.nanoTime());
                ProgressInputStream progressStream = new ProgressInputStream(stream, lastByteReadNanos);
                activeStream.set(progressStream);
                if (!active()) {
                    // end() ran between the check above and publishing the stream.
                    return ConnectionOutcome.DONE;
                }
                startWatchdog(progressStream);
                return runFrameLoop(progressStream);
            } catch (RegBaseCheckedException e) {
                if (!active()) {
                    return ConnectionOutcome.DONE;
                }
                // A device that is unreachable (as opposed to a dropped in-progress
                // stream) is never silently retried. See class Javadoc.
                notifyError(mapRegistrationError(e), safeMessage(e), isRecoverable(e));
                return ConnectionOutcome.DONE;
            } catch (RuntimeException e) {
                if (!active()) {
                    return ConnectionOutcome.DONE;
                }
                LOGGER.error("Unexpected failure establishing liveness SBI stream", e);
                notifyError(ErrorCode.DEVICE_CONNECTION_FAILED, safeMessage(e), false);
                return ConnectionOutcome.DONE;
            } finally {
                cancelWatchdog();
                closeActiveStream();
                closeQuietly(stream);
            }
        }

        /**
         * Reads and delivers frames until stopped or a stream-level failure occurs.
         * Per-frame decode/validation failures are handled inline.
         */
        private ConnectionOutcome runFrameLoop(InputStream stream) {
            while (active()) {
                byte[] jpeg;
                try {
                    jpeg = MjpegFrameReader.readNextFrame(stream);
                } catch (EOFException e) {
                    return reportStreamLevelFailure(ErrorCode.DEVICE_DISCONNECTED, "SBI stream ended", true);
                } catch (IOException e) {
                    boolean dueToTimeout = timedOut;
                    return reportStreamLevelFailure(
                            dueToTimeout ? ErrorCode.DEVICE_CONNECTION_FAILED : ErrorCode.DEVICE_DISCONNECTED,
                            dueToTimeout ? "No data from device for " + frameTimeoutMs + " ms" : safeMessage(e),
                            !dueToTimeout);
                }

                if (!active()) {
                    return ConnectionOutcome.DONE;
                }

                long frameIndex = sequence;
                measureFrameRate(frameIndex);

                if (frameIndex % frameStride == 0) {
                    try {
                        JpegToRgb888Converter.DecodedImage decoded = JpegToRgb888Converter.convert(jpeg);
                        Frame frame = new Frame(decoded.getRgb(), decoded.getWidth(), decoded.getHeight(),
                                System.nanoTime() / 1_000_000L, frameIndex, deviceInfo.deviceId);
                        observeResolution(frame.width(), frame.height());
                        if (active()) {
                            everDeliveredAFrame = true;
                            deliverFrame(frame);
                        }
                    } catch (IOException | RuntimeException e) {
                        // Per-frame failure only: framing for THIS frame parsed
                        // correctly, so the stream position is still valid.
                        notifyError(ErrorCode.INVALID_FRAME,
                                "Frame " + frameIndex + " could not be decoded: " + safeMessage(e), true);
                    }
                }

                sequence = frameIndex + 1;
            }
            return ConnectionOutcome.DONE;
        }

        /** Measures the device rate over raw frames (skipped ones included), once per session. */
        private void measureFrameRate(long frameIndex) {
            if (fpsMeasured) {
                return;
            }
            long now = System.nanoTime();
            if (fpsWindowStartNanos == 0L) {
                fpsWindowStartNanos = now;
                fpsWindowStartSequence = frameIndex;
                return;
            }
            long frames = frameIndex - fpsWindowStartSequence;
            if (frames >= FPS_SAMPLE_FRAMES) {
                double seconds = (now - fpsWindowStartNanos) / 1e9;
                if (seconds > 0) {
                    observeFrameRate((int) Math.round(frames / seconds));
                }
                fpsMeasured = true;
            }
        }

        private ConnectionOutcome reportStreamLevelFailure(ErrorCode code, String detail, boolean recoverableInPrinciple) {
            if (!active()) {
                return ConnectionOutcome.DONE;
            }
            boolean willRetry = recoverableInPrinciple
                    && everDeliveredAFrame
                    && reconnectAttemptsUsed < maxReconnectAttempts;
            if (willRetry) {
                // The frame-rate window spans the gap otherwise.
                fpsWindowStartNanos = 0L;
            }
            notifyError(code, detail, willRetry);
            return willRetry ? ConnectionOutcome.RETRY : ConnectionOutcome.DONE;
        }

        private boolean sleepUnlessStopped(long millis) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadlineNanos) {
                if (!active()) {
                    return false;
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                try {
                    Thread.sleep(Math.max(1L, Math.min(50L, remainingMs)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return active();
        }

        private void startWatchdog(final InputStream stream) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LIVENESS_SBI_STREAM_WATCHDOG");
                t.setDaemon(true);
                return t;
            });
            watchdogExecutor = executor;
            timedOut = false;

            long checkEveryMs = Math.max(100L, Math.min(1000L, frameTimeoutMs / 4L));
            watchdogTask = executor.scheduleAtFixedRate(() -> {
                if (!active()) {
                    return;
                }
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastByteReadNanos.get());
                if (elapsedMs >= frameTimeoutMs) {
                    timedOut = true;
                    closeQuietly(stream); // the worker reports the timeout
                }
            }, checkEveryMs, checkEveryMs, TimeUnit.MILLISECONDS);
        }

        private void cancelWatchdog() {
            ScheduledFuture<?> task = watchdogTask;
            if (task != null) {
                task.cancel(true);
            }
            watchdogTask = null;
            ScheduledExecutorService executor = watchdogExecutor;
            if (executor != null) {
                executor.shutdownNow();
            }
            watchdogExecutor = null;
        }

        private void closeActiveStream() {
            closeQuietly(activeStream.getAndSet(null));
        }

        /** Isolates listener exceptions so an engine bug is not reported as a device error. */
        private void deliverFrame(Frame frame) {
            Listener current = listener;
            if (current == null) {
                return;
            }
            try {
                current.onFrame(frame);
            } catch (Throwable listenerFailure) {
                LOGGER.error("Listener.onFrame() threw; continuing stream", listenerFailure);
            }
        }

        private void notifyError(ErrorCode code, String detail, boolean recoverable) {
            Listener current = listener;
            if (current == null || stopping.get()) {
                return;
            }
            try {
                current.onError(code, detail, recoverable);
            } catch (Throwable listenerFailure) {
                LOGGER.error("Listener.onError() threw", listenerFailure);
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Best effort during shutdown/error cleanup.
            }
        }
    }

    private static ErrorCode mapRegistrationError(RegBaseCheckedException exception) {
        if (RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode().equals(exception.getErrorCode())) {
            return ErrorCode.DEVICE_UNAVAILABLE;
        }
        return ErrorCode.DEVICE_CONNECTION_FAILED;
    }

    private static boolean isRecoverable(RegBaseCheckedException exception) {
        return RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorCode().equals(exception.getErrorCode())
                || RegistrationExceptionConstants.MDS_STREAM_TIMEOUT.getErrorCode().equals(exception.getErrorCode());
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static DeviceInfo buildDeviceInfo(MdmBioDevice device) {
        String deviceId = firstNonBlank(device.getDeviceId(), device.getSerialNumber(), "unknown-device");
        String vendor = firstNonBlank(device.getDeviceMake(), null, "unknown");
        String model = firstNonBlank(device.getDeviceModel(), null, "unknown");
        String sbiVersion = firstNonBlank(device.getSpecVersion(), null, "unknown");
        String certification = normalizeCertification(device.getCertification());

        // MDS device info declares the wire format only; resolution/FPS are observed later.
        DeviceCapabilities declared = new DeviceCapabilities(Collections.singletonList(STREAM_FORMAT),
                null, null, DeviceCapabilities.Origin.DECLARED);
        return new DeviceInfo(deviceId, vendor, model, sbiVersion, certification,
                UNKNOWN_CAPABILITY, UNKNOWN_CAPABILITY, UNKNOWN_CAPABILITY, declared);
    }

    private static String normalizeCertification(String certification) {
        if (certification == null) {
            return "NONE";
        }
        String normalized = certification.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("L1")) {
            return "L1";
        }
        if (normalized.contains("L0")) {
            return "L0";
        }
        return "NONE";
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        if (second != null && !second.trim().isEmpty()) {
            return second;
        }
        return fallback;
    }

    /** Tracks byte-level progress so a watchdog can close a stalled HTTP stream. */
    private static final class ProgressInputStream extends FilterInputStream {
        private final AtomicLong lastReadNanos;

        private ProgressInputStream(InputStream input, AtomicLong lastReadNanos) {
            super(input);
            this.lastReadNanos = lastReadNanos;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                lastReadNanos.set(System.nanoTime());
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                lastReadNanos.set(System.nanoTime());
            }
            return count;
        }
    }
}
