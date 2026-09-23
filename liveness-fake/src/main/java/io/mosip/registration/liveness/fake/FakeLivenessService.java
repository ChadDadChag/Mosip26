package io.mosip.registration.liveness.fake;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.policy.CooldownTracker;
import io.mosip.registration.liveness.core.policy.PolicyResolver;
import io.mosip.registration.liveness.core.session.CooldownRefusedSession;
import io.mosip.registration.liveness.core.util.RandomSource;
import io.mosip.registration.liveness.core.util.SecureRandomSource;
import io.mosip.registration.liveness.core.util.SystemTimeSource;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * Drop-in {@link LivenessService} for UI development: real policy (per workflow, from config), real
 * retry/cancel/cooldown semantics, real threading, scripted decisions. Swap for {@code DefaultLivenessService}
 * by changing construction only.
 *
 * <p>Scenario selection, first match wins: {@link #setScenario} / {@link Builder#scenario}, the system property
 * {@value FakeScenario#SYSTEM_PROPERTY}, the config key {@value FakeScenario#CONFIG_KEY}, then
 * {@link FakeScenario#PASS_ACTIVE}. It is resolved for every new session, so it can be switched while the app runs.
 *
 * <p>Pacing: {@code timeScale} 1.0 = realistic timing (default; system property {@code liveness.fake.timeScale}),
 * 0 = instant (tests), 0.25 = four times faster.
 */
public final class FakeLivenessService implements LivenessService {

    public static final String MODEL_VERSION = "fake-1.0";
    public static final String TIME_SCALE_PROPERTY = "liveness.fake.timeScale";

    private final double timeScale;
    private final AuditSink audit;
    private final RandomSource random;
    private final TimeSource clock;
    private final CooldownTracker cooldown;

    private volatile ConfigProvider config;
    private volatile FakeScenario scenarioOverride;
    private final Map<WorkflowType, PolicyResolver.Resolved> policies =
            new EnumMap<WorkflowType, PolicyResolver.Resolved>(WorkflowType.class);

    public FakeLivenessService(ConfigProvider config) {
        this(builder(config));
    }

    private FakeLivenessService(Builder b) {
        this.config = b.config;
        this.scenarioOverride = b.scenario;
        this.timeScale = b.timeScale != null ? b.timeScale : timeScaleFromSystemProperty();
        this.audit = b.audit;
        this.random = b.random != null ? b.random : new SecureRandomSource();
        this.clock = b.clock != null ? b.clock : SystemTimeSource.INSTANCE;
        this.cooldown = new CooldownTracker(clock);
    }

    public static Builder builder(ConfigProvider config) {
        return new Builder(config);
    }

    @Override
    public LivenessSession newSession(WorkflowType workflow) {
        LivenessPolicy p = resolved(workflow).policy;
        if (!p.enabled) return null;
        long cooldownLeft = cooldown.remainingMs(workflow);
        if (cooldownLeft > 0) return new CooldownRefusedSession(workflow, cooldownLeft, audit, MODEL_VERSION, "fake");
        return new FakeLivenessSession(workflow, PolicyResolver.copy(p), effectiveScenario(), timeScale,
                random, clock, audit, cooldown);
    }

    @Override
    public LivenessPolicy policy(WorkflowType workflow) {
        return PolicyResolver.copy(resolved(workflow).policy);
    }

    @Override
    public void reloadConfig(ConfigProvider config) {
        synchronized (policies) {
            this.config = config;
            policies.clear();
        }
    }

    /** Switch the scripted scenario for sessions created from now on; null = use property/config/default. */
    public void setScenario(FakeScenario scenario) {
        this.scenarioOverride = scenario;
    }

    /** The scenario the next session will use (RANDOM is resolved per session). */
    public FakeScenario configuredScenario() {
        FakeScenario s = scenarioOverride;
        if (s == null) s = FakeScenario.parse(System.getProperty(FakeScenario.SYSTEM_PROPERTY));
        if (s == null && config != null) s = FakeScenario.parse(config.get(FakeScenario.CONFIG_KEY));
        return s != null ? s : FakeScenario.PASS_ACTIVE;
    }

    private FakeScenario effectiveScenario() {
        FakeScenario s = configuredScenario();
        if (s != FakeScenario.RANDOM) return s;
        FakeScenario[] all = FakeScenario.values();
        return all[random.nextInt(all.length - 1)]; // RANDOM is last; never picks itself
    }

    private PolicyResolver.Resolved resolved(WorkflowType workflow) {
        PolicyResolver.Resolved r;
        synchronized (policies) {
            r = policies.get(workflow);
            if (r != null) return r;
            r = PolicyResolver.resolve(config, workflow);
            policies.put(workflow, r);
        }
        AuditRecorder.configLoaded(audit, workflow, r.policy, r.configError, "fake");
        return r;
    }

    private static double timeScaleFromSystemProperty() {
        String v = System.getProperty(TIME_SCALE_PROPERTY);
        if (v == null) return 1.0;
        try {
            double d = Double.parseDouble(v.trim());
            return d >= 0 ? d : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    public static final class Builder {
        private final ConfigProvider config;
        private FakeScenario scenario;
        private Double timeScale;
        private AuditSink audit;
        private RandomSource random;
        private TimeSource clock;

        private Builder(ConfigProvider config) {
            this.config = config;
        }

        public Builder scenario(FakeScenario scenario) { this.scenario = scenario; return this; }

        public Builder timeScale(double timeScale) {
            if (timeScale < 0) throw new IllegalArgumentException("timeScale must be >= 0");
            this.timeScale = timeScale;
            return this;
        }

        public Builder auditSink(AuditSink audit) { this.audit = audit; return this; }

        /** Tests only: deterministic challenge selection and jitter. */
        public Builder random(RandomSource random) { this.random = random; return this; }

        /** Tests only: controls cooldown expiry and reported durations. */
        public Builder clock(TimeSource clock) { this.clock = clock; return this; }

        public FakeLivenessService build() { return new FakeLivenessService(this); }
    }
}
