package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.audit.AuditEvent;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovedCompensationPlanSnapshotProviderContractTest {

    private static final long TENANT = 71L;
    private static final UUID CYCLE =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SNAPSHOT =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID APPROVAL =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID LINE =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID PROPOSAL =
            UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final UUID WORKER =
            UUID.fromString("10000000-0000-4000-8000-000000000006");
    private static final UUID ASSIGNMENT =
            UUID.fromString("10000000-0000-4000-8000-000000000007");
    private static final UUID CORRELATION =
            UUID.fromString("10000000-0000-4000-8000-000000000008");
    private static final LocalDate EFFECTIVE = LocalDate.parse("2027-01-01");

    private final CompensationPlanningRepository repository =
            mock(CompensationPlanningRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final CompensationPlanningCanonicalizer canonicalizer =
            new CompensationPlanningCanonicalizer(new ObjectMapper());
    private final ApprovedCompensationPlanSnapshotProvider provider =
            new ApprovedCompensationPlanSnapshotProvider(
                    repository, canonicalizer, audit);

    @Test
    void validServicePepEvidenceReturnsTheExactVerifiedSnapshotAndAuditsIt() {
        CompensationPlanningDtos.ApprovedSnapshot snapshot = snapshot();
        when(repository.snapshot(TENANT, SNAPSHOT)).thenReturn(Optional.of(snapshot));

        Xcon020ApprovedCompensationPlanSnapshotV1 result =
                provider.get(SNAPSHOT, evidence());

        assertThat(result.approvalReceiptId()).isEqualTo(APPROVAL);
        assertThat(result.cycleId()).isEqualTo(CYCLE);
        assertThat(result.planId()).isEqualTo(CYCLE);
        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT);
        assertThat(result.lineCount()).isOne();
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineId()).isEqualTo(LINE);
            assertThat(line.lineSequence()).isOne();
            assertThat(line.approvedAmount()).isEqualByComparingTo("2500.25");
        });
        verify(repository).bindTenant(TENANT);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(event.getValue().category()).isEqualTo("DATA_ACCESS");
        assertThat(event.getValue().action()).isEqualTo(
                ApprovedCompensationPlanSnapshotProvider.OPERATION);
        assertThat(event.getValue().policyId()).isEqualTo("SVC-PEP-PER-001");
        assertThat(event.getValue().metadata()).containsEntry("rowCount", 1);
        assertThat(event.getValue().metadata())
                .containsEntry("payloadDigest", snapshot.payloadDigest());
    }

    @Test
    void rejectsUntrustedEvidenceBeforeTouchingTheRepository() {
        List<ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence> invalid = List.of(
                copy(false, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        1L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "other-workload",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        1L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "dwp-payroll-server", "other-audience", TENANT, TENANT,
                        1L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT + 1,
                        1L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        0L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        1L, 0L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        1L, 1L, "OTHER_PURPOSE",
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION),
                copy(true, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        1L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        "XCON-999", CORRELATION),
                copy(true, "dwp-payroll-server",
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                        1L, 1L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION, null));

        for (var value : invalid) {
            assertCode(() -> provider.get(SNAPSHOT, value), ErrorCode.FORBIDDEN);
        }
        verifyNoInteractions(repository, audit);
    }

    @Test
    void missingOrDigestTamperedSnapshotsRemainOpaqueAndUnaudited() {
        when(repository.snapshot(TENANT, SNAPSHOT)).thenReturn(Optional.empty());
        assertCode(() -> provider.get(SNAPSHOT, evidence()),
                ErrorCode.RESOURCE_NOT_AVAILABLE);

        CompensationPlanningDtos.ApprovedSnapshot valid = snapshot();
        CompensationPlanningDtos.ApprovedSnapshot tampered = new
                CompensationPlanningDtos.ApprovedSnapshot(
                valid.approvalReceiptId(), valid.approvalRevision(), valid.cycleId(),
                valid.effectiveFrom(), valid.effectiveTo(), valid.lineCount(),
                valid.lines(), "f".repeat(64), valid.planId(), valid.snapshotId(),
                valid.snapshotRevision(), valid.sourceVersion(), valid.aggregateVersion(),
                valid.correlationId(), valid.occurredAt());
        when(repository.snapshot(TENANT, SNAPSHOT)).thenReturn(Optional.of(tampered));
        assertCode(() -> provider.get(SNAPSHOT, evidence()),
                ErrorCode.RESOURCE_NOT_AVAILABLE);
        verify(audit, never()).record(any());
    }

    @Test
    void exactRefetchRejectsRevisionOrDigestDriftBeforeAuditingDisclosure() {
        CompensationPlanningDtos.ApprovedSnapshot snapshot = snapshot();
        when(repository.snapshot(TENANT, SNAPSHOT)).thenReturn(Optional.of(snapshot));

        assertCode(() -> provider.getExact(
                        SNAPSHOT,
                        snapshot.snapshotRevision() + 1,
                        snapshot.payloadDigest(),
                        evidence()),
                ErrorCode.RESOURCE_NOT_AVAILABLE);
        assertCode(() -> provider.getExact(
                        SNAPSHOT,
                        snapshot.snapshotRevision(),
                        "f".repeat(64),
                        evidence()),
                ErrorCode.RESOURCE_NOT_AVAILABLE);

        verify(audit, never()).record(any());
    }

    private CompensationPlanningDtos.ApprovedSnapshot snapshot() {
        String lineDigest = canonicalizer.lineDigest(
                LINE, 1, WORKER, ASSIGNMENT, "BASE", "USD", "2500.25",
                EFFECTIVE, null);
        CompensationPlanningDtos.SnapshotLine line = new
                CompensationPlanningDtos.SnapshotLine(
                LINE, 1, PROPOSAL, WORKER, ASSIGNMENT, "BASE", "USD", "2500.25",
                EFFECTIVE, null, lineDigest);
        String digest = canonicalizer.payloadDigest(
                APPROVAL, CYCLE, EFFECTIVE, null, List.of(line), SNAPSHOT, 1L, 7L);
        return new CompensationPlanningDtos.ApprovedSnapshot(
                APPROVAL, 7L, CYCLE, EFFECTIVE, null, 1, List.of(line), digest,
                CYCLE, SNAPSHOT, 1L, 7L, 8L, CORRELATION,
                Instant.parse("2027-01-02T00:00:00Z"));
    }

    private static ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence evidence() {
        return copy(true, ApprovedCompensationPlanSnapshotProvider.CALLER_WORKLOAD,
                ApprovedCompensationPlanSnapshotProvider.AUDIENCE, TENANT, TENANT,
                3L, 9L, ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                ApprovedCompensationPlanSnapshotProvider.PROJECTION, CORRELATION);
    }

    private static ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence copy(
            boolean mutualTls,
            String workload,
            String audience,
            long tenant,
            long targetTenant,
            long grantRevision,
            long policyRevision,
            String purpose,
            String projection,
            UUID correlation) {
        return new ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence(
                mutualTls, workload, audience, tenant, targetTenant, grantRevision,
                policyRevision, purpose, projection, correlation);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(code);
    }
}
