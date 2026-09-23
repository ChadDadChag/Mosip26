package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.core.EngineTuning;

/**
 * Stream integrity for analysed frames: sequence and timestamp must strictly increase and frames cannot be
 * implausibly close together. Repeated or reordered frames are a replay/injection signal as much as a device
 * glitch. The last-seen values persist across attempts (the stream continues); the violation count is per attempt.
 */
public final class FrameSanity {
    private final EngineTuning tuning;
    private long lastSeq = Long.MIN_VALUE;
    private long lastTs = Long.MIN_VALUE;
    private int violations;

    public FrameSanity(EngineTuning tuning) {
        this.tuning = tuning;
    }

    public void resetAttempt() {
        violations = 0;
    }

    /** Forget the stream (source restarted: sequence numbers may start again). */
    public void resetStream() {
        lastSeq = Long.MIN_VALUE;
        lastTs = Long.MIN_VALUE;
        violations = 0;
    }

    /** @return true if the frame is acceptable; false if it must be dropped (and was counted). */
    public boolean accept(Frame f) {
        boolean ok = f.sequence() > lastSeq && f.timestampMs() > lastTs
                && (lastTs == Long.MIN_VALUE || f.timestampMs() - lastTs >= tuning.minFrameIntervalMs);
        if (!ok) {
            violations++;
            return false;
        }
        lastSeq = f.sequence();
        lastTs = f.timestampMs();
        return true;
    }

    /** Counts a frame the analyzer rejected as invalid. */
    public void countViolation() {
        violations++;
    }

    public boolean limitReached() {
        return violations >= tuning.invalidFrameLimit;
    }

    public int violations() {
        return violations;
    }
}
