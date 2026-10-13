package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon005AssignmentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon006OrganizationChangedV1;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

@SuppressWarnings("auxiliaryclass")
class PerformanceWorkforceInvalidationConsumerTest {

    private static final LocalDate EFFECTIVE_DATE = LocalDate.parse("2030-01-02");

    @Test
    void mapsAllCanonicalXconInvalidationsToThePerOwnedBoundary() {
        InMemoryPerformanceWorkforceProjectionStore store = readyStore();
        PerformanceWorkforceInvalidationConsumer consumer =
                new PerformanceWorkforceInvalidationConsumer(store);

        UUID workerEvent = PerformanceWorkforceTestSupport.uuid(
                "20000000-0000-0000-0000-000000000001");
        Xcon003WorkerChangedV1 worker = new Xcon003WorkerChangedV1(
                "STATUS_CHANGED",
                EFFECTIVE_DATE,
                1L,
                PerformanceWorkforceTestSupport.MEMBER_ONE.workerPublicId());
        assertThat(consumer.consumeWorkerChanged(envelope(
                workerEvent,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(worker),
                worker)).outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertMapped(
                store.lastEvent(),
                PerformanceWorkforceInvalidationConsumer.WORKER_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.WORKER,
                worker.workerPublicId(),
                1L);

        UUID relationship = PerformanceWorkforceTestSupport.uuid(
                "21000000-0000-0000-0000-000000000001");
        Xcon004EmploymentChangedV1 employment = new Xcon004EmploymentChangedV1(
                "TERMS_CHANGED", EFFECTIVE_DATE, 1L, relationship);
        assertThat(consumer.consumeEmploymentChanged(envelope(
                PerformanceWorkforceTestSupport.uuid(
                        "20000000-0000-0000-0000-000000000002"),
                PerformanceWorkforceInvalidationConsumer.payloadDigest(employment),
                employment)).outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertMapped(
                store.lastEvent(),
                PerformanceWorkforceInvalidationConsumer.EMPLOYMENT_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.EMPLOYMENT,
                relationship,
                1L);

        UUID assignmentEvent = PerformanceWorkforceTestSupport.uuid(
                "20000000-0000-0000-0000-000000000003");
        Xcon005AssignmentChangedV1 assignment = new Xcon005AssignmentChangedV1(
                PerformanceWorkforceTestSupport.MEMBER_ONE.assignmentPublicId(),
                List.of("MANAGER_ASSIGNMENT", "ORGANIZATION"),
                EFFECTIVE_DATE,
                assignmentEvent,
                "CHANGE_MANAGER",
                1L);
        assertThat(consumer.consumeAssignmentChanged(envelope(
                assignmentEvent,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(assignment),
                assignment)).outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertMapped(
                store.lastEvent(),
                PerformanceWorkforceInvalidationConsumer.ASSIGNMENT_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.ASSIGNMENT,
                assignment.assignmentPublicId(),
                1L);

        UUID organizationRevision = PerformanceWorkforceTestSupport.uuid(
                "22000000-0000-0000-0000-000000000001");
        Xcon006OrganizationChangedV1 organization = new Xcon006OrganizationChangedV1(
                List.of(PerformanceWorkforceTestSupport.ORGANIZATION),
                EFFECTIVE_DATE,
                organizationRevision,
                "b".repeat(64));
        assertThat(consumer.consumeOrganizationChanged(envelope(
                PerformanceWorkforceTestSupport.uuid(
                        "20000000-0000-0000-0000-000000000004"),
                PerformanceWorkforceInvalidationConsumer.payloadDigest(organization),
                organization)).outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertMapped(
                store.lastEvent(),
                PerformanceWorkforceInvalidationConsumer.ORGANIZATION_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.ORGANIZATION,
                organizationRevision,
                null);
    }

    @Test
    void rejectsPayloadDigestAndAssignmentEventIdentityMismatch() {
        InMemoryPerformanceWorkforceProjectionStore store = readyStore();
        PerformanceWorkforceInvalidationConsumer consumer =
                new PerformanceWorkforceInvalidationConsumer(store);
        UUID eventId = PerformanceWorkforceTestSupport.uuid(
                "23000000-0000-0000-0000-000000000001");
        Xcon003WorkerChangedV1 worker = worker(1L, "STATUS_CHANGED");

        assertProjectionError(
                () -> consumer.consumeWorkerChanged(envelope(
                        eventId, "0".repeat(64), worker)),
                PerformanceWorkforceProjectionException.Code.EVENT_PAYLOAD_DIGEST_MISMATCH);

        Xcon005AssignmentChangedV1 assignment = new Xcon005AssignmentChangedV1(
                PerformanceWorkforceTestSupport.MEMBER_ONE.assignmentPublicId(),
                List.of("MANAGER_ASSIGNMENT"),
                EFFECTIVE_DATE,
                PerformanceWorkforceTestSupport.uuid(
                        "23000000-0000-0000-0000-000000000002"),
                "CHANGE_MANAGER",
                1L);
        assertProjectionError(
                () -> consumer.consumeAssignmentChanged(envelope(
                        eventId,
                        PerformanceWorkforceInvalidationConsumer.payloadDigest(assignment),
                        assignment)),
                PerformanceWorkforceProjectionException.Code.EVENT_ID_PAYLOAD_MISMATCH);
    }

    @Test
    void blocksGapThenReplaysOwnerSnapshotAndDeduplicatesTheEvent() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(
                                PerformanceWorkforceTestSupport.MEMBER_ONE,
                                PerformanceWorkforceTestSupport.MEMBER_TWO),
                        null));
        InMemoryPerformanceWorkforceProjectionStore store =
                new InMemoryPerformanceWorkforceProjectionStore();
        PerformanceWorkforceProjectionService service =
                new PerformanceWorkforceProjectionService(() -> port, store);
        PerformanceWorkforceInvalidationConsumer consumer =
                new PerformanceWorkforceInvalidationConsumer(store);
        service.refresh(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF);

        UUID firstEvent = PerformanceWorkforceTestSupport.uuid(
                "24000000-0000-0000-0000-000000000001");
        Xcon003WorkerChangedV1 first = worker(1L, "STATUS_CHANGED");
        PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<Xcon003WorkerChangedV1>
                firstEnvelope = envelope(
                firstEvent,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(first),
                first);
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt stale =
                consumer.consumeWorkerChanged(firstEnvelope);
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt duplicate =
                consumer.consumeWorkerChanged(firstEnvelope);

        assertThat(stale.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertThat(stale.duplicate()).isFalse();
        assertThat(duplicate.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertThat(duplicate.duplicate()).isTrue();

        Xcon003WorkerChangedV1 older = worker(1L, "CORRECTION");
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt ignored =
                consumer.consumeWorkerChanged(envelope(
                        PerformanceWorkforceTestSupport.uuid(
                                "24000000-0000-0000-0000-000000000002"),
                        PerformanceWorkforceInvalidationConsumer.payloadDigest(older),
                        older));
        assertThat(ignored.outcome()).isEqualTo(
                PerformanceWorkforceInvalidationConsumer.Outcome.IGNORED_OLDER_REVISION);

        UUID gapEvent = PerformanceWorkforceTestSupport.uuid(
                "24000000-0000-0000-0000-000000000003");
        Xcon003WorkerChangedV1 gapPayload = worker(3L, "CORRECTION");
        PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<Xcon003WorkerChangedV1>
                gapEnvelope = envelope(
                gapEvent,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(gapPayload),
                gapPayload);
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt gap =
                consumer.consumeWorkerChanged(gapEnvelope);

        assertThat(gap.outcome()).isEqualTo(
                PerformanceWorkforceInvalidationConsumer.Outcome.BLOCKED_DEPENDENCY_GAP);
        assertThat(gap.projectionState()).isEqualTo(
                PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP);
        assertThat(gap.requiredAggregateRevision()).isEqualTo(2L);
        assertThat(gap.observedAggregateRevision()).isEqualTo(3L);
        assertProjectionError(
                () -> service.preview(
                        PerformanceWorkforceTestSupport.TENANT,
                        new PerformanceWorkforceProjection.PopulationRule(
                                true, Set.of(PerformanceWorkforceTestSupport.ORGANIZATION))),
                PerformanceWorkforceProjectionException.Code.STALE_WORKFORCE_SNAPSHOT);

        PerformanceWorkforceProjection.Head replayed = service.replayOwnerSnapshot(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF,
                gapEvent);
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt replayReceipt =
                consumer.consumeWorkerChanged(gapEnvelope);

        assertThat(replayed.state()).isEqualTo(PerformanceWorkforceProjection.State.READY);
        assertThat(replayed.localRevision()).isEqualTo(2L);
        assertThat(replayReceipt.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.REPLAYED);
        assertThat(replayReceipt.duplicate()).isTrue();

        Xcon003WorkerChangedV1 next = worker(4L, "CORRECTION");
        assertThat(consumer.consumeWorkerChanged(envelope(
                PerformanceWorkforceTestSupport.uuid(
                        "24000000-0000-0000-0000-000000000004"),
                PerformanceWorkforceInvalidationConsumer.payloadDigest(next),
                next)).outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
    }

    @Test
    void quarantinesSameEventIdentityWithConflictingPayload() {
        InMemoryPerformanceWorkforceProjectionStore store = readyStore();
        PerformanceWorkforceInvalidationConsumer consumer =
                new PerformanceWorkforceInvalidationConsumer(store);
        UUID eventId = PerformanceWorkforceTestSupport.uuid(
                "25000000-0000-0000-0000-000000000001");
        Xcon003WorkerChangedV1 first = worker(1L, "STATUS_CHANGED");
        Xcon003WorkerChangedV1 conflicting = worker(1L, "CORRECTION");

        consumer.consumeWorkerChanged(envelope(
                eventId,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(first),
                first));
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt quarantined =
                consumer.consumeWorkerChanged(envelope(
                        eventId,
                        PerformanceWorkforceInvalidationConsumer.payloadDigest(conflicting),
                        conflicting));

        assertThat(quarantined.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.QUARANTINED);
        assertThat(quarantined.duplicate()).isTrue();
        assertThat(quarantined.projectionState())
                .isEqualTo(PerformanceWorkforceProjection.State.QUARANTINED);

        Xcon003WorkerChangedV1 newEvent = worker(2L, "CORRECTION");
        assertProjectionError(
                () -> consumer.consumeWorkerChanged(envelope(
                        PerformanceWorkforceTestSupport.uuid(
                                "25000000-0000-0000-0000-000000000002"),
                        PerformanceWorkforceInvalidationConsumer.payloadDigest(newEvent),
                        newEvent)),
                PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED);
    }

    @SuppressWarnings("auxiliaryclass")
    private InMemoryPerformanceWorkforceProjectionStore readyStore() {
        InMemoryPerformanceWorkforceProjectionStore store =
                new InMemoryPerformanceWorkforceProjectionStore();
        store.replaceFromOwner(PerformanceWorkforceTestSupport.snapshot(), null);
        return store;
    }

    private Xcon003WorkerChangedV1 worker(long revision, String changeType) {
        return new Xcon003WorkerChangedV1(
                changeType,
                EFFECTIVE_DATE,
                revision,
                PerformanceWorkforceTestSupport.MEMBER_ONE.workerPublicId());
    }

    private <T> PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<T> envelope(
            UUID eventId,
            String digest,
            T payload) {
        return new PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<>(
                PerformanceWorkforceTestSupport.TENANT, eventId, digest, payload);
    }

    private void assertMapped(
            PerformanceWorkforceProjectionStore.InvalidationEvent event,
            String eventType,
            PerformanceWorkforceProjectionStore.SubjectType subjectType,
            UUID subjectPublicId,
            Long revision) {
        assertThat(event).satisfies(value -> {
            assertThat(value.tenantId()).isEqualTo(PerformanceWorkforceTestSupport.TENANT);
            assertThat(value.eventType()).isEqualTo(eventType);
            assertThat(value.subjectType()).isEqualTo(subjectType);
            assertThat(value.subjectPublicId()).isEqualTo(subjectPublicId);
            assertThat(value.aggregateRevision()).isEqualTo(revision);
            assertThat(value.effectiveDate()).isEqualTo(EFFECTIVE_DATE);
            assertThat(value.payloadHash()).matches("[0-9a-f]{64}");
        });
    }

    private void assertProjectionError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            PerformanceWorkforceProjectionException.Code code) {
        assertThatThrownBy(action)
                .isInstanceOf(PerformanceWorkforceProjectionException.class)
                .extracting(error -> ((PerformanceWorkforceProjectionException) error).code())
                .isEqualTo(code);
    }
}
