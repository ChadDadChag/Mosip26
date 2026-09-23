package io.mosip.registration.liveness.core.detect;

import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.core.testing.Fa;

import java.util.ArrayList;
import java.util.List;

/** Feeds a detector a 15 fps timeline; the prompt is at t = 0. */
final class DetectorHarness {
    static final long FRAME_MS = 67;

    final ActionDetector d;
    long t;
    long seq;
    final List<DetectorStatus> statuses = new ArrayList<DetectorStatus>();

    DetectorHarness(ActionDetector d, Baseline baseline) {
        this.d = d;
        d.reset(baseline, 0);
    }

    static Baseline neutralBaseline() {
        List<FrameAnalysis> frames = new ArrayList<FrameAnalysis>();
        for (int i = 0; i < 5; i++) frames.add(Fa.at(-1000 + i * FRAME_MS, i).build());
        return Baseline.of(frames);
    }

    interface Shape { Fa apply(Fa f); }

    static final Shape NEUTRAL = new Shape() { @Override public Fa apply(Fa f) { return f; } };

    DetectorStatus frame(Shape s) {
        t += FRAME_MS;
        DetectorStatus st = d.update(s.apply(Fa.at(t, ++seq)).build(), t);
        statuses.add(st);
        return st;
    }

    DetectorStatus frames(int n, Shape s) {
        DetectorStatus last = null;
        for (int i = 0; i < n; i++) last = frame(s);
        return last;
    }

    boolean detected() {
        return statuses.contains(DetectorStatus.DETECTED);
    }
}
