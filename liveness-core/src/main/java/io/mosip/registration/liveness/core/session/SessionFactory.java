package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.EngineTuning;
import io.mosip.registration.liveness.core.metrics.LivenessMetrics;
import io.mosip.registration.liveness.core.policy.CooldownTracker;
import io.mosip.registration.liveness.core.util.RandomSource;
import io.mosip.registration.liveness.core.util.TimeSource;

/** Creates sessions; keeps the session constructor package-private. */
public final class SessionFactory {
    private SessionFactory() { }

    public static DefaultLivenessSession create(WorkflowType workflow, LivenessPolicy policy, EngineTuning tuning,
                                                FaceAnalyzerFactory analyzerFactory, AuditSink audit,
                                                TimeSource clock, RandomSource random, CooldownTracker cooldown,
                                                boolean ownThread) {
        return create(workflow, policy, tuning, analyzerFactory, audit, clock, random, cooldown, null, ownThread);
    }

    public static DefaultLivenessSession create(WorkflowType workflow, LivenessPolicy policy, EngineTuning tuning,
                                                FaceAnalyzerFactory analyzerFactory, AuditSink audit,
                                                TimeSource clock, RandomSource random, CooldownTracker cooldown,
                                                LivenessMetrics metrics, boolean ownThread) {
        DefaultLivenessSession s = new DefaultLivenessSession(workflow, policy, tuning, analyzerFactory, audit, clock,
                random, cooldown, ownThread);
        if (metrics != null) s.setMetrics(metrics);
        return s;
    }
}
