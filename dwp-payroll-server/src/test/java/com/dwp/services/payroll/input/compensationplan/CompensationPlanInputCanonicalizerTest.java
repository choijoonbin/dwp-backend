package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputTestFixtures.SNAPSHOT_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompensationPlanInputCanonicalizerTest {

    private JsonMapper mapper;
    private CompensationPlanInputCanonicalizer canonicalizer;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().findAndAddModules().build();
        canonicalizer = new CompensationPlanInputCanonicalizer(mapper);
    }

    @Test
    void verifiesExactEnvelopeLineAndPayloadDigests() {
        var fixture = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));

        var publication = canonicalizer.publication(fixture.envelope());
        var materialized = canonicalizer.verify(publication, fixture.snapshot());

        assertThat(materialized.lines()).hasSize(1);
        assertThat(materialized.snapshot().payloadDigest())
                .isEqualTo(fixture.event().payloadDigest());
    }

    @Test
    void rejectsDigestDriftAndNonConsecutiveCausalVersion() {
        var fixture = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        ApprovedCompensationPlanLine original = fixture.snapshot().lines().getFirst();
        ApprovedCompensationPlanLine changed = new ApprovedCompensationPlanLine(
                original.lineId(), original.lineSequence(), original.workerId(),
                original.assignmentId(), original.componentCode(), original.currency(),
                new BigDecimal("1300000"), original.effectiveFrom(),
                original.effectiveTo(), original.lineDigest());
        Xcon020ApprovedCompensationPlanSnapshotV1 drifted =
                new Xcon020ApprovedCompensationPlanSnapshotV1(
                        fixture.snapshot().approvalReceiptId(), fixture.snapshot().cycleId(),
                        fixture.snapshot().effectiveFrom(), fixture.snapshot().effectiveTo(),
                        1, List.of(changed), fixture.snapshot().payloadDigest(),
                        fixture.snapshot().planId(), fixture.snapshot().snapshotId(),
                        fixture.snapshot().snapshotRevision(),
                        fixture.snapshot().sourceVersion());

        assertThatThrownBy(() -> canonicalizer.verify(
                canonicalizer.publication(fixture.envelope()), drifted))
                .isInstanceOf(CompensationPlanInputCanonicalizer
                        .InvalidCompensationPlanInputException.class)
                .hasMessageContaining("line digest");

        var invalidEnvelope = new com.dwp.core.event.DomainEventEnvelope(
                fixture.envelope().specVersion(), fixture.envelope().id(),
                fixture.envelope().source(), fixture.envelope().type(),
                fixture.envelope().schemaVersion(), fixture.envelope().time(),
                fixture.envelope().subject(), fixture.envelope().tenantId(),
                fixture.envelope().aggregateType(), fixture.envelope().aggregateId(),
                3, fixture.envelope().correlationId(), fixture.envelope().causationId(),
                fixture.envelope().traceParent(), fixture.envelope().data(),
                fixture.envelope().extensions());
        assertThatThrownBy(() -> canonicalizer.publication(invalidEnvelope))
                .isInstanceOf(CompensationPlanInputCanonicalizer
                        .InvalidCompensationPlanInputException.class)
                .hasMessageContaining("predecessor");
    }

    @Test
    void acceptsSameWorkerComponentAcrossDifferentAssignments() {
        var fixture = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        ApprovedCompensationPlanLine first = fixture.snapshot().lines().getFirst();
        ApprovedCompensationPlanLine provisionalSecond = new ApprovedCompensationPlanLine(
                UUID.randomUUID(), 2, first.workerId(), UUID.randomUUID(),
                first.componentCode(), first.currency(), new BigDecimal("900000"),
                first.effectiveFrom(), first.effectiveTo(), "0".repeat(64));
        ApprovedCompensationPlanLine second = new ApprovedCompensationPlanLine(
                provisionalSecond.lineId(), provisionalSecond.lineSequence(),
                provisionalSecond.workerId(), provisionalSecond.assignmentId(),
                provisionalSecond.componentCode(), provisionalSecond.currency(),
                provisionalSecond.approvedAmount(), provisionalSecond.effectiveFrom(),
                provisionalSecond.effectiveTo(),
                canonicalizer.lineDigest(provisionalSecond));
        List<ApprovedCompensationPlanLine> lines = List.of(first, second);
        Xcon020ApprovedCompensationPlanSnapshotV1 provisional =
                new Xcon020ApprovedCompensationPlanSnapshotV1(
                        fixture.snapshot().approvalReceiptId(), fixture.snapshot().cycleId(),
                        fixture.snapshot().effectiveFrom(), fixture.snapshot().effectiveTo(),
                        lines.size(), lines, "0".repeat(64), fixture.snapshot().planId(),
                        fixture.snapshot().snapshotId(), fixture.snapshot().snapshotRevision(),
                        fixture.snapshot().sourceVersion());
        String payloadDigest = canonicalizer.payloadDigest(provisional);
        Xcon020ApprovedCompensationPlanSnapshotV1 snapshot =
                new Xcon020ApprovedCompensationPlanSnapshotV1(
                        provisional.approvalReceiptId(), provisional.cycleId(),
                        provisional.effectiveFrom(), provisional.effectiveTo(), lines.size(),
                        lines, payloadDigest, provisional.planId(), provisional.snapshotId(),
                        provisional.snapshotRevision(), provisional.sourceVersion());
        Xcon021ApprovedCompensationPlanSnapshotPublishedV2 originalEvent = fixture.event();
        Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event =
                new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                        originalEvent.aggregateId(), originalEvent.aggregateVersion(),
                        originalEvent.approvalReceiptId(), originalEvent.approvalRevision(),
                        originalEvent.correlationId(), originalEvent.cycleId(),
                        originalEvent.effectiveDate(), originalEvent.fromState(), lines.size(),
                        originalEvent.occurredAt(), payloadDigest, originalEvent.planId(),
                        originalEvent.snapshotId(), originalEvent.snapshotRevision(),
                        originalEvent.sourceVersion(), originalEvent.toState());
        DomainEventEnvelope originalEnvelope = fixture.envelope();
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                originalEnvelope.specVersion(), originalEnvelope.id(),
                originalEnvelope.source(), originalEnvelope.type(),
                originalEnvelope.schemaVersion(), originalEnvelope.time(),
                originalEnvelope.subject(), originalEnvelope.tenantId(),
                originalEnvelope.aggregateType(), originalEnvelope.aggregateId(),
                originalEnvelope.aggregateSequence(), originalEnvelope.correlationId(),
                originalEnvelope.causationId(), originalEnvelope.traceParent(),
                mapper.valueToTree(event), originalEnvelope.extensions());

        var result = canonicalizer.verify(canonicalizer.publication(envelope), snapshot);

        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines()).extracting(ApprovedCompensationPlanLine::assignmentId)
                .doesNotHaveDuplicates();
    }
}
