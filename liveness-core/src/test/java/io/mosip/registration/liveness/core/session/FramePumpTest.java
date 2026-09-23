package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.core.testing.Fa;
import io.mosip.registration.liveness.core.util.ManualTimeSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FramePumpTest {

    static final class Sink implements FramePump.Sink {
        final AtomicInteger wakeups = new AtomicInteger();
        final List<ErrorCode> errors = new ArrayList<ErrorCode>();
        @Override public void frameAvailable() { wakeups.incrementAndGet(); }
        @Override public synchronized void sourceError(ErrorCode c, String d, boolean r) { errors.add(c); }
    }

    @Test
    void keepsOnlyTheLatestFrameAndCountsDrops() {
        ManualTimeSource clock = new ManualTimeSource(0);
        Sink sink = new Sink();
        FramePump p = new FramePump(clock, sink);
        assertThat(p.lastArrivalMs()).isEqualTo(-1);
        Frame f1 = Fa.at(1, 1).build().frame, f2 = Fa.at(2, 2).build().frame, f3 = Fa.at(3, 3).build().frame;
        p.onFrame(f1);
        clock.advance(33);
        p.onFrame(f2);
        assertThat(p.take()).isSameAs(f2);
        assertThat(p.take()).isNull();
        p.onFrame(f3);
        p.discard();
        assertThat(p.take()).isNull();
        assertThat(p.received()).isEqualTo(3);
        assertThat(p.dropped()).isEqualTo(2);
        assertThat(p.lastArrivalMs()).isEqualTo(33);
        assertThat(sink.wakeups.get()).isEqualTo(3);
    }

    @Test
    void forwardsErrorsAndIgnoresEverythingAfterClose() {
        Sink sink = new Sink();
        FramePump p = new FramePump(new ManualTimeSource(0), sink);
        p.onError(ErrorCode.DEVICE_DISCONNECTED, "usb", true);
        p.close();
        p.onError(ErrorCode.DEVICE_UNAVAILABLE, "late", false);
        p.onFrame(Fa.at(1, 1).build().frame);
        assertThat(sink.errors).containsExactly(ErrorCode.DEVICE_DISCONNECTED);
        assertThat(p.take()).isNull();
    }

    @Test
    void fastProducerSlowConsumerNeverAnalysesAFrameTwiceAndLosesNothingUnaccounted() throws Exception {
        final FramePump p = new FramePump(new ManualTimeSource(0), new Sink());
        final int n = 20000;
        final List<Frame> frames = new ArrayList<Frame>();
        for (int i = 1; i <= n; i++) frames.add(new Frame(new byte[3], 1, 1, i, i, "t"));
        Thread producer = new Thread(new Runnable() {
            @Override public void run() { for (Frame f : frames) p.onFrame(f); }
        });
        Set<Long> taken = new HashSet<Long>();
        long lastSeq = 0;
        producer.start();
        while (producer.isAlive() || p.received() < n) {
            Frame f = p.take();
            if (f != null) {
                assertThat(taken.add(f.sequence())).as("analysed twice").isTrue();
                assertThat(f.sequence()).as("never older than a frame already taken").isGreaterThan(lastSeq);
                lastSeq = f.sequence();
            }
        }
        producer.join();
        Frame leftover = p.take();
        if (leftover != null) taken.add(leftover.sequence());
        assertThat(taken.size() + p.dropped()).isEqualTo(n);
    }
}
