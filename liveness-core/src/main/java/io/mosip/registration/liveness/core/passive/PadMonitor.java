package io.mosip.registration.liveness.core.passive;

import io.mosip.registration.liveness.api.FrameAnalysis;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Continuous PAD check while challenges run and during the final hold (problem statement §4: "re-evaluate liveness
 * during active verification"). A short rolling median of the liveness score below {@code padRejectThreshold}
 * means PAD_FAILURE: performing the action does not help an attack. The median (not a single frame) keeps one
 * noisy frame from failing a genuine user; frames without a score are skipped.
 */
public final class PadMonitor {
    private final float padRejectThreshold;
    private final int frames;
    private final Deque<Float> recent = new ArrayDeque<Float>();

    public PadMonitor(float padRejectThreshold, int frames) {
        this.padRejectThreshold = padRejectThreshold;
        this.frames = Math.max(1, frames);
    }

    public void reset() {
        recent.clear();
    }

    /** @return true when the rolling median has dropped below the PAD reject threshold. */
    public boolean attack(FrameAnalysis a) {
        if (a.faceCount != 1 || Float.isNaN(a.livenessScore)) return false;
        recent.addLast(a.livenessScore);
        while (recent.size() > frames) recent.removeFirst();
        return recent.size() == frames && Stats.median(recent) < padRejectThreshold;
    }

    public float median() {
        return Stats.median(recent);
    }
}
