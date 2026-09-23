package io.mosip.registration.liveness.fake;

import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Records callbacks; optionally presses Retry like a user would, from inside the callback. */
class RecordingListener implements LivenessListener {
    final List<LivenessEvent> events = Collections.synchronizedList(new ArrayList<LivenessEvent>());
    /** Every callback in order; a LivenessResult marks onComplete. */
    final List<Object> timeline = Collections.synchronizedList(new ArrayList<Object>());
    final AtomicInteger completions = new AtomicInteger();
    final CountDownLatch done = new CountDownLatch(1);
    volatile LivenessResult result;
    volatile LivenessSession session;
    volatile boolean autoRetry = true;
    volatile String callbackThread;

    @Override
    public void onEvent(LivenessEvent e) {
        callbackThread = Thread.currentThread().getName();
        events.add(e);
        timeline.add(e);
        if (!autoRetry || session == null) return;
        if (e.type == LivenessEvent.Type.ATTEMPT_FAILED
                || (e.type == LivenessEvent.Type.DEVICE_ERROR && e.recoverable)) {
            session.retry();
        }
    }

    @Override
    public void onComplete(LivenessResult r) {
        result = r;
        timeline.add(r);
        completions.incrementAndGet();
        done.countDown();
    }

    LivenessResult await() throws InterruptedException {
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("no onComplete within 10 s; events=" + types());
        return result;
    }

    List<LivenessEvent.Type> types() {
        List<LivenessEvent.Type> t = new ArrayList<LivenessEvent.Type>();
        synchronized (events) {
            for (LivenessEvent e : events) t.add(e.type);
        }
        return t;
    }

    List<LivenessEvent> ofType(LivenessEvent.Type type) {
        List<LivenessEvent> out = new ArrayList<LivenessEvent>();
        synchronized (events) {
            for (LivenessEvent e : events) if (e.type == type) out.add(e);
        }
        return out;
    }
}
