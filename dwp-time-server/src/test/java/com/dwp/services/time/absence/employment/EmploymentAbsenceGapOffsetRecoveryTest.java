package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventInboxOffsetRecoverySupport;
import com.dwp.core.event.DomainEventInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentAbsenceGapOffsetRecoveryTest {

    @Test
    void verifiedGapRecoveryUsesExactOffsetCasAndDeferredOnlyCoreMode() {
        var store = storeWithGap();
        var signed = EmploymentAbsenceTestSupport.signedOwner(5, 5);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerRecovered(
                        signed.success().data(), signed.success().evidence(),
                        EmploymentAbsenceTestSupport.grant()));
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        when(inbox.recoverOffsetFromOwnerSnapshot(any()))
                .thenReturn(new DomainEventInboxOffsetRecoverySupport.OffsetRecoveryResult(
                        0, 5, 1, UUID.randomUUID(), EmploymentAbsenceTestSupport.NOW,
                        DomainEventInboxOffsetRecoverySupport.GAP_REASON_CODE));
        var coordinator = coordinator(store, owner, inbox);

        var outcome = coordinator.recoverGap(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP);

        assertThat(outcome)
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);
        EmploymentAbsenceProjection projection = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(projection.freshness())
                .isEqualTo(EmploymentAbsenceContract.FreshnessState.CURRENT);
        assertThat(projection.sharedOffsetSequence()).isEqualTo(5);
        ArgumentCaptor<DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest> request =
                ArgumentCaptor.forClass(
                        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest.class);
        verify(inbox).recoverOffsetFromOwnerSnapshot(request.capture());
        assertThat(request.getValue().mode())
                .isEqualTo(DomainEventInboxOffsetRecoverySupport.RecoveryMode.GAP);
        assertThat(request.getValue().expectedPriorOffset()).isZero();
        assertThat(request.getValue().recoveredOffset()).isEqualTo(5);
        assertThat(request.getValue().auditEvidence())
                .containsKeys("tenantGrantRevision", "authorizationRevision",
                        "fieldPolicyRevision", "projectionExpectedVersion",
                        "projectionResultVersion");
    }

    @Test
    void retryableOwnerFailureLeavesGapAndNeverAdvancesCoreOffset() {
        var store = storeWithGap();
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerDenied(
                        EmploymentAbsenceContract.OwnerFailure.retryable(
                                "OWNER_TEMPORARILY_UNAVAILABLE")));
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        var coordinator = coordinator(store, owner, inbox);

        var outcome = coordinator.recoverGap(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP);

        assertThat(outcome)
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.RETRYABLE);
        EmploymentAbsenceProjection projection = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(projection.freshness())
                .isEqualTo(EmploymentAbsenceContract.FreshnessState.GAP);
        assertThat(projection.recoveryOutcome())
                .isEqualTo(EmploymentAbsenceContract.RecoveryOutcome.RETRYABLE_FAILURE);
        verify(inbox, never()).recoverOffsetFromOwnerSnapshot(any());
    }

    @Test
    void deferredActivatorTargetsExactlyOneTerminalGapRow() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1, 0);
        JdbcEmploymentAbsenceDeferredInboxActivator activator =
                new JdbcEmploymentAbsenceDeferredInboxActivator(jdbc);

        activator.activateExact(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertThat(sql.getValue())
                .contains("consumer_name = :consumerName")
                .contains("tenant_id = :tenantId")
                .contains("event_source = :eventSource")
                .contains("event_type = :eventType")
                .contains("aggregate_type = :aggregateType")
                .contains("aggregate_id = :aggregateId")
                .contains("aggregate_sequence = :eventSequence")
                .contains("status = 'DEFERRED'");
        assertThat(parameters.getValue().getValue("eventSequence")).isEqualTo(5L);
        assertThat(parameters.getValue().getValue("aggregateId"))
                .isEqualTo(EmploymentAbsenceTestSupport.RELATIONSHIP.toString());

        assertThatThrownBy(() -> activator.activateExact(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one deferred row");
    }

    private static EmploymentAbsenceTestSupport.InMemoryProjectionStore storeWithGap() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        EmploymentAbsenceProjection gap = EmploymentAbsenceProjection
                .baselineClaim(EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP,
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(60))
                .observe(new EmploymentAbsenceProjection.InvalidationEvidence(
                                5, UUID.randomUUID(), "ASSIGNMENT_CHANGED",
                                LocalDate.of(2026, 10, 21),
                                EmploymentAbsenceTestSupport.DIGEST_B,
                                EmploymentAbsenceTestSupport.CORRELATION.toString()),
                        0, EmploymentAbsenceContract.FreshnessState.GAP,
                        EmploymentAbsenceContract.RecoveryOutcome.NOT_ATTEMPTED,
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(60));
        store.insertExpectedZero(gap);
        return store;
    }

    private static EmploymentAbsenceConsumerCoordinator coordinator(
            EmploymentAbsenceProjectionStore store,
            EmploymentAbsenceOwnerClient owner,
            DomainEventInboxRepository inbox) {
        when(owner.revalidateForFinalization(any(), any(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new EmploymentAbsenceConsumerCoordinator(
                inbox, store, owner,
                (tenant, relationship, correlation, action, outcome, evidence) -> { },
                (tenant, relationship, sequence) -> { },
                new EmploymentAbsenceActivationGate(true,
                        java.util.Set.of(EmploymentAbsenceTestSupport.TENANT)),
                EmploymentAbsenceTestSupport.mapper(),
                new EmploymentAbsenceTestSupport.NoopTransactionManager(),
                EmploymentAbsenceTestSupport.CLOCK, "test-worker");
    }
}
