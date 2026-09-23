package io.mosip.registration.liveness.api;

/**
 * Common face-capture device abstraction (adapter pattern). Owner: Person 1.
 * Implementations: SbiStreamFrameSource (Desktop, SBI /stream MJPEG), MockFrameSource
 * (webcam / video file / image folder), AndroidCameraFrameSource or AndroidSbiFrameSource (Person 5).
 */
public interface FrameSource {

    interface Listener {
        /** Called on the source's own thread. Must return quickly; engine drops frames when busy. */
        void onFrame(Frame frame);
        /** Device problems. After a non-recoverable error no more frames are delivered. */
        void onError(ErrorCode code, String detailForAuditOnly, boolean recoverable);
    }

    DeviceInfo info();                 // capability discovery; valid before start()
    void start(Listener listener);     // non-blocking; throws nothing, errors go to Listener.onError
    void stop();                       // idempotent; releases device/stream
    boolean isRunning();

    final class DeviceInfo {
        public final String deviceId, vendor, model, sbiVersion, certificationLevel; // "L0" | "L1" | "NONE"
        public final int maxWidth, maxHeight, maxFps;
        public DeviceInfo(String deviceId, String vendor, String model, String sbiVersion,
                          String certificationLevel, int maxWidth, int maxHeight, int maxFps) {
            this.deviceId = deviceId; this.vendor = vendor; this.model = model; this.sbiVersion = sbiVersion;
            this.certificationLevel = certificationLevel;
            this.maxWidth = maxWidth; this.maxHeight = maxHeight; this.maxFps = maxFps;
        }
    }
}
