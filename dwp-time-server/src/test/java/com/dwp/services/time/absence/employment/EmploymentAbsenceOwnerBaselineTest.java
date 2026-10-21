package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventInboxOffsetRecoverySupport;
import com.dwp.core.event.DomainEventInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentAbsenceOwnerBaselineTest {

    @Test
    void eventlessBaselineUsesZeroMinimumAndSnapshotOnlyOffsetCheckpoint() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerRecovered(
                        signed.success().data(), signed.success().evidence(),
                        EmploymentAbsenceTestSupport.grant()));
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        when(inbox.recoverOffsetFromOwnerSnapshot(any()))
                .thenReturn(new DomainEventInboxOffsetRecoverySupport.OffsetRecoveryResult(
                        0, 0, 0, UUID.randomUUID(), EmploymentAbsenceTestSupport.NOW,
                        DomainEventInboxOffsetRecoverySupport.BASELINE_REASON_CODE));
        EmploymentAbsenceConsumerCoordinator coordinator = coordinator(store, owner, inbox);

        var outcome = coordinator.recoverBaseline(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP);

        assertThat(outcome)
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);
        EmploymentAbsenceProjection projection = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(projection.freshness())
                .isEqualTo(EmploymentAbsenceContract.FreshnessState.CURRENT);
        assertThat(projection.lastObservedEventSequence()).isZero();
        assertThat(projection.lastInvalidation()).isNull();
        assertThat(projection.sharedOffsetSequence()).isZero();

        ArgumentCaptor<DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest> request =
                ArgumentCaptor.forClass(
                        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest.class);
        verify(inbox).recoverOffsetFromOwnerSnapshot(request.capture());
        assertThat(request.getValue().mode())
                .isEqualTo(DomainEventInboxOffsetRecoverySupport.RecoveryMode.OWNER_BASELINE);
        assertThat(request.getValue().expectedPriorOffset()).isZero();
        assertThat(request.getValue().recoveredOffset()).isZero();
    }

    @Test
    void unresolvedInboxPreventsBaselineAndOwnerCall() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.unresolved = true;
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        EmploymentAbsenceConsumerCoordinator coordinator = coordinator(store, owner, inbox);

        var outcome = coordinator.recoverBaseline(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP);

        assertThat(outcome)
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.BLOCKED);
        verify(owner, never()).recover(anyLong(), any(), anyLong(), any());
    }

    @Test
    void retryableBaselineFailureCanBeReclaimedAfterBackoff() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        MutableClock clock = new MutableClock(EmploymentAbsenceTestSupport.NOW);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerDenied(
                                EmploymentAbsenceContract.OwnerFailure.retryable(
                                        "OWNER_TEMPORARILY_UNAVAILABLE")),
                        new EmploymentAbsenceContract.OwnerRecovered(
                                signed.success().data(), signed.success().evidence(),
                                EmploymentAbsenceTestSupport.grant()));
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        when(inbox.recoverOffsetFromOwnerSnapshot(any()))
                .thenReturn(new DomainEventInboxOffsetRecoverySupport.OffsetRecoveryResult(
                        0, 0, 0, UUID.randomUUID(), EmploymentAbsenceTestSupport.NOW,
                        DomainEventInboxOffsetRecoverySupport.BASELINE_REASON_CODE));
        EmploymentAbsenceConsumerCoordinator coordinator =
                coordinator(store, owner, inbox, clock);

        assertThat(coordinator.recoverBaseline(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.RETRYABLE);
        clock.advanceSeconds(16);
        assertThat(coordinator.recoverBaseline(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);

        EmploymentAbsenceProjection projection = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(projection.freshness())
                .isEqualTo(EmploymentAbsenceContract.FreshnessState.CURRENT);
        assertThat(projection.recoveryOutcome())
                .isEqualTo(EmploymentAbsenceContract.RecoveryOutcome.SUCCEEDED);
        verify(owner, times(2)).recover(anyLong(), any(), anyLong(), any());
    }

    @Test
    void currentRefreshPreservesOffsetAndInvalidationEvidence() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        EmploymentAbsenceProjection before =
                EmploymentAbsenceTestSupport.currentProjection(5, 3);
        store.put(before);
        var signed = EmploymentAbsenceTestSupport.signedOwner(5, 5);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerRecovered(
                        signed.success().data(), signed.success().evidence(),
                        EmploymentAbsenceTestSupport.grant()));
        EmploymentAbsenceConsumerCoordinator coordinator = coordinator(
                store, owner, mock(DomainEventInboxRepository.class));

        assertThat(coordinator.refreshCurrent(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);

        EmploymentAbsenceProjection refreshed = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(refreshed.sharedOffsetSequence())
                .isEqualTo(before.sharedOffsetSequence());
        assertThat(refreshed.lastObservedEventSequence())
                .isEqualTo(before.lastObservedEventSequence());
        assertThat(refreshed.requiredRelationshipRevision())
                .isEqualTo(before.requiredRelationshipRevision());
        assertThat(refreshed.lastInvalidation()).isEqualTo(before.lastInvalidation());
    }

    @Test
    void currentRefreshRejectsEventlessBaselinePlaceholder() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.insertExpectedZero(EmploymentAbsenceProjection.baselineClaim(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                EmploymentAbsenceTestSupport.NOW.minusSeconds(60)));
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        EmploymentAbsenceConsumerCoordinator coordinator = coordinator(
                store, owner, mock(DomainEventInboxRepository.class));

        assertThat(coordinator.refreshCurrent(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.BLOCKED);
        verify(owner, never()).recover(anyLong(), any(), anyLong(), any());
    }

    @Test
    void integrityReleasedBaselineRefreshUsesSharedOffsetAsOwnerRevisionFloor() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        var signed = EmploymentAbsenceTestSupport.signedOwner(5, 0);
        EmploymentAbsenceProjection released = EmploymentAbsenceProjection.baselineClaim(
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP,
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(120))
                .recovered(signed.success().data(), signed.success().evidence(),
                        "UTC", 0, 5,
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(120))
                .quarantineStoredIntegrityFailure(
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(90))
                .releaseQuarantine(
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(60));
        store.put(released);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerDenied(
                        EmploymentAbsenceContract.OwnerFailure.retryable(
                                "OWNER_TEMPORARILY_UNAVAILABLE")));
        EmploymentAbsenceConsumerCoordinator coordinator = coordinator(
                store, owner, mock(DomainEventInboxRepository.class));
        ArgumentCaptor<Long> minimumRevision = ArgumentCaptor.forClass(Long.class);

        assertThat(coordinator.refreshCurrent(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.RETRYABLE);

        verify(owner).recover(anyLong(), any(), minimumRevision.capture(), any());
        assertThat(minimumRevision.getValue()).isEqualTo(5L);
    }

    private static EmploymentAbsenceConsumerCoordinator coordinator(
            EmploymentAbsenceProjectionStore store,
            EmploymentAbsenceOwnerClient owner,
            DomainEventInboxRepository inbox) {
        return coordinator(store, owner, inbox, EmploymentAbsenceTestSupport.CLOCK);
    }

    private static EmploymentAbsenceConsumerCoordinator coordinator(
            EmploymentAbsenceProjectionStore store,
            EmploymentAbsenceOwnerClient owner,
            DomainEventInboxRepository inbox,
            Clock clock) {
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
                clock, "test-worker");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
