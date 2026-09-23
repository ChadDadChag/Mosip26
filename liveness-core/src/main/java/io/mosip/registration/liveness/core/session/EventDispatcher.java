package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serialised, final-gated delivery of listener callbacks for one session.
 *
 * <p>Threading contract (what apps can rely on):
 * <ul>
 *   <li>All callbacks for a session run on one dedicated daemon thread named after the session, never on the
 *       UI thread or the camera thread. Apps must marshal to their UI thread themselves.</li>
 *   <li>Callbacks are delivered in the order they were emitted.</li>
 *   <li>{@link LivenessListener#onComplete} is delivered exactly once; no event is delivered after it.</li>
 *   <li>Listeners may call {@code retry()}/{@code cancel()} from inside a callback.</li>
 *   <li>An exception thrown by the listener is swallowed so it cannot break the engine.</li>
 * </ul>
 * {@link #emit} and {@link #complete} are safe to call from any thread. The delivery thread exits right after
 * {@code onComplete}; a session that is abandoned without a final result keeps one idle daemon thread, so apps
 * must call {@code cancel()} when leaving the screen (the contract already requires it).
 *
 * <p>Deliberately not an ExecutorService: a ThreadPoolExecutor shut down while another thread is starting its
 * first worker can strand the queued final task, losing onComplete (caught by EventDispatcherTest).
 */
public final class EventDispatcher {

    private final LivenessListener listener;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private final Thread thread;
    /** Set when complete() is accepted: later emits are not even queued. */
    private final AtomicBoolean finalSubmitted = new AtomicBoolean(false);
    /** Set on the delivery thread once onComplete ran: guards events queued in a race with complete(). */
    private volatile boolean finalDelivered;

    public EventDispatcher(LivenessListener listener, String threadName) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        this.listener = listener;
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                deliverLoop();
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /** Queues an event. Dropped silently if the final result was already submitted. */
    public void emit(final LivenessEvent event) {
        if (finalSubmitted.get()) return;
        queue.add(new Runnable() {
            @Override
            public void run() {
                if (finalDelivered) return;
                try {
                    listener.onEvent(event);
                } catch (RuntimeException ignored) {
                    // A broken listener must not break the session.
                }
            }
        });
    }

    /** Queues the final result. Only the first call wins; later calls return false and do nothing. */
    public boolean complete(LivenessResult result) {
        if (!finalSubmitted.compareAndSet(false, true)) return false;
        queue.add(new FinalDelivery(result));
        return true;
    }

    public boolean isCompleted() {
        return finalSubmitted.get();
    }

    /** Test/diagnostic helper: waits until onComplete has been delivered and the delivery thread exited. */
    public boolean awaitDelivered(long timeoutMs) throws InterruptedException {
        thread.join(timeoutMs);
        return !thread.isAlive();
    }

    private void deliverLoop() {
        while (true) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                // Nobody interrupts this private thread; if it happens, keep the exactly-once promise.
                continue;
            }
            task.run();
            if (task instanceof FinalDelivery) {
                queue.clear(); // events that raced with complete() are dropped by contract
                return;
            }
        }
    }

    private final class FinalDelivery implements Runnable {
        private final LivenessResult result;

        FinalDelivery(LivenessResult result) {
            this.result = result;
        }

        @Override
        public void run() {
            finalDelivered = true;
            try {
                listener.onComplete(result);
            } catch (RuntimeException ignored) {
                // A broken listener must not break the session.
            }
        }
    }
}
