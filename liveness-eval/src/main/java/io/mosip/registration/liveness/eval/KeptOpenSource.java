package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

/**
 * Keeps a camera open across takes. TraceRecorder and DatasetRecorder start and stop their source on every take, and
 * a cold webcam open (0.8 to 2.5 s here) ate the first seconds of a take: for traces, the pre-prompt frames the
 * detectors' baseline needs. Here start() and stop() only attach and detach the take's listener, so sequence numbers
 * run on across takes (traces and datasets keep times, not seq).
 */
final class KeptOpenSource implements FrameSource {
    private final FrameSource camera;
    private volatile Listener current;
    private volatile String fatal;

    KeptOpenSource(FrameSource camera) {
        this.camera = camera;
    }

    void open() {
        camera.start(new Listener() {
            @Override public void onFrame(Frame frame) {
                Listener l = current;
                if (l != null) l.onFrame(frame);
            }

            @Override public void onError(ErrorCode code, String detail, boolean recoverable) {
                if (!recoverable) fatal = code + ": " + detail;
                Listener l = current;
                if (l != null) l.onError(code, detail, recoverable);
            }
        });
    }

    void close() {
        current = null;
        camera.stop();
    }

    @Override public DeviceInfo info() { return camera.info(); }

    @Override public void start(Listener listener) {
        current = listener;
        String f = fatal; // a camera that already failed fails the take at once
        if (f != null) listener.onError(ErrorCode.CAMERA_UNAVAILABLE, f, false);
    }

    @Override public void stop() { current = null; }

    @Override public boolean isRunning() { return current != null && camera.isRunning(); }
}
