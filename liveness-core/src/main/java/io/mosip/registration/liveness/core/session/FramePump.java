package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges the source thread and the session worker through a single "latest frame" slot.
 * <ul>
 *   <li>{@link #onFrame} (source thread) only swaps the slot: O(1), never blocks, never queues. If the analyzer is
 *       slower than the camera, the unanalysed older frame is overwritten and counted as dropped.</li>
 *   <li>{@link #take} (worker) empties the slot, so every frame is analysed at most once.</li>
 *   <li>Device errors are forwarded to the worker's input queue; the listener never sees them directly.</li>
 * </ul>
 */
public final class FramePump implements FrameSource.Listener {

    /** Receives device errors and wake-ups on the source thread; must only enqueue. */
    public interface Sink {
        void frameAvailable();
        void sourceError(ErrorCode code, String detailForAuditOnly, boolean recoverable);
    }

    private final TimeSource clock;
    private final Sink sink;
    private final AtomicReference<Frame> slot = new AtomicReference<Frame>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastArrivalMs = -1;
    private volatile boolean closed;

    public FramePump(TimeSource clock, Sink sink) {
        this.clock = clock;
        this.sink = sink;
    }

    @Override
    public void onFrame(Frame frame) {
        if (closed || frame == null) return;
        lastArrivalMs = clock.nowMs();
        received.incrementAndGet();
        if (slot.getAndSet(frame) != null) dropped.incrementAndGet();
        sink.frameAvailable();
    }

    @Override
    public void onError(ErrorCode code, String detailForAuditOnly, boolean recoverable) {
        if (closed) return;
        sink.sourceError(code, detailForAuditOnly, recoverable);
    }

    /** Newest unanalysed frame, or null. Each frame is returned at most once. */
    public Frame take() {
        return slot.getAndSet(null);
    }

    /** Discards the waiting frame (e.g. while throttled or waiting for Retry) and counts it as dropped. */
    public void discard() {
        if (slot.getAndSet(null) != null) dropped.incrementAndGet();
    }

    public boolean hasFrame() {
        return slot.get() != null;
    }

    public long lastArrivalMs() {
        return lastArrivalMs;
    }

    public long received() {
        return received.get();
    }

    public long dropped() {
        return dropped.get();
    }

    /** After close, late callbacks from the source are ignored. */
    public void close() {
        closed = true;
        slot.set(null);
    }
}
