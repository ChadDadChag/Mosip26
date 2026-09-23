package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class EventDispatcherTest {

    static LivenessResult result(LivenessResult.Decision d) {
        return new LivenessResult(d, LivenessResult.PassPath.NONE, null, null, 0f,
                Collections.<ChallengeType>emptyList(), 1, 0, "s", "m");
    }

    static final class Recorder implements LivenessListener {
        final List<Object> calls = Collections.synchronizedList(new ArrayList<Object>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        @Override public void onEvent(LivenessEvent e) { calls.add(e.type); threads.add(Thread.currentThread().getName()); }
        @Override public void onComplete(LivenessResult r) { calls.add(r.decision); threads.add(Thread.currentThread().getName()); }
    }

    @Test
    void deliversInOrderOnDedicatedThreadAndStopsAfterFinal() throws Exception {
        Recorder rec = new Recorder();
        EventDispatcher d = new EventDispatcher(rec, "liveness-events-test");
        d.emit(LivenessEvent.of(LivenessEvent.Type.STARTED, 1));
        d.emit(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, 1));
        assertThat(d.complete(result(LivenessResult.Decision.PASSED))).isTrue();
        assertThat(d.complete(result(LivenessResult.Decision.CANCELLED))).isFalse();
        d.emit(LivenessEvent.of(LivenessEvent.Type.HOLD_STILL, 1));
        assertThat(d.awaitDelivered(2000)).isTrue();

        assertThat(rec.calls).containsExactly(LivenessEvent.Type.STARTED, LivenessEvent.Type.CHECKING_PASSIVE,
                LivenessResult.Decision.PASSED);
        assertThat(rec.threads).allMatch("liveness-events-test"::equals);
    }

    @Test
    void throwingListenerDoesNotBreakDelivery() throws Exception {
        final List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        EventDispatcher d = new EventDispatcher(new LivenessListener() {
            @Override public void onEvent(LivenessEvent e) { seen.add(e.type); throw new IllegalStateException("boom"); }
            @Override public void onComplete(LivenessResult r) { seen.add(r.decision); }
        }, "t");
        d.emit(LivenessEvent.of(LivenessEvent.Type.STARTED, 1));
        d.complete(result(LivenessResult.Decision.FAILED));
        d.awaitDelivered(2000);
        assertThat(seen).containsExactly(LivenessEvent.Type.STARTED, LivenessResult.Decision.FAILED);
    }

    @Test
    void racingEmittersAndCompletersDeliverExactlyOneFinalAndNothingAfter() throws Exception {
        // Regression: with a ThreadPoolExecutor this lost onComplete in roughly 1 of 50 rounds.
        for (int round = 0; round < 1000; round++) {
            final Recorder rec = new Recorder();
            final EventDispatcher d = new EventDispatcher(rec, "t");
            final CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<Thread>();
            for (int t = 0; t < 4; t++) {
                final boolean completer = t % 2 == 0;
                Thread th = new Thread(new Runnable() {
                    @Override public void run() {
                        try { go.await(); } catch (InterruptedException e) { return; }
                        for (int i = 0; i < 50; i++) {
                            d.emit(LivenessEvent.of(LivenessEvent.Type.CHALLENGE_PROGRESS, 1));
                            if (completer && i == 25) d.complete(result(LivenessResult.Decision.CANCELLED));
                        }
                    }
                });
                th.start();
                threads.add(th);
            }
            go.countDown();
            for (Thread th : threads) th.join();
            assertThat(d.awaitDelivered(2000)).isTrue();

            List<Object> calls = new ArrayList<Object>(rec.calls);
            assertThat(calls.indexOf(LivenessResult.Decision.CANCELLED)).isEqualTo(calls.size() - 1);
            assertThat(Collections.frequency(calls, LivenessResult.Decision.CANCELLED)).isEqualTo(1);
        }
    }
}
