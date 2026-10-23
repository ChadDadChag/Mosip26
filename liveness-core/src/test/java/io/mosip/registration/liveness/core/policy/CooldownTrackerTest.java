package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.util.ManualTimeSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CooldownTrackerTest {

    @Test
    void blocksOnlyTheTrippedWorkflowUntilTheCooldownPasses() {
        ManualTimeSource clock = new ManualTimeSource(1000);
        CooldownTracker t = new CooldownTracker(clock);
        assertThat(t.isActive(WorkflowType.OPERATOR)).isFalse();

        t.trip(WorkflowType.OPERATOR, 60);
        assertThat(t.isActive(WorkflowType.OPERATOR)).isTrue();
        assertThat(t.isActive(WorkflowType.SUPERVISOR)).isFalse();
        assertThat(t.remainingMs(WorkflowType.OPERATOR)).isEqualTo(60000);

        clock.advance(59999);
        assertThat(t.remainingMs(WorkflowType.OPERATOR)).isEqualTo(1);
        clock.advance(1);
        assertThat(t.isActive(WorkflowType.OPERATOR)).isFalse();
    }

    @Test
    void zeroCooldownNeverBlocks() {
        CooldownTracker t = new CooldownTracker(new ManualTimeSource(0));
        t.trip(WorkflowType.OPERATOR, 0);
        assertThat(t.isActive(WorkflowType.OPERATOR)).isFalse();
    }
}
