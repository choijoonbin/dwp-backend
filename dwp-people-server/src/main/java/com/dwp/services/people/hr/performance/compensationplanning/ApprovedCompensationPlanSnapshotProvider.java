package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.audit.AuditEvent;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owner-side implementation of SVC-PEP-PER-001. Transport authentication and
 * JWT verification remain Central-owned; this port still validates every
 * derived claim before disclosing the atomic XCON-020 snapshot.
 */
@Component
public class ApprovedCompensationPlanSnapshotProvider {

    public static final String CALLER_WORKLOAD = "dwp-payroll-server";
    public static final String AUDIENCE = "dwp-people-server";
    public static final String PURPOSE =
            "HRIS_APPROVED_COMPENSATION_PLAN_SNAPSHOT_REFETCH";
    public static final String OPERATION = "getApprovedCompensationPlanSnapshot";
    public static final String PROJECTION = "XCON-020";

    private final CompensationPlanningRepository repository;
    private final CompensationPlanningCanonicalizer canonicalizer;
    private final AuditOutboxRecorder audit;

    public ApprovedCompensationPlanSnapshotProvider(
            CompensationPlanningRepository repository,
            CompensationPlanningCanonicalizer canonicalizer,
            AuditOutboxRecorder audit) {
        this.repository = Objects.requireNonNull(repository);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional
    public Xcon020ApprovedCompensationPlanSnapshotV1 get(
            UUID snapshotId,
            ServicePepEvidence evidence) {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        requireTrusted(evidence);
        repository.bindTenant(evidence.tenantId());
        CompensationPlanningDtos.ApprovedSnapshot snapshot = repository
                .snapshot(evidence.tenantId(), snapshotId)
                .orElseThrow(ApprovedCompensationPlanSnapshotProvider::notAvailable);
        try {
            canonicalizer.verifySnapshot(snapshot);
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The approved compensation snapshot is not available.", exception);
        }
        Xcon020ApprovedCompensationPlanSnapshotV1 payload = toContract(snapshot);
        audit.record(AuditEvent.builder()
                .tenantId(evidence.tenantId())
                .category("DATA_ACCESS")
                .action(OPERATION)
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(75)
                .actorType("SERVICE")
                .actorId(evidence.callerWorkloadId())
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-compensation")
                .targetType("APPROVED_COMPENSATION_PLAN_SNAPSHOT")
                .targetId(snapshotId.toString())
                .correlationId(evidence.correlationId().toString())
                .policyId("SVC-PEP-PER-001")
                .policyDecision("ALLOW")
                .metadata(java.util.Map.of(
                        "callerWorkloadId", evidence.callerWorkloadId(),
                        "operationId", OPERATION,
                        "purposeCode", evidence.purposeCode(),
                        "projectionId", evidence.projectionId(),
                        "policyRevision", evidence.policyRevision(),
                        "rowCount", snapshot.lineCount(),
                        "payloadDigest", snapshot.payloadDigest()))
                .retentionClass("EXTENDED")
                .build());
        return payload;
    }

    static Xcon020ApprovedCompensationPlanSnapshotV1 toContract(
            CompensationPlanningDtos.ApprovedSnapshot snapshot) {
        List<ApprovedCompensationPlanLine> lines = snapshot.lines().stream()
                .map(line -> new ApprovedCompensationPlanLine(
                        line.lineId(), line.lineSequence(), line.workerId(),
                        line.assignmentId(), line.componentCode(), line.currency(),
                        PostedMoney.decimal(line.approvedAmount()), line.effectiveFrom(),
                        line.effectiveTo(), line.lineDigest()))
                .toList();
        return new Xcon020ApprovedCompensationPlanSnapshotV1(
                snapshot.approvalReceiptId(), snapshot.cycleId(),
                snapshot.effectiveFrom(), snapshot.effectiveTo(), snapshot.lineCount(),
                lines, snapshot.payloadDigest(), snapshot.planId(), snapshot.snapshotId(),
                snapshot.snapshotRevision(), snapshot.sourceVersion());
    }

    private static void requireTrusted(ServicePepEvidence evidence) {
        if (evidence == null
                || !evidence.mutualTlsAuthenticated()
                || !CALLER_WORKLOAD.equals(evidence.callerWorkloadId())
                || !AUDIENCE.equals(evidence.audience())
                || !PURPOSE.equals(evidence.purposeCode())
                || !PROJECTION.equals(evidence.projectionId())
                || evidence.tenantId() <= 0
                || evidence.targetTenantId() != evidence.tenantId()
                || evidence.tenantGrantRevision() <= 0
                || evidence.policyRevision() <= 0
                || evidence.correlationId() == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Approved compensation snapshot access was denied.");
        }
    }

    private static BaseException notAvailable() {
        return new BaseException(
                ErrorCode.RESOURCE_NOT_AVAILABLE,
                "The approved compensation snapshot is not available.");
    }

    public record ServicePepEvidence(
            boolean mutualTlsAuthenticated,
            String callerWorkloadId,
            String audience,
            long tenantId,
            long targetTenantId,
            long tenantGrantRevision,
            long policyRevision,
            String purposeCode,
            String projectionId,
            UUID correlationId) {
    }
}
