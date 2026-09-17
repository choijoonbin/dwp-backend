package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionTypeTarget;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationAttentionPolicyGuardTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationEffectivePolicyRepository repository =
            mock(NotificationEffectivePolicyRepository.class);
    private final NotificationAttentionPolicyGuard guard =
            new NotificationAttentionPolicyGuard(repository);

    @Test
    void mandatoryPolicyAlwaysLocksMute() {
        EffectivePolicy policy = new EffectivePolicy(
                UUID.randomUUID(), 42L, "TYPE", "APPROVAL.ACTION_REQUIRED", 9,
                true, false, "IMMEDIATE",
                Map.of("IN_APP", new EffectivePolicyChannel(
                        "IN_APP", true, "IMMEDIATE", true, null)));

        AttentionPolicyDecision decision = guard.evaluate(
                List.of(policy),
                List.of(new AttentionTypeTarget(
                        "approvals", "APPROVAL.ACTION_REQUIRED")),
                "MUTE",
                Map.of());

        assertThat(decision.locked()).isTrue();
        assertThat(decision.policySource()).isEqualTo("TENANT_POLICY");
        assertThat(decision.reasonCode()).isEqualTo("MANDATORY_POLICY");
        assertThat(decision.policyRevision()).isEqualTo("9");
    }

    @Test
    void policyLookupFailureIsFailClosed() {
        when(repository.findForTenant(42L))
                .thenThrow(new DataAccessResourceFailureException("offline"));

        assertThatThrownBy(() -> guard.load(ACTOR))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
