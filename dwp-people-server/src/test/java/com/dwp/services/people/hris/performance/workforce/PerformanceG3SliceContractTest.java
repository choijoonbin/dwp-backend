package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon006OrganizationChangedV1;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Exact executable acceptance contract for BASE-TFR-PER-016. */
@SuppressWarnings("auxiliaryclass")
class PerformanceG3SliceContractTest {

    @Test
    void baseTfrPer016SealsGoldenPopulationInvalidationAndOwnerReplay() {
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
        PerformanceWorkforceProjection.PopulationRule activeOrganization =
                new PerformanceWorkforceProjection.PopulationRule(
                        true, Set.of(PerformanceWorkforceTestSupport.ORGANIZATION));

        PerformanceWorkforceProjection.Head initial = service.refresh(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF);
        PerformanceWorkforceProjection.Preview frozenCandidate = service.preview(
                PerformanceWorkforceTestSupport.TENANT, activeOrganization);

        assertThat(initial.snapshot().ownerRevision()).isEqualTo(42L);
        assertThat(frozenCandidate.members()).containsExactly(
                member(PerformanceWorkforceTestSupport.MEMBER_ONE),
                member(PerformanceWorkforceTestSupport.MEMBER_TWO));
        assertThat(frozenCandidate.participantCount()).isEqualTo(2);
        assertThat(frozenCandidate.reviewerAssignmentCount()).isEqualTo(2);

        UUID organizationEvent = UUID.fromString(
                "30000000-0000-0000-0000-000000000001");
        Xcon006OrganizationChangedV1 organizationChanged =
                new Xcon006OrganizationChangedV1(
                        List.of(PerformanceWorkforceTestSupport.ORGANIZATION),
                        LocalDate.parse("2030-01-02"),
                        UUID.fromString("31000000-0000-0000-0000-000000000001"),
                        "a".repeat(64));
        var organizationEnvelope = new PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<>(
                PerformanceWorkforceTestSupport.TENANT,
                organizationEvent,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(organizationChanged),
                organizationChanged);
        assertThat(consumer.consumeOrganizationChanged(organizationEnvelope).outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertThatThrownBy(() -> service.preview(
                PerformanceWorkforceTestSupport.TENANT, activeOrganization))
                .isInstanceOf(PerformanceWorkforceProjectionException.class)
                .extracting(error -> ((PerformanceWorkforceProjectionException) error).code())
                .isEqualTo(
                        PerformanceWorkforceProjectionException.Code.STALE_WORKFORCE_SNAPSHOT);
        assertThat(frozenCandidate.members()).hasSize(2);

        PerformanceWorkforceProjection.Head replayed = service.replayOwnerSnapshot(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF,
                organizationEvent);
        assertThat(replayed.state()).isEqualTo(PerformanceWorkforceProjection.State.READY);
        assertThat(replayed.localRevision()).isEqualTo(2L);

        UUID workerEvent = UUID.fromString("32000000-0000-0000-0000-000000000001");
        Xcon003WorkerChangedV1 workerRevisionOne = new Xcon003WorkerChangedV1(
                "STATUS_CHANGED",
                LocalDate.parse("2030-01-03"),
                1L,
                PerformanceWorkforceTestSupport.MEMBER_ONE.workerPublicId());
        consumer.consumeWorkerChanged(new PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<>(
                PerformanceWorkforceTestSupport.TENANT,
                workerEvent,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(workerRevisionOne),
                workerRevisionOne));

        UUID gapEvent = UUID.fromString("32000000-0000-0000-0000-000000000003");
        Xcon003WorkerChangedV1 workerRevisionThree = new Xcon003WorkerChangedV1(
                "CORRECTION",
                LocalDate.parse("2030-01-04"),
                3L,
                PerformanceWorkforceTestSupport.MEMBER_ONE.workerPublicId());
        var gap = consumer.consumeWorkerChanged(
                new PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<>(
                        PerformanceWorkforceTestSupport.TENANT,
                        gapEvent,
                        PerformanceWorkforceInvalidationConsumer.payloadDigest(workerRevisionThree),
                        workerRevisionThree));

        assertThat(gap.outcome()).isEqualTo(
                PerformanceWorkforceInvalidationConsumer.Outcome.BLOCKED_DEPENDENCY_GAP);
        assertThat(gap.requiredAggregateRevision()).isEqualTo(2L);
        assertThat(gap.observedAggregateRevision()).isEqualTo(3L);
        assertThat(service.replayOwnerSnapshot(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF,
                gapEvent).state()).isEqualTo(PerformanceWorkforceProjection.State.READY);
    }

    private static PerformanceWorkforceProjection.Member member(
            com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1 source) {
        return new PerformanceWorkforceProjection.Member(
                source.workerPublicId(),
                source.assignmentPublicId(),
                source.status(),
                source.organizationPublicId(),
                source.managerAssignmentPublicId(),
                source.jobProfilePublicId(),
                source.gradePublicId());
    }
}
