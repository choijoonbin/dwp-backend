package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventInboxRepository;
import com.dwp.core.event.DomainEventInboxStrictValidationSupport;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.FreshnessState;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.RecoveryOutcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentAbsenceProjectionOrderingTest {

    @Test
    void coveredContiguousEventDoesNotRefetchOrFastForwardToOwnerRevision() {
        EmploymentAbsenceProjection current =
                EmploymentAbsenceTestSupport.currentProjection(5, 0);
        var ownerSnapshot = current.ownerSnapshot();
        var evidence = invalidation(1);

        EmploymentAbsenceProjection next = current.covered(
                evidence, 1, EmploymentAbsenceTestSupport.NOW.plusSeconds(1));

        assertThat(next.freshness()).isEqualTo(FreshnessState.CURRENT);
        assertThat(next.sharedOffsetSequence()).isEqualTo(1);
        assertThat(next.ownerSnapshot()).isSameAs(ownerSnapshot);
        assertThat(next.ownerSnapshot().relationshipRevision()).isEqualTo(5);
        assertThat(next.requiredRelationshipRevision()).isEqualTo(1);
    }

    @Test
    void gapBackfillPreservesHighestEvidenceUntilTerminalPredecessor() {
        EmploymentAbsenceProjection gap = EmploymentAbsenceProjection
                .baselineClaim(EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP,
                        EmploymentAbsenceTestSupport.NOW)
                .observe(invalidation(5), 0, FreshnessState.GAP,
                        RecoveryOutcome.NOT_ATTEMPTED,
                        EmploymentAbsenceTestSupport.NOW);

        EmploymentAbsenceProjection afterOne = gap.gapBackfill(
                1, EmploymentAbsenceTestSupport.NOW.plusSeconds(1));
        EmploymentAbsenceProjection afterTwo = afterOne.gapBackfill(
                2, EmploymentAbsenceTestSupport.NOW.plusSeconds(2));
        EmploymentAbsenceProjection afterThree = afterTwo.gapBackfill(
                3, EmploymentAbsenceTestSupport.NOW.plusSeconds(3));
        EmploymentAbsenceProjection afterFour = afterThree.gapBackfill(
                4, EmploymentAbsenceTestSupport.NOW.plusSeconds(4));

        assertThat(afterThree.freshness()).isEqualTo(FreshnessState.GAP);
        assertThat(afterThree.lastObservedEventSequence()).isEqualTo(5);
        assertThat(afterThree.lastInvalidation().eventSequence()).isEqualTo(5);
        assertThat(afterFour.freshness()).isEqualTo(FreshnessState.REFETCH_PENDING);
        assertThat(afterFour.gapExpectedSequence()).isNull();
        assertThat(afterFour.sharedOffsetSequence()).isEqualTo(4);
    }

    @Test
    void staleCasAndInvalidClosedStatePairsFailClosed() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        EmploymentAbsenceProjection inserted = store.insertExpectedZero(
                EmploymentAbsenceProjection.baselineClaim(
                        EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP,
                        EmploymentAbsenceTestSupport.NOW));

        assertThatThrownBy(() -> store.updateExpectedVersion(inserted, 2))
                .isInstanceOf(JdbcEmploymentAbsenceProjectionStore.ProjectionCasLost.class);
        assertThatThrownBy(() -> new EmploymentAbsenceProjection(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                FreshnessState.CURRENT, 0, 0, 0, null,
                null, null, null, 0, null,
                RecoveryOutcome.PENDING, null, EmploymentAbsenceTestSupport.NOW,
                1, EmploymentAbsenceTestSupport.NOW, EmploymentAbsenceTestSupport.NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quarantinedObservationPreservesPermanentBlockerAndIsIdempotent() {
        EmploymentAbsenceProjection blocked = EmploymentAbsenceProjection.blocker(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                "EVENT_CONTRACT_VIOLATION",
                EmploymentAbsenceTestSupport.NOW);

        EmploymentAbsenceProjection observed = blocked.observeWhileQuarantined(
                invalidation(2), 0);
        EmploymentAbsenceProjection afterBackfill = observed.observeWhileQuarantined(
                invalidation(1), 1);
        EmploymentAbsenceProjection repeated = afterBackfill.observeWhileQuarantined(
                invalidation(1), 1);

        assertThat(observed.freshness()).isEqualTo(FreshnessState.QUARANTINED);
        assertThat(afterBackfill.freshness()).isEqualTo(FreshnessState.QUARANTINED);
        assertThat(repeated).isEqualTo(afterBackfill);
        assertThat(afterBackfill.lastRecoveryErrorCode())
                .isEqualTo("EVENT_CONTRACT_VIOLATION");
        assertThat(afterBackfill.lastRecoveryAt())
                .isEqualTo(EmploymentAbsenceTestSupport.NOW);
        assertThat(afterBackfill.lastObservedEventSequence()).isEqualTo(2);
        assertThat(afterBackfill.lastInvalidation().eventSequence()).isEqualTo(2);
        assertThat(afterBackfill.sharedOffsetSequence()).isEqualTo(1);
    }

    @Test
    void coordinatorNeverAutoReleasesQuarantineWhenOrdinaryEventArrives() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.insertExpectedZero(EmploymentAbsenceProjection.blocker(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                "EVENT_CONTRACT_VIOLATION",
                EmploymentAbsenceTestSupport.NOW));
        var event = EmploymentAbsenceTestSupport.event(1);
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        when(inbox.registerForStrictValidation(anyString(), any()))
                .thenReturn(new DomainEventInboxStrictValidationSupport.RegistrationResult(
                        DomainEventInboxStrictValidationSupport.RegistrationState.REGISTERED,
                        new DomainEventInboxStrictValidationSupport.PreparedEvent(
                                "{}", EmploymentAbsenceTestSupport.DIGEST_A),
                        null, null));
        when(inbox.begin(anyString(), any(), anyString(), any()))
                .thenReturn(new DomainEventInboxRepository.BeginResult(
                        DomainEventInboxRepository.BeginState.ACQUIRED,
                        "lease-token", 1));
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

        assertThat(coordinator.consume(event))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);

        EmploymentAbsenceProjection after = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(after.freshness()).isEqualTo(FreshnessState.QUARANTINED);
        assertThat(after.recoveryOutcome())
                .isEqualTo(RecoveryOutcome.PERMANENT_FAILURE);
        assertThat(after.lastRecoveryErrorCode())
                .isEqualTo("EVENT_CONTRACT_VIOLATION");
        assertThat(after.sharedOffsetSequence()).isEqualTo(1);
        assertThat(after.lastObservedEventSequence()).isEqualTo(1);
        verify(inbox).complete(EmploymentAbsenceContract.CONSUMER, event, "lease-token");
        verify(owner, never()).recover(
                org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void strictReplayOfPriorContractDeadPerformsOwnerRecovery() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.insertExpectedZero(EmploymentAbsenceProjection.blocker(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                "EVENT_CONTRACT_VIOLATION",
                EmploymentAbsenceTestSupport.NOW));
        var event = EmploymentAbsenceTestSupport.event(1);
        DomainEventInboxRepository inbox = replayInbox(event);
        var signed = EmploymentAbsenceTestSupport.signedOwner(1, 1);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerRecovered(
                        signed.success().data(), signed.success().evidence(),
                        EmploymentAbsenceTestSupport.grant()));
        when(owner.revalidateForFinalization(any(), any(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(coordinator(inbox, store, owner).consume(event))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);

        EmploymentAbsenceProjection recovered = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(recovered.freshness()).isEqualTo(FreshnessState.CURRENT);
        assertThat(recovered.recoveryOutcome()).isEqualTo(RecoveryOutcome.SUCCEEDED);
        assertThat(recovered.lastRecoveryErrorCode()).isNull();
        assertThat(recovered.sharedOffsetSequence()).isEqualTo(1);
        assertThat(recovered.lastObservedEventSequence()).isEqualTo(1);
        verify(owner).recover(eq(EmploymentAbsenceTestSupport.TENANT),
                eq(EmploymentAbsenceTestSupport.RELATIONSHIP), eq(1L), any());
    }

    @Test
    void duplicateStrictReplayRefreshesProjectionWithoutCompletingInboxOrAdvancingOffset() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        EmploymentAbsenceProjection quarantined = EmploymentAbsenceTestSupport
                .currentProjection(5, 3)
                .failed(EmploymentAbsenceContract.OwnerFailure.permanent(
                                "STORED_PROJECTION_INTEGRITY_FAILURE"),
                        false, EmploymentAbsenceTestSupport.NOW);
        store.put(quarantined);
        var event = EmploymentAbsenceTestSupport.event(1);
        DomainEventInboxRepository inbox = replayInbox(
                event, DomainEventInboxRepository.BeginState.DUPLICATE);
        var signed = EmploymentAbsenceTestSupport.signedOwner(5, 3);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);
        when(owner.recover(anyLong(), any(), anyLong(), any()))
                .thenReturn(new EmploymentAbsenceContract.OwnerRecovered(
                        signed.success().data(), signed.success().evidence(),
                        EmploymentAbsenceTestSupport.grant()));
        when(owner.revalidateForFinalization(any(), any(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(coordinator(inbox, store, owner).consume(event))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);

        EmploymentAbsenceProjection recovered = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(recovered.freshness()).isEqualTo(FreshnessState.CURRENT);
        assertThat(recovered.recoveryOutcome()).isEqualTo(RecoveryOutcome.SUCCEEDED);
        assertThat(recovered.lastRecoveryErrorCode()).isNull();
        assertThat(recovered.sharedOffsetSequence()).isEqualTo(3);
        assertThat(recovered.lastObservedEventSequence()).isEqualTo(3);
        verify(owner).recover(eq(EmploymentAbsenceTestSupport.TENANT),
                eq(EmploymentAbsenceTestSupport.RELATIONSHIP), eq(5L), any());
        verify(inbox, never()).complete(anyString(), any(), any());
        verify(inbox, never()).recoverOffsetFromOwnerSnapshot(any());
    }

    @Test
    void replayPendingCannotReleasePayloadConflictQuarantine() {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.insertExpectedZero(EmploymentAbsenceProjection.blocker(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                "PAYLOAD_CONFLICT",
                EmploymentAbsenceTestSupport.NOW));
        var event = EmploymentAbsenceTestSupport.event(1);
        DomainEventInboxRepository inbox = replayInbox(event);
        EmploymentAbsenceOwnerClient owner = mock(EmploymentAbsenceOwnerClient.class);

        assertThat(coordinator(inbox, store, owner).consume(event))
                .isEqualTo(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.APPLIED);

        EmploymentAbsenceProjection blocked = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(blocked.freshness()).isEqualTo(FreshnessState.QUARANTINED);
        assertThat(blocked.lastRecoveryErrorCode()).isEqualTo("PAYLOAD_CONFLICT");
        assertThat(blocked.sharedOffsetSequence()).isEqualTo(1);
        verify(owner, never()).recover(anyLong(), any(), anyLong(), any());
    }

    @Test
    void quarantineReleaseDeniesMissingAuthorityEventEvidenceBlockerAndStaleVersion() {
        EmploymentAbsenceProjection recoverable = recoverableQuarantine();

        var unauthorizedStore = store(recoverable);
        assertThat(recoveryService(unauthorizedStore, false, evidence -> { })
                .release(releaseRequest(1)))
                .isEqualTo(EmploymentAbsenceQuarantineRecoveryService.ReleaseOutcome
                        .DENIED_AUTHORITY);

        for (String code : List.of("EVENT_CONTRACT_VIOLATION", "PAYLOAD_CONFLICT")) {
            var eventStore = store(EmploymentAbsenceTestSupport.currentProjection(5, 3)
                    .failed(EmploymentAbsenceContract.OwnerFailure.permanent(code),
                            false, EmploymentAbsenceTestSupport.NOW));
            assertThat(recoveryService(eventStore, true, evidence -> { })
                    .release(releaseRequest(1)))
                    .isEqualTo(EmploymentAbsenceQuarantineRecoveryService.ReleaseOutcome
                            .DENIED_ERROR_CODE);
        }

        var blockedStore = store(recoverable);
        blockedStore.blocking = true;
        assertThat(recoveryService(blockedStore, true, evidence -> { })
                .release(releaseRequest(1)))
                .isEqualTo(EmploymentAbsenceQuarantineRecoveryService.ReleaseOutcome
                        .DENIED_BLOCKER);

        var staleStore = store(recoverable);
        assertThat(recoveryService(staleStore, true, evidence -> { })
                .release(releaseRequest(2)))
                .isEqualTo(EmploymentAbsenceQuarantineRecoveryService.ReleaseOutcome
                        .DENIED_STALE_VERSION);
    }

    @Test
    void authorizedQuarantineReleasePreservesFactsOffsetAndInvalidationAndAudits() {
        EmploymentAbsenceProjection before = recoverableQuarantine();
        var store = store(before);
        AtomicReference<Map<String, Object>> auditEvidence = new AtomicReference<>();
        EmploymentAbsenceQuarantineRecoveryService service = recoveryService(
                store, true, auditEvidence::set);

        assertThat(service.release(releaseRequest(before.projectionVersion())))
                .isEqualTo(EmploymentAbsenceQuarantineRecoveryService.ReleaseOutcome
                        .RELEASED);

        EmploymentAbsenceProjection after = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(after.freshness()).isEqualTo(FreshnessState.REFETCH_PENDING);
        assertThat(after.recoveryOutcome()).isEqualTo(RecoveryOutcome.PENDING);
        assertThat(after.lastRecoveryErrorCode()).isNull();
        assertThat(after.lastRecoveryAt())
                .isEqualTo(EmploymentAbsenceTestSupport.NOW.plusSeconds(2));
        assertThat(after.projectionVersion())
                .isEqualTo(before.projectionVersion() + 1);
        assertThat(after.ownerSnapshot()).isEqualTo(before.ownerSnapshot());
        assertThat(after.ownerEvidence()).isEqualTo(before.ownerEvidence());
        assertThat(after.ownerBusinessTimeZone())
                .isEqualTo(before.ownerBusinessTimeZone());
        assertThat(after.ownerMinimumRelationshipRevision())
                .isEqualTo(before.ownerMinimumRelationshipRevision());
        assertThat(after.sharedOffsetSequence())
                .isEqualTo(before.sharedOffsetSequence());
        assertThat(after.lastObservedEventSequence())
                .isEqualTo(before.lastObservedEventSequence());
        assertThat(after.requiredRelationshipRevision())
                .isEqualTo(before.requiredRelationshipRevision());
        assertThat(after.lastInvalidation()).isEqualTo(before.lastInvalidation());
        assertThat(auditEvidence.get())
                .containsEntry("releasedErrorCode",
                        "STORED_PROJECTION_INTEGRITY_FAILURE")
                .containsEntry("projectionExpectedVersion", 1L)
                .containsEntry("projectionResultVersion", 2L)
                .containsEntry("capability",
                        EmploymentAbsenceQuarantineRecoveryService.CAPABILITY);
    }

    @Test
    void gapOriginPermanentQuarantineCannotBeOperatorReleased() {
        EmploymentAbsenceProjection gapOrigin = EmploymentAbsenceProjection
                .baselineClaim(EmploymentAbsenceTestSupport.TENANT,
                        EmploymentAbsenceTestSupport.RELATIONSHIP,
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(2))
                .observe(invalidation(5), 0, FreshnessState.GAP,
                        RecoveryOutcome.NOT_ATTEMPTED,
                        EmploymentAbsenceTestSupport.NOW.minusSeconds(1))
                .failed(EmploymentAbsenceContract.OwnerFailure.permanent(
                                "OWNER_SIGNATURE_INVALID"),
                        true, EmploymentAbsenceTestSupport.NOW);
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        EmploymentAbsenceProjection before = store.insertExpectedZero(gapOrigin);

        assertThat(recoveryService(store, true, evidence -> { })
                .release(releaseRequest(before.projectionVersion())))
                .isEqualTo(EmploymentAbsenceQuarantineRecoveryService.ReleaseOutcome
                        .DENIED_STATE);
        assertThat(store.find(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow())
                .isEqualTo(before);
    }

    private static EmploymentAbsenceProjection recoverableQuarantine() {
        return EmploymentAbsenceTestSupport.currentProjection(5, 3)
                .failed(EmploymentAbsenceContract.OwnerFailure.permanent(
                                "STORED_PROJECTION_INTEGRITY_FAILURE"),
                        false, EmploymentAbsenceTestSupport.NOW);
    }

    private static EmploymentAbsenceTestSupport.InMemoryProjectionStore store(
            EmploymentAbsenceProjection projection) {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.put(projection);
        return store;
    }

    private static EmploymentAbsenceQuarantineRecoveryService recoveryService(
            EmploymentAbsenceTestSupport.InMemoryProjectionStore store,
            boolean authorized,
            java.util.function.Consumer<Map<String, Object>> evidence) {
        return new EmploymentAbsenceQuarantineRecoveryService(
                store,
                (tenant, actor, capability, correlation) -> authorized,
                (tenant, relationship, correlation, action, outcome, detail) ->
                        evidence.accept(detail),
                new EmploymentAbsenceActivationGate(true,
                        java.util.Set.of(EmploymentAbsenceTestSupport.TENANT)),
                new EmploymentAbsenceTestSupport.NoopTransactionManager(),
                java.time.Clock.fixed(
                        EmploymentAbsenceTestSupport.NOW.plusSeconds(2),
                        java.time.ZoneOffset.UTC));
    }

    private static DomainEventInboxRepository replayInbox(
            com.dwp.core.event.DomainEventEnvelope event) {
        return replayInbox(event, DomainEventInboxRepository.BeginState.ACQUIRED);
    }

    private static DomainEventInboxRepository replayInbox(
            com.dwp.core.event.DomainEventEnvelope event,
            DomainEventInboxRepository.BeginState beginState) {
        DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
        var stored = new DomainEventInboxStrictValidationSupport.StoredEvent(
                event.id(), event.source(), event.type(), event.schemaVersion(),
                event.tenantId(), event.aggregateType(), event.aggregateId(),
                event.aggregateSequence(), event.correlationId(), "{}",
                EmploymentAbsenceTestSupport.DIGEST_A, "REPLAY_PENDING", 1,
                EmploymentAbsenceTestSupport.NOW, null,
                DomainEventInboxStrictValidationSupport.INVALID_EVENT_REASON
                        + ": prior contract failure");
        when(inbox.registerForStrictValidation(anyString(), any()))
                .thenReturn(new DomainEventInboxStrictValidationSupport.RegistrationResult(
                        DomainEventInboxStrictValidationSupport.RegistrationState.EXISTING,
                        new DomainEventInboxStrictValidationSupport.PreparedEvent(
                                "{}", EmploymentAbsenceTestSupport.DIGEST_A),
                        stored, null));
        when(inbox.begin(anyString(), any(), anyString(), any()))
                .thenReturn(new DomainEventInboxRepository.BeginResult(
                        beginState,
                        beginState == DomainEventInboxRepository.BeginState.ACQUIRED
                                ? "replay-lease" : null,
                        2));
        return inbox;
    }

    private static EmploymentAbsenceConsumerCoordinator coordinator(
            DomainEventInboxRepository inbox,
            EmploymentAbsenceProjectionStore store,
            EmploymentAbsenceOwnerClient owner) {
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

    private static EmploymentAbsenceQuarantineRecoveryService.ReleaseRequest
            releaseRequest(long expectedVersion) {
        return new EmploymentAbsenceQuarantineRecoveryService.ReleaseRequest(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                expectedVersion, "operator-1", "MANUAL_RECOVERY",
                EmploymentAbsenceTestSupport.CORRELATION);
    }

    private static EmploymentAbsenceProjection.InvalidationEvidence invalidation(long sequence) {
        return new EmploymentAbsenceProjection.InvalidationEvidence(
                sequence, UUID.randomUUID(), "ASSIGNMENT_CHANGED",
                LocalDate.of(2026, 10, 21), EmploymentAbsenceTestSupport.DIGEST_B,
                EmploymentAbsenceTestSupport.CORRELATION.toString());
    }
}
