package io.mosip.registration.liveness.core.statusaudit;

import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only listener: events in order, onComplete count, the callback thread names. */
public final class RecordingLivenessListener implements LivenessListener {
    public final List<LivenessEvent> events = Collections.synchronizedList(new ArrayList<LivenessEvent>());
    public final List<Object> timeline = Collections.synchronizedList(new ArrayList<Object>());
    public final AtomicInteger completions = new AtomicInteger();
    public final CountDownLatch done = new CountDownLatch(1);
    public final CountDownLatch firstPrompt = new CountDownLatch(1);
    public volatile LivenessResult result;
    public volatile String lastThread;

    @Override
    public void onEvent(LivenessEvent e) {
        lastThread = Thread.currentThread().getName();
        events.add(e);
        timeline.add(e);
        if (e.type == LivenessEvent.Type.CHALLENGE_PROMPT) firstPrompt.countDown();
    }

    @Override
    public void onComplete(LivenessResult r) {
        result = r;
        timeline.add(r);
        completions.incrementAndGet();
        done.countDown();
    }

    public LivenessResult await(long seconds) throws InterruptedException {
        if (!done.await(seconds, TimeUnit.SECONDS)) throw new AssertionError("no onComplete; events=" + events);
        return result;
    }

    public LivenessEvent last(LivenessEvent.Type t) {
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).type == t) return events.get(i);
        }
        return null;
    }
}
