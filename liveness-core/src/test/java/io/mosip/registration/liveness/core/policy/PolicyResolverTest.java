package io.mosip.registration.liveness.core.policy;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.WorkflowType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyResolverTest {

    private static final String P = LivenessPolicy.PREFIX;

    @Test
    void bundledFileSuppliesPerWorkflowOverridesWhenHostConfigIsEmpty() {
        assertThat(BundledDefaults.get().isLoaded()).isTrue();
        MapConfig empty = new MapConfig();

        LivenessPolicy resident = PolicyResolver.resolve(empty, WorkflowType.RESIDENT).policy;
        LivenessPolicy operator = PolicyResolver.resolve(empty, WorkflowType.OPERATOR).policy;
        LivenessPolicy supervisor = PolicyResolver.resolve(empty, WorkflowType.SUPERVISOR).policy;

        assertThat(resident.minChallenges).isEqualTo(2);
        assertThat(operator.minChallenges).isEqualTo(1);
        assertThat(supervisor.minChallenges).isEqualTo(1);
        assertThat(operator.onRepeatedFailure).isEqualTo(LivenessPolicy.OnRepeatedFailure.COOLDOWN);
        assertThat(resident.onRepeatedFailure).isEqualTo(LivenessPolicy.OnRepeatedFailure.BLOCK);
        assertThat(resident.passiveThreshold).isEqualTo(0.85f);
        assertThat(resident.padRejectThreshold).isEqualTo(0.30f);
        assertThat(resident.challengeTimeoutMs).isEqualTo(6000);
        assertThat(resident.maxRetries).isEqualTo(3);
        assertThat(resident.challengePool).containsExactly(ChallengeType.BLINK, ChallengeType.SMILE,
                ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);
    }

    @Test
    void precedenceMatchesHavingMergedTheBundledFileIntoHostConfig() {
        MapConfig host = new MapConfig()
                .put(P + "default.maxRetries", "5")
                .put(P + "operator.maxRetries", "1")
                .put(P + "default.minChallenges", "3");

        assertThat(PolicyResolver.resolve(host, WorkflowType.OPERATOR).policy.maxRetries).isEqualTo(1);
        assertThat(PolicyResolver.resolve(host, WorkflowType.RESIDENT).policy.maxRetries).isEqualTo(5);
        // Per field, the contract checks the workflow key before the default key, across all layers. So a
        // bundled workflow key (operator.minChallenges=1) beats a host default key, exactly as if the app had
        // merged liveness-default.properties into its config as the file instructs.
        assertThat(PolicyResolver.resolve(host, WorkflowType.OPERATOR).policy.minChallenges).isEqualTo(1);
        assertThat(PolicyResolver.resolve(host, WorkflowType.RESIDENT).policy.minChallenges).isEqualTo(3);
        host.put(P + "operator.minChallenges", "2");
        assertThat(PolicyResolver.resolve(host, WorkflowType.OPERATOR).policy.minChallenges).isEqualTo(2);
    }

    @Test
    void invalidHostConfigFallsBackToBundledDefaultsAndReportsError() {
        MapConfig host = new MapConfig().put(P + "default.passiveThreshold", "1.5")
                .put(P + "resident.enabled", "false");
        PolicyResolver.Resolved r = PolicyResolver.resolve(host, WorkflowType.RESIDENT);
        assertThat(r.configError).contains("passiveThreshold");
        assertThat(r.policy.passiveThreshold).isEqualTo(0.85f);
        assertThat(r.policy.enabled).as("an invalid config must never switch liveness off").isTrue();
    }

    @Test
    void unparsableValuesFallBack() {
        assertThat(PolicyResolver.resolve(new MapConfig().put(P + "default.maxRetries", "three"),
                WorkflowType.RESIDENT).configError).contains("NumberFormatException");
        assertThat(PolicyResolver.resolve(new MapConfig().put(P + "default.challengeTypes", "BLINK,WINK"),
                WorkflowType.RESIDENT).configError).isNotNull();
    }

    @Test
    void duplicatePoolEntriesCannotSatisfyMinChallenges() {
        MapConfig host = new MapConfig().put(P + "default.challengeTypes", "BLINK,BLINK")
                .put(P + "default.minChallenges", "2");
        PolicyResolver.Resolved r = PolicyResolver.resolve(host, WorkflowType.RESIDENT);
        assertThat(r.configError).contains("distinct");
    }

    @Test
    void extraValidationRejectsImpossibleValues() {
        LivenessPolicy p = new LivenessPolicy();
        p.passiveWindowFrames = 0;
        assertThatThrownBy(() -> PolicyResolver.validateExtra(p)).hasMessageContaining("passiveWindowFrames");
        LivenessPolicy q = new LivenessPolicy();
        q.passiveTimeoutMs = 0;
        assertThatThrownBy(() -> PolicyResolver.validateExtra(q)).hasMessageContaining("passiveTimeoutMs");
        LivenessPolicy r = new LivenessPolicy();
        r.cooldownSec = -1;
        assertThatThrownBy(() -> PolicyResolver.validateExtra(r)).hasMessageContaining("cooldownSec");
    }

    @Test
    void nullHostConfigUsesBundledDefaults() {
        PolicyResolver.Resolved r = PolicyResolver.resolve(null, WorkflowType.SUPERVISOR);
        assertThat(r.configError).isNull();
        assertThat(r.policy.minChallenges).isEqualTo(1);
    }

    @Test
    void copyIsIndependent() {
        LivenessPolicy a = PolicyResolver.resolve(new MapConfig(), WorkflowType.RESIDENT).policy;
        LivenessPolicy b = PolicyResolver.copy(a);
        b.challengePool.clear();
        b.maxRetries = 99;
        assertThat(a.challengePool).hasSize(4);
        assertThat(a.maxRetries).isEqualTo(3);
    }
}
