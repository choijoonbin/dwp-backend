package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.event.DomainEventEnvelope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed PAY-016 vocabulary. No type in this package releases payroll calculation. */
public final class CompensationPlanInputContracts {

    public static final String TOPIC =
            "dwp.hris.modern.approved-compensation-plan-snapshot-published.v2";
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";
    public static final String SOURCE =
            "urn:dwp:people:performance:compensation-planning";
    public static final String AGGREGATE_TYPE = "COMPENSATION_CYCLE";
    public static final String OWNER_REFETCH_PURPOSE =
            "HRIS_APPROVED_COMPENSATION_PLAN_SNAPSHOT_REFETCH";
    public static final String OWNER_REFETCH_PROJECTION = "XCON-020";
    public static final String REPLAY_AUTHORITY =
            "DATA.HRIS_PAYROLL_INPUT:UPDATE";
    public static final String MATERIALIZED_EVENT =
            "PayrollCompensationPlanInputMaterialized";

    private CompensationPlanInputContracts() {
    }

    public enum Outcome {
        APPLIED,
        REPLAYED,
        QUARANTINED
    }

    public record ConsumptionResult(
            Outcome outcome,
            UUID sourceEventId,
            UUID receiptId,
            UUID snapshotId,
            long snapshotRevision,
            int lineCount,
            String failureCode) {

        public ConsumptionResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(sourceEventId, "sourceEventId");
            if (outcome == Outcome.QUARANTINED) {
                if (failureCode == null || failureCode.isBlank()) {
                    throw new IllegalArgumentException(
                            "quarantine result requires failureCode");
                }
            } else {
                Objects.requireNonNull(receiptId, "receiptId");
                Objects.requireNonNull(snapshotId, "snapshotId");
                if (snapshotRevision <= 0 || lineCount <= 0 || failureCode != null) {
                    throw new IllegalArgumentException("applied receipt is incomplete");
                }
            }
        }
    }

    public record Publication(
            DomainEventEnvelope envelope,
            Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event,
            String envelopeDigest,
            String envelopeJson) {

        public Publication {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(event, "event");
            requireDigest(envelopeDigest, "envelopeDigest");
            if (envelopeJson == null || envelopeJson.isBlank()) {
                throw new IllegalArgumentException("envelopeJson is required");
            }
        }
    }

    public record MaterializedSnapshot(
            Xcon020ApprovedCompensationPlanSnapshotV1 snapshot,
            List<Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine>
                    lines) {

        public MaterializedSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            lines = List.copyOf(lines);
            if (lines.isEmpty() || snapshot.lineCount() != lines.size()) {
                throw new IllegalArgumentException("materialized snapshot cardinality differs");
            }
        }
    }

    public record Receipt(
            long tenantId,
            UUID receiptId,
            UUID sourceEventId,
            UUID aggregateId,
            long aggregateSequence,
            UUID snapshotId,
            long snapshotRevision,
            String payloadDigest,
            int lineCount,
            UUID outboxEventId,
            Instant appliedAt) {

        public Receipt {
            if (tenantId <= 0 || aggregateSequence <= 0 || snapshotRevision <= 0
                    || lineCount <= 0) {
                throw new IllegalArgumentException("receipt positive fields are invalid");
            }
            Objects.requireNonNull(receiptId, "receiptId");
            Objects.requireNonNull(sourceEventId, "sourceEventId");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            requireDigest(payloadDigest, "payloadDigest");
            Objects.requireNonNull(outboxEventId, "outboxEventId");
            Objects.requireNonNull(appliedAt, "appliedAt");
        }

        ConsumptionResult result(Outcome outcome) {
            return new ConsumptionResult(
                    outcome, sourceEventId, receiptId, snapshotId,
                    snapshotRevision, lineCount, null);
        }
    }

    public record ReplayAuthority(
            String actorType,
            String actorId,
            Set<String> authorities,
            String reason) {

        public ReplayAuthority {
            if (!Set.of("TENANT_ADMIN", "PLATFORM_ADMIN").contains(actorType)) {
                throw new SecurityException("replay requires an exact administrative actor type");
            }
            if (actorId == null || actorId.isBlank()
                    || reason == null || reason.isBlank()
                    || reason.length() > 800
                    || authorities == null
                    || !authorities.contains(REPLAY_AUTHORITY)) {
                throw new SecurityException("replay authority evidence is incomplete");
            }
            authorities = Set.copyOf(authorities);
        }
    }

    static void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
    }
}
