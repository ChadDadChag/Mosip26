package io.mosip.registration.liveness.api;

/**
 * Common face-capture device abstraction (adapter pattern). Owner: Person 1.
 * Implementations: SbiStreamFrameSource (Desktop, SBI /stream MJPEG), MockFrameSource
 * (webcam / video file / image folder), CameraXFrameSource (Android).
 *
 * <p>Lifecycle: {@code start()} after {@code stop()} begins a fresh session (sequence
 * restarts at 0). A stopped session never delivers to its listener again, even if its
 * worker is still unwinding a blocking device call.</p>
 */
public interface FrameSource {

    interface Listener {
        /** Called on the source's own thread. Must return quickly; engine drops frames when busy. */
        void onFrame(Frame frame);
        /** Device problems. After a non-recoverable error no more frames are delivered. */
        void onError(ErrorCode code, String detailForAuditOnly, boolean recoverable);
    }

    /** Capability discovery. Valid before start(); sources that observe the stream refine it while running. */
    DeviceInfo info();
    void start(Listener listener);     // non-blocking; throws nothing, errors go to Listener.onError
    void stop();                       // idempotent; releases device/stream
    boolean isRunning();

    final class DeviceInfo {
        public final String deviceId, vendor, model, sbiVersion, certificationLevel; // "L0" | "L1" | "NONE"
        /** Largest known resolution and frame rate; 0 = unknown. Details are in {@link #capabilities}. */
        public final int maxWidth, maxHeight, maxFps;
        public final DeviceCapabilities capabilities;

        public DeviceInfo(String deviceId, String vendor, String model, String sbiVersion,
                          String certificationLevel, int maxWidth, int maxHeight, int maxFps) {
            this(deviceId, vendor, model, sbiVersion, certificationLevel, maxWidth, maxHeight, maxFps,
                    DeviceCapabilities.unknown());
        }

        public DeviceInfo(String deviceId, String vendor, String model, String sbiVersion,
                          String certificationLevel, int maxWidth, int maxHeight, int maxFps,
                          DeviceCapabilities capabilities) {
            this.deviceId = deviceId; this.vendor = vendor; this.model = model; this.sbiVersion = sbiVersion;
            this.certificationLevel = certificationLevel;
            this.maxWidth = maxWidth; this.maxHeight = maxHeight; this.maxFps = maxFps;
            this.capabilities = capabilities == null ? DeviceCapabilities.unknown() : capabilities;
        }

        /** Same identity, new capabilities; max* fields are derived from them where known. */
        public DeviceInfo withCapabilities(DeviceCapabilities caps) {
            DeviceCapabilities.Resolution largest = caps.largestResolution();
            int fps = caps.maxFrameRate();
            return new DeviceInfo(deviceId, vendor, model, sbiVersion, certificationLevel,
                    largest == null ? maxWidth : largest.width,
                    largest == null ? maxHeight : largest.height,
                    fps == 0 ? maxFps : fps, caps);
        }

        public DeviceInfo withCertificationLevel(String level) {
            return new DeviceInfo(deviceId, vendor, model, sbiVersion, level,
                    maxWidth, maxHeight, maxFps, capabilities);
        }
    }
}
