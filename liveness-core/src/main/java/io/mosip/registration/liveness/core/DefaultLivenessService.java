package io.mosip.registration.liveness.core;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.metrics.LivenessMetrics;
import io.mosip.registration.liveness.core.policy.ServicePolicies;
import io.mosip.registration.liveness.core.session.CooldownRefusedSession;
import io.mosip.registration.liveness.core.session.DefaultLivenessSession;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;
import io.mosip.registration.liveness.core.session.SessionFactory;
import io.mosip.registration.liveness.core.util.RandomSource;
import io.mosip.registration.liveness.core.util.SecureRandomSource;
import io.mosip.registration.liveness.core.util.SystemTimeSource;
import io.mosip.registration.liveness.core.util.TimeSource;

/**
 * The real liveness engine. A drop-in replacement for {@code FakeLivenessService}: apps change only construction.
 * <pre>
 * LivenessService liveness = DefaultLivenessService.builder()
 *         .config(configProvider).analyzerFactory(part2Factory).auditSink(auditSink).build();
 * </pre>
 * Fully offline: no network access anywhere in the engine.
 */
public final class DefaultLivenessService implements LivenessService {

    private final ServicePolicies policies;
    private final FaceAnalyzerFactory analyzerFactory;
    private final AuditSink audit;
    private final EngineTuning tuning;
    private final TimeSource clock;
    private final RandomSource random;
    private final LivenessMetrics metrics = new LivenessMetrics();

    private DefaultLivenessService(Builder b) {
        this.analyzerFactory = b.analyzerFactory;
        this.audit = b.audit;
        this.tuning = b.tuning != null ? b.tuning : EngineTuning.defaults();
        this.clock = b.clock != null ? b.clock : SystemTimeSource.INSTANCE;
        this.random = b.random != null ? b.random : new SecureRandomSource();
        this.policies = new ServicePolicies(b.config, audit, clock, "core");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public LivenessSession newSession(WorkflowType workflow) {
        LivenessPolicy p = policies.snapshot(workflow);
        if (!p.enabled) return null;
        long cooldownLeft = policies.cooldown().remainingMs(workflow);
        if (cooldownLeft > 0) {
            metrics.cooldownRefused();
            return new CooldownRefusedSession(workflow, cooldownLeft, audit, null, "core");
        }
        return SessionFactory.create(workflow, p, tuning, analyzerFactory, audit, clock, random,
                policies.cooldown(), metrics, true);
    }

    /**
     * Anonymised operational metrics since this service was created: pass/failure/retry rates, average analysis
     * time and challenge completion time (counts and averages only). Apps may log {@code metrics().snapshot().toMap()}.
     */
    public LivenessMetrics metrics() {
        return metrics;
    }

    @Override
    public LivenessPolicy policy(WorkflowType workflow) {
        return policies.snapshot(workflow);
    }

    @Override
    public void reloadConfig(ConfigProvider config) {
        policies.reload(config);
    }

    public static final class Builder {
        private ConfigProvider config;
        private FaceAnalyzerFactory analyzerFactory;
        private AuditSink audit;
        private EngineTuning tuning;
        private TimeSource clock;
        private RandomSource random;

        private Builder() { }

        /** Host config (Desktop ApplicationContext / Android synced config). Missing keys use the bundled defaults. */
        public Builder config(ConfigProvider config) { this.config = config; return this; }

        /** Part 2's analyzer factory: one analyzer per session. Required. */
        public Builder analyzerFactory(FaceAnalyzerFactory factory) { this.analyzerFactory = factory; return this; }

        /** Host audit adapter. Optional (no audit if absent). */
        public Builder auditSink(AuditSink audit) { this.audit = audit; return this; }

        /** Engine thresholds not in the contract policy (tuning tools only). */
        public Builder tuning(EngineTuning tuning) { this.tuning = tuning; return this; }

        /** Tests only. */
        public Builder clock(TimeSource clock) { this.clock = clock; return this; }

        /** Tests only: a seeded source makes challenge selection predictable. Never in production. */
        public Builder random(RandomSource random) { this.random = random; return this; }

        public DefaultLivenessService build() {
            if (analyzerFactory == null) throw new IllegalStateException("analyzerFactory is required");
            return new DefaultLivenessService(this);
        }
    }
}
