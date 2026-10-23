package io.mosip.registration.liveness.device.util;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets the live preview and the liveness engine share one device.
 *
 * <p>{@link FrameSource#start(Listener)} takes a single listener, and the liveness session owns it. Wrap the real
 * source in this class, give the wrapper to {@code LivenessSession.start()}, and register the screen's preview with
 * {@link #addPreviewListener}: every frame reaches the engine and every preview listener, on the source's thread.
 * Preview listeners must return quickly (hand the frame to the UI thread); an exception in one never reaches the
 * engine or other listeners. Device errors go to the engine only, which reports them as DEVICE_ERROR events.
 */
public final class PreviewFrameSource implements FrameSource {

    /** Receives every frame the device delivers. */
    public interface PreviewListener {
        void onPreviewFrame(Frame frame);
    }

    private final FrameSource delegate;
    private final List<PreviewListener> previews = new CopyOnWriteArrayList<>();

    public PreviewFrameSource(FrameSource delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    public void addPreviewListener(PreviewListener listener) {
        if (listener != null) {
            previews.add(listener);
        }
    }

    public void removePreviewListener(PreviewListener listener) {
        previews.remove(listener);
    }

    @Override
    public DeviceInfo info() {
        return delegate.info();
    }

    @Override
    public void start(final Listener engine) {
        delegate.start(new Listener() {
            @Override
            public void onFrame(Frame frame) {
                for (PreviewListener p : previews) {
                    try {
                        p.onPreviewFrame(frame);
                    } catch (RuntimeException ignored) {
                        // A broken preview must never stop the engine from receiving frames.
                    }
                }
                engine.onFrame(frame);
            }

            @Override
            public void onError(ErrorCode code, String detailForAuditOnly, boolean recoverable) {
                engine.onError(code, detailForAuditOnly, recoverable);
            }
        });
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }
}
