package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventInboxRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentAbsenceInboxProjectionReconcilerTest {

    @Test
    void skipsLiveLeaseAndReprocessesDeferredAndDeadRowsThroughStrictCoordinator() {
        var liveEvent = EmploymentAbsenceTestSupport.event(1);
        var deferredEvent = EmploymentAbsenceTestSupport.event(5);
        var deadEvent = EmploymentAbsenceTestSupport.event(6);
        EmploymentAbsenceInboxSource source = maximum -> List.of(
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        liveEvent, liveEvent.id(), EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP.toString(),
                        "PROCESSING", "live-token",
                        EmploymentAbsenceTestSupport.NOW.plusSeconds(10), null),
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        deferredEvent, deferredEvent.id(),
                        EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP.toString(),
                        "DEFERRED", null, null, "waiting"),
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        deadEvent, deadEvent.id(), EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP.toString(),
                        "DEAD", null, null, "contract"));
        EmploymentAbsenceConsumerCoordinator coordinator =
                mock(EmploymentAbsenceConsumerCoordinator.class);
        when(coordinator.consume(deferredEvent))
                .thenReturn(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.GAP);
        when(coordinator.consume(deadEvent))
                .thenReturn(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.DEAD);
        var reconciler = new EmploymentAbsenceInboxProjectionReconciler(
                source, coordinator, EmploymentAbsenceTestSupport.CLOCK);

        var result = reconciler.reconcile(10);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.activeLeases()).isEqualTo(1);
        assertThat(result.malformedRows()).isZero();
        assertThat(result.outcomes()).containsEntry(
                EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.GAP, 1)
                .containsEntry(
                        EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.DEAD, 1);
        verify(coordinator, never()).consume(liveEvent);
        verify(coordinator).consume(deferredEvent);
        verify(coordinator).consume(deadEvent);
    }

    @Test
    void processingWithoutLockTokenIsNotTreatedAsAnActiveLease() {
        var event = EmploymentAbsenceTestSupport.event(1);
        EmploymentAbsenceInboxSource source = maximum -> List.of(
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        event, event.id(), EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP.toString(),
                        "PROCESSING", null,
                        EmploymentAbsenceTestSupport.NOW.plusSeconds(10), null));
        EmploymentAbsenceConsumerCoordinator coordinator =
                mock(EmploymentAbsenceConsumerCoordinator.class);
        when(coordinator.consume(event))
                .thenReturn(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.RETRYABLE);
        var reconciler = new EmploymentAbsenceInboxProjectionReconciler(
                source, coordinator, EmploymentAbsenceTestSupport.CLOCK);

        var result = reconciler.reconcile(10);

        assertThat(result.activeLeases()).isZero();
        assertThat(result.outcomes()).containsEntry(
                EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.RETRYABLE, 1);
        verify(coordinator).consume(event);
    }

    @Test
    void malformedStoredEnvelopeIsIsolatedAndBatchContinues() {
        UUID malformedEventId = UUID.randomUUID();
        var validEvent = EmploymentAbsenceTestSupport.event(2);
        EmploymentAbsenceInboxSource source = maximum -> List.of(
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        null, malformedEventId, EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP.toString(),
                        "FAILED", null, null, "malformed envelope"),
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        validEvent, validEvent.id(), EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP.toString(),
                        "DEFERRED", null, null, "waiting"));
        EmploymentAbsenceConsumerCoordinator coordinator =
                mock(EmploymentAbsenceConsumerCoordinator.class);
        when(coordinator.reconcileMalformedStoredEnvelope(
                malformedEventId, EmploymentAbsenceTestSupport.DIGEST_A,
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP.toString()))
                .thenReturn(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.DEAD);
        when(coordinator.consume(validEvent))
                .thenReturn(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.GAP);
        var reconciler = new EmploymentAbsenceInboxProjectionReconciler(
                source, coordinator, EmploymentAbsenceTestSupport.CLOCK);

        var result = reconciler.reconcile(10);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.malformedRows()).isEqualTo(1);
        assertThat(result.outcomes())
                .containsEntry(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.DEAD, 1)
                .containsEntry(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.GAP, 1);
        verify(coordinator).reconcileMalformedStoredEnvelope(
                malformedEventId, EmploymentAbsenceTestSupport.DIGEST_A,
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP.toString());
        verify(coordinator).consume(validEvent);
    }

    @Test
    void nonUuidMalformedAggregateIsFinalizedWithoutProjectionMutationAndBatchContinues() {
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        EmploymentAbsenceConsumerCoordinator coordinator =
                new EmploymentAbsenceConsumerCoordinator(
                        inbox, store, owner,
                        (tenant, relationship, correlation, action, outcome, evidence) -> { },
                        (tenant, relationship, sequence) -> { },
                        new EmploymentAbsenceActivationGate(true,
                                java.util.Set.of(EmploymentAbsenceTestSupport.TENANT)),
                        EmploymentAbsenceTestSupport.mapper(),
                        new EmploymentAbsenceTestSupport.NoopTransactionManager(),
                        EmploymentAbsenceTestSupport.CLOCK, "test-worker");
        EmploymentAbsenceInboxSource source = maximum -> List.of(
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        null, firstEventId, EmploymentAbsenceTestSupport.DIGEST_A,
                        EmploymentAbsenceTestSupport.TENANT, "NOT-A-UUID",
                        "FAILED", null, null, "malformed envelope"),
                new EmploymentAbsenceInboxSource.InboxCandidate(
                        null, secondEventId, EmploymentAbsenceTestSupport.DIGEST_B,
                        EmploymentAbsenceTestSupport.TENANT, "still-not-a-uuid",
                        "FAILED", null, null, "malformed envelope"));
        var reconciler = new EmploymentAbsenceInboxProjectionReconciler(
                source, coordinator, EmploymentAbsenceTestSupport.CLOCK);

        var result = reconciler.reconcile(10);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.malformedRows()).isEqualTo(2);
        assertThat(result.outcomes()).containsEntry(
                EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.DEAD, 2);
        verify(inbox).finalizeRegisteredInvalid(
                EmploymentAbsenceContract.CONSUMER, firstEventId,
                EmploymentAbsenceTestSupport.DIGEST_A,
                "stored TIM-003 envelope is invalid");
        verify(inbox).finalizeRegisteredInvalid(
                EmploymentAbsenceContract.CONSUMER, secondEventId,
                EmploymentAbsenceTestSupport.DIGEST_B,
                "stored TIM-003 envelope is invalid");
        assertThat(store.find(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP)).isEmpty();
        verify(owner, never()).recover(anyLong(), any(), anyLong(), any());
    }

    @Test
    void emptyBatchIsStableAndDoesNoCoordinatorWork() {
        EmploymentAbsenceConsumerCoordinator coordinator =
                mock(EmploymentAbsenceConsumerCoordinator.class);
        var reconciler = new EmploymentAbsenceInboxProjectionReconciler(
                maximum -> List.of(), coordinator, EmploymentAbsenceTestSupport.CLOCK);

        var result = reconciler.reconcile(25);

        assertThat(result.scanned()).isZero();
        assertThat(result.outcomes()).isEmpty();
        verify(coordinator, never()).consume(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void defaultOffAndEmptyEnrollmentPermitNoTenantWork() {
        EmploymentAbsenceActivationGate gate =
                new EmploymentAbsenceActivationGate(false, "");

        assertThat(gate.enabled()).isFalse();
        assertThat(gate.enrolledTenants()).isEmpty();
        assertThat(gate.permits(EmploymentAbsenceTestSupport.TENANT)).isFalse();
    }
}
