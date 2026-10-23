package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-workflow lockout after a session ends in MAX_RETRIES_EXCEEDED with {@code onRepeatedFailure=COOLDOWN}
 * (by default: operator login). In memory only: it resets when the app restarts (see contract proposal P2).
 * Thread-safe.
 */
public final class CooldownTracker {
    private final TimeSource clock;
    private final Map<WorkflowType, Long> until = new EnumMap<WorkflowType, Long>(WorkflowType.class);

    public CooldownTracker(TimeSource clock) {
        this.clock = clock;
    }

    public synchronized void trip(WorkflowType workflow, int cooldownSec) {
        if (cooldownSec <= 0) return;
        until.put(workflow, clock.nowMs() + cooldownSec * 1000L);
    }

    /** Milliseconds of cooldown left for this workflow, 0 if none. */
    public synchronized long remainingMs(WorkflowType workflow) {
        Long end = until.get(workflow);
        if (end == null) return 0;
        long left = end - clock.nowMs();
        if (left <= 0) {
            until.remove(workflow);
            return 0;
        }
        return left;
    }

    public boolean isActive(WorkflowType workflow) {
        return remainingMs(workflow) > 0;
    }
}
