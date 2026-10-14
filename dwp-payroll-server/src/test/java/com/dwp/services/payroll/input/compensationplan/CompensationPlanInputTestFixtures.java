package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CompensationPlanInputTestFixtures {

    static final Instant NOW = Instant.parse("2026-10-14T04:00:00Z");
    static final UUID CYCLE = UUID.fromString("10000000-0000-4000-8000-000000000016");
    static final UUID SNAPSHOT_1 = UUID.fromString("20000000-0000-4000-8000-000000000016");
    static final UUID APPROVAL = UUID.fromString("30000000-0000-4000-8000-000000000016");
    static final UUID CORRELATION = UUID.fromString("40000000-0000-4000-8000-000000000016");
    static final UUID WORKER = UUID.fromString("50000000-0000-4000-8000-000000000016");
    static final UUID ASSIGNMENT = UUID.fromString("60000000-0000-4000-8000-000000000016");

    private CompensationPlanInputTestFixtures() {
    }

    static Fixture fixture(
            ObjectMapper mapper,
            CompensationPlanInputCanonicalizer canonicalizer,
            long tenantId,
            UUID sourceEventId,
            UUID snapshotId,
            long snapshotRevision,
            long sourceVersion,
            long aggregateVersion,
            UUID lineId,
            BigDecimal amount) {
        LocalDate effectiveFrom = LocalDate.parse("2026-11-01");
        LocalDate effectiveTo = LocalDate.parse("2027-01-01");
        ApprovedCompensationPlanLine provisionalLine =
                new ApprovedCompensationPlanLine(
                        lineId, 1, WORKER, ASSIGNMENT, "BASE.SALARY", "KRW",
                        amount, effectiveFrom, effectiveTo, "0".repeat(64));
        String lineDigest = canonicalizer.lineDigest(provisionalLine);
        ApprovedCompensationPlanLine line =
                new ApprovedCompensationPlanLine(
                        lineId, 1, WORKER, ASSIGNMENT, "BASE.SALARY", "KRW",
                        amount, effectiveFrom, effectiveTo, lineDigest);
        Xcon020ApprovedCompensationPlanSnapshotV1 provisionalSnapshot =
                new Xcon020ApprovedCompensationPlanSnapshotV1(
                        APPROVAL, CYCLE, effectiveFrom, effectiveTo, 1,
                        List.of(line), "0".repeat(64), CYCLE, snapshotId,
                        snapshotRevision, sourceVersion);
        String payloadDigest = canonicalizer.payloadDigest(provisionalSnapshot);
        Xcon020ApprovedCompensationPlanSnapshotV1 snapshot =
                new Xcon020ApprovedCompensationPlanSnapshotV1(
                        APPROVAL, CYCLE, effectiveFrom, effectiveTo, 1,
                        List.of(line), payloadDigest, CYCLE, snapshotId,
                        snapshotRevision, sourceVersion);
        Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event =
                new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                        CYCLE, aggregateVersion, APPROVAL, snapshotRevision,
                        CORRELATION, CYCLE, effectiveFrom, "APPROVED", 1, NOW,
                        payloadDigest, CYCLE, snapshotId, snapshotRevision,
                        sourceVersion, "PUBLISHED");
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                "1.0", sourceEventId,
                CompensationPlanInputContracts.SOURCE,
                CompensationPlanInputContracts.TOPIC, 2, NOW,
                "compensation-cycle/" + CYCLE, tenantId,
                CompensationPlanInputContracts.AGGREGATE_TYPE,
                CYCLE.toString(), aggregateVersion, CORRELATION.toString(),
                UUID.randomUUID().toString(), null,
                mapper.valueToTree(event), Map.of());
        return new Fixture(envelope, event, snapshot);
    }

    record Fixture(
            DomainEventEnvelope envelope,
            Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event,
            Xcon020ApprovedCompensationPlanSnapshotV1 snapshot) {
    }
}
