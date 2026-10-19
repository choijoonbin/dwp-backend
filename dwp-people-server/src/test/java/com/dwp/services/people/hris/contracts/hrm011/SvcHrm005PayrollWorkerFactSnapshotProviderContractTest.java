package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.contracts.hris.xcon.v1.Xcon017WorkerDependentEligibilitySnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon018WorkerTaxIdentitySnapshotV1;
import com.dwp.platform.contract.AuditEventEnvelope;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SvcHrm005PayrollWorkerFactSnapshotProviderContractTest {

    @Test
    void exactHomogeneousProjectionIsSortedAndDigestBound() {
        UUID worker = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Xcon018WorkerTaxIdentitySnapshotV1 second = tax(worker,
                UUID.fromString("33333333-3333-4333-8333-333333333333"));
        Xcon018WorkerTaxIdentitySnapshotV1 first = tax(worker,
                UUID.fromString("22222222-2222-4222-8222-222222222222"));
        Hrm011WorkloadAuditPort audit = mock(Hrm011WorkloadAuditPort.class);
        Hrm011WorkloadIngress ingress = ingress((tenant, projection, asOf, limit, after) ->
                new Hrm011WorkloadIngress.PayrollSlice(
                        projection, "tax-owner-v7", List.of(second, first)), audit);

        Hrm011WorkloadIngress.SnapshotPage page = ingress.payrollWorkerFacts(
                verified(request("TAX_IDENTITY_V1")));

        assertThat(page.items()).isEqualTo(List.of(first, second));
        assertThat(page.ownerRevision()).isEqualTo("tax-owner-v7");
        assertThat(page.pageDigest()).matches("[0-9a-f]{64}");

        ArgumentCaptor<AuditEventEnvelope> captured =
                ArgumentCaptor.forClass(AuditEventEnvelope.class);
        verify(audit).record(captured.capture());
        AuditEventEnvelope event = captured.getValue();
        assertThat(event.data().keySet()).isEqualTo(Hrm011WorkloadAuditPort.DATA_FIELDS);
        assertThat(event.subject()).isEqualTo(Hrm011WorkloadIngress.SVC005_OPERATION);
        assertThat(event.data())
                .containsEntry("callerWorkloadId", Hrm011WorkloadIngress.PAYROLL_CALLER)
                .containsEntry("tenantId", 71L)
                .containsEntry("operationId", Hrm011WorkloadIngress.SVC005_OPERATION)
                .containsEntry("purposeCode", Hrm011WorkloadIngress.SVC005_PURPOSE)
                .containsEntry("projectionId", "TAX_IDENTITY_V1")
                .containsEntry("policyRevision", 7L)
                .containsEntry("correlationId",
                        "55555555-5555-4555-8555-555555555555")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("rowCount", 2)
                .containsEntry("payloadDigest", page.pageDigest());
        assertThat(event.data().toString()).doesNotContain(
                "tok_123456789012345678901234", "eligibilityFacts",
                "bankAccountToken", "taxIdentifierToken");
        assertThat(event.id()).isEqualTo(
                Hrm011WorkloadAuditPort.deterministicEventId(event.data()).toString());
    }

    @Test
    void mixedProjectionFailsClosedWithoutRawValueFallback() {
        UUID worker = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Xcon018WorkerTaxIdentitySnapshotV1 tax = tax(worker,
                UUID.fromString("22222222-2222-4222-8222-222222222222"));
        Xcon017WorkerDependentEligibilitySnapshotV1 dependent =
                new Xcon017WorkerDependentEligibilitySnapshotV1(
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        LocalDate.parse("2026-01-01"), null,
                        List.of(new Xcon017WorkerDependentEligibilitySnapshotV1.EligibilityFacts(
                                "MEDICAL", true, 1, "KR")),
                        "b".repeat(64), "CHILD",
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        1, 1, worker);
        Hrm011WorkloadAuditPort audit = mock(Hrm011WorkloadAuditPort.class);
        Hrm011WorkloadIngress ingress = ingress((tenant, projection, asOf, limit, after) ->
                new Hrm011WorkloadIngress.PayrollSlice(
                        projection, "mixed-v1", List.of(tax, dependent)), audit);

        assertThatThrownBy(() -> ingress.payrollWorkerFacts(
                verified(request("TAX_IDENTITY_V1"))))
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> assertThat(
                        ((EmploymentContractException) error).status()).isEqualTo(503));
        verifyNoInteractions(audit);
    }

    @Test
    void providerIsFailClosedUntilTypedSourceIsAdmitted() {
        var provider = new FailClosedHrm011WorkloadAdapters.PayrollFacts();
        assertThatThrownBy(() -> provider.read(
                71L, "TAX_IDENTITY_V1", Instant.now(), 10, null))
                .isInstanceOf(EmploymentContractException.class);
    }

    private Xcon018WorkerTaxIdentitySnapshotV1 tax(UUID worker, UUID snapshot) {
        return new Xcon018WorkerTaxIdentitySnapshotV1(
                LocalDate.parse("2026-01-01"), null, "a".repeat(64),
                "RESIDENT", snapshot, 1, 1,
                "tok_123456789012345678901234", "KR", "EMPLOYEE", worker);
    }

    private Hrm011WorkloadIngress ingress(
            Hrm011WorkloadIngress.PayrollWorkerFactSource source,
            Hrm011WorkloadAuditPort audit) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new Hrm011WorkloadIngress(
                source, new EmploymentContractCanonicalizer(mapper), mapper,
                audit,
                "01234567890123456789012345678901");
    }

    private Hrm011WorkloadIngress.SnapshotRequest request(String projection) {
        return new Hrm011WorkloadIngress.SnapshotRequest(
                Hrm011WorkloadIngress.SVC005_PURPOSE,
                Instant.parse("2026-10-19T03:00:00Z"), projection, 100, null);
    }

    private Hrm011WorkloadRequestContext.VerifiedRequest verified(
            Hrm011WorkloadIngress.SnapshotRequest request) {
        return new Hrm011WorkloadRequestContext.VerifiedRequest(
                "GET", Hrm011WorkloadIngressFilter.PAYROLL_FACTS_PATH,
                "fixture-fingerprint", Hrm011WorkloadIngressFilter.PAYROLL_FACTS_BINDING,
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"), 3,
                new Hrm011WorkloadIngress.TrustedWorkload(
                        71L, Hrm011WorkloadIngress.PAYROLL_CALLER,
                        Hrm011WorkloadIngress.AUDIENCE, request.purposeCode(),
                        true, true, true, true, 7),
                request);
    }
}
