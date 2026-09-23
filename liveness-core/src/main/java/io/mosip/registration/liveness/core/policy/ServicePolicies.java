package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.audit.AuditRecorder;
import io.mosip.registration.liveness.core.util.TimeSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * Service-level policy state shared by the real and the fake service: per-workflow policies resolved lazily from
 * the current config (audited as CONFIG_LOADED), config reload, and the cooldown tracker. Thread-safe.
 */
public final class ServicePolicies {
    private final AuditSink audit;
    private final String engine;
    private final CooldownTracker cooldown;
    private final Map<WorkflowType, PolicyResolver.Resolved> cache =
            new EnumMap<WorkflowType, PolicyResolver.Resolved>(WorkflowType.class);
    private ConfigProvider config;

    public ServicePolicies(ConfigProvider config, AuditSink audit, TimeSource clock, String engine) {
        this.config = config;
        this.audit = audit;
        this.engine = engine;
        this.cooldown = new CooldownTracker(clock);
    }

    /** The live (cached) policy. Callers that hand it out or keep it must copy it. */
    public LivenessPolicy policy(WorkflowType workflow) {
        PolicyResolver.Resolved r;
        synchronized (cache) {
            r = cache.get(workflow);
            if (r != null) return r.policy;
            r = PolicyResolver.resolve(config, workflow);
            cache.put(workflow, r);
        }
        AuditRecorder.configLoaded(audit, workflow, r.policy, r.configError, engine);
        return r.policy;
    }

    /** A defensive copy, for {@code LivenessService.policy()} and session snapshots. */
    public LivenessPolicy snapshot(WorkflowType workflow) {
        return PolicyResolver.copy(policy(workflow));
    }

    public void reload(ConfigProvider newConfig) {
        synchronized (cache) {
            config = newConfig;
            cache.clear();
        }
    }

    public ConfigProvider config() {
        synchronized (cache) {
            return config;
        }
    }

    public CooldownTracker cooldown() {
        return cooldown;
    }
}
