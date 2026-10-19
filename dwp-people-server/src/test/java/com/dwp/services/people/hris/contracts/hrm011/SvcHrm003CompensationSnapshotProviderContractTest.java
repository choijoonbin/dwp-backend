package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.platform.contract.AuditEventEnvelope;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SvcHrm003CompensationSnapshotProviderContractTest {

    @Test
    void noAuthoritativePayGroupSourceAlwaysReturnsValidEmptyXcon002Page() {
        Hrm011WorkloadIngress.PayrollWorkerFactSource facts =
                mock(Hrm011WorkloadIngress.PayrollWorkerFactSource.class);
        Hrm011WorkloadAuditPort audit = mock(Hrm011WorkloadAuditPort.class);
        Hrm011WorkloadIngress ingress = ingress(facts, audit);
        Instant asOf = Instant.parse("2026-10-19T03:00:00Z");
        var request = new Hrm011WorkloadIngress.SnapshotRequest(
                Hrm011WorkloadIngress.SVC003_PURPOSE, asOf,
                Hrm011WorkloadIngress.COMPENSATION, 500, null);

        Hrm011WorkloadIngress.SnapshotPage page = ingress.compensationSnapshots(
                verified(request, Hrm011WorkloadIngress.PAYROLL_CALLER));

        assertThat(page.schemaVersion()).isEqualTo(1);
        assertThat(page.projection()).isEqualTo("COMPENSATION_BASIS_V1");
        assertThat(page.ownerRevision()).isEqualTo("pay-group-source-unadmitted:v1");
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.pageDigest()).matches("[0-9a-f]{64}");
        verifyNoInteractions(facts);

        ArgumentCaptor<AuditEventEnvelope> captured =
                ArgumentCaptor.forClass(AuditEventEnvelope.class);
        verify(audit).record(captured.capture());
        AuditEventEnvelope event = captured.getValue();
        assertThat(event.data().keySet()).isEqualTo(Hrm011WorkloadAuditPort.DATA_FIELDS);
        assertThat(event.subject()).isEqualTo(Hrm011WorkloadIngress.SVC003_OPERATION);
        assertThat(event.tenantId()).isEqualTo("71");
        assertThat(event.correlationId())
                .isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(event.data())
                .containsEntry("callerWorkloadId", Hrm011WorkloadIngress.PAYROLL_CALLER)
                .containsEntry("tenantId", 71L)
                .containsEntry("operationId", Hrm011WorkloadIngress.SVC003_OPERATION)
                .containsEntry("purposeCode", Hrm011WorkloadIngress.SVC003_PURPOSE)
                .containsEntry("projectionId", Hrm011WorkloadIngress.COMPENSATION)
                .containsEntry("policyRevision", 7L)
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("rowCount", 0)
                .containsEntry("payloadDigest", page.pageDigest());
        assertThat(event.id()).isEqualTo(
                Hrm011WorkloadAuditPort.deterministicEventId(event.data()).toString());
    }

    @Test
    void onlyAuthenticatedPayrollWorkloadIsAccepted() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm011WorkloadAuditPort audit = mock(Hrm011WorkloadAuditPort.class);
        Hrm011WorkloadIngress ingress = new Hrm011WorkloadIngress(
                mock(Hrm011WorkloadIngress.PayrollWorkerFactSource.class),
                new EmploymentContractCanonicalizer(mapper), mapper, audit,
                "01234567890123456789012345678901");
        var request = new Hrm011WorkloadIngress.SnapshotRequest(
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Instant.parse("2026-10-19T03:00:00Z"),
                Hrm011WorkloadIngress.COMPENSATION, 1, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ingress.compensationSnapshots(verified(request, "another-service")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void auditPersistenceFailureFailsClosedBeforeReturningPage() {
        Hrm011WorkloadAuditPort audit = mock(Hrm011WorkloadAuditPort.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).record(any(AuditEventEnvelope.class));
        Hrm011WorkloadIngress ingress = ingress(
                mock(Hrm011WorkloadIngress.PayrollWorkerFactSource.class), audit);

        var request = new Hrm011WorkloadIngress.SnapshotRequest(
                        Hrm011WorkloadIngress.SVC003_PURPOSE,
                        Instant.parse("2026-10-19T03:00:00Z"),
                        Hrm011WorkloadIngress.COMPENSATION, 500, null);
        assertThatThrownBy(() -> ingress.compensationSnapshots(verified(
                request, Hrm011WorkloadIngress.PAYROLL_CALLER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    private Hrm011WorkloadIngress ingress(
            Hrm011WorkloadIngress.PayrollWorkerFactSource facts,
            Hrm011WorkloadAuditPort audit) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new Hrm011WorkloadIngress(
                facts, new EmploymentContractCanonicalizer(mapper), mapper,
                audit,
                "01234567890123456789012345678901");
    }

    private Hrm011WorkloadRequestContext.VerifiedRequest verified(
            Hrm011WorkloadIngress.SnapshotRequest request, String caller) {
        return new Hrm011WorkloadRequestContext.VerifiedRequest(
                "GET", Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                "fixture-fingerprint", Hrm011WorkloadIngressFilter.COMPENSATION_BINDING,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("11111111-1111-4111-8111-111111111111"), 3,
                new Hrm011WorkloadIngress.TrustedWorkload(
                        71L, caller, Hrm011WorkloadIngress.AUDIENCE,
                        request.purposeCode(), true, true, true, true, 7),
                request);
    }
}
