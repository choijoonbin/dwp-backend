package com.dwp.services.platform.hrisconfiguration.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationDomainPolicyTest {

    @Test
    void exactLifecycleMatricesRejectSkippedAndTerminalTransitions() {
        IntegrationDomainPolicy.requireTransition(
                DefinitionState.DRAFT, DefinitionState.VALIDATED);
        IntegrationDomainPolicy.requireTransition(
                ExecutionState.RESULT_UNKNOWN, ExecutionState.RECONCILING);

        assertThatThrownBy(() -> IntegrationDomainPolicy.requireTransition(
                DefinitionState.DRAFT, DefinitionState.APPROVED))
                .hasMessageContaining("Illegal definition transition");
        assertThatThrownBy(() -> IntegrationDomainPolicy.requireTransition(
                ExecutionState.RECONCILED, ExecutionState.ACCEPTED))
                .hasMessageContaining("Illegal execution transition");
    }

    @Test
    void makerCheckerRejectsTheSameActorOrPopulationScope() {
        IntegrationDomainPolicy.requireMakerChecker(
                11, 12, "a".repeat(64), "b".repeat(64));

        assertThatThrownBy(() -> IntegrationDomainPolicy.requireMakerChecker(
                11, 11, "a".repeat(64), "b".repeat(64)))
                .hasMessageContaining("separate actors");
        assertThatThrownBy(() -> IntegrationDomainPolicy.requireMakerChecker(
                11, 12, "a".repeat(64), "a".repeat(64)))
                .hasMessageContaining("separate actors");
    }

    @Test
    void resultUnknownEvidenceMustRetainTheOriginalProviderKeyAndDigest() {
        IntegrationDomainPolicy.requireOriginalProviderEvidence(
                "provider-key", "provider-key", "a".repeat(64), "a".repeat(64));

        assertThatThrownBy(() ->
                IntegrationDomainPolicy.requireOriginalProviderEvidence(
                        "provider-key", "new-key", "a".repeat(64), "a".repeat(64)))
                .hasMessageContaining("original key and digest");
    }

    @Test
    void retrySelectionMustEqualTheFrozenFailedOnlySet() {
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000801");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000802");
        List<UUID> exact = List.of(first, second);
        String digest = IntegrationDomainPolicy.sha256(exact.toString());

        assertThat(IntegrationDomainPolicy.requireExactRetryableSet(
                exact, List.of(second, first), digest)).containsExactly(first, second);
        assertThatThrownBy(() -> IntegrationDomainPolicy.requireExactRetryableSet(
                List.of(first), exact,
                IntegrationDomainPolicy.sha256(List.of(first).toString())))
                .hasMessageContaining("frozen retryable failed-item set");
    }

    @Test
    void postDispatchCancellationRequiresTerminalCompensationEvidence() {
        IntegrationDomainPolicy.requireCancellationEvidence(
                false, null, null);
        IntegrationDomainPolicy.requireCancellationEvidence(
                true, "policy_abcdefghijklmnop", "a".repeat(64));

        assertThatThrownBy(() ->
                IntegrationDomainPolicy.requireCancellationEvidence(
                        true, "policy_abcdefghijklmnop", null))
                .hasMessageContaining("terminal compensation evidence");
    }
}
