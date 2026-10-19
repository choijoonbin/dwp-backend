package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmploymentContractTemplateQueryPortContractTest {

    @Test
    void productionAdapterFailsClosedWithoutProviderClaim() {
        var adapter = new FailClosedEmploymentContractTemplateQueryAdapter();
        var query = query();

        assertThatThrownBy(() -> adapter.requirePublished(query))
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> {
                    EmploymentContractException value =
                            (EmploymentContractException) error;
                    assertThat(value.status()).isEqualTo(503);
                    assertThat(value.retryable()).isTrue();
                });
    }

    @Test
    void typedPortPreservesExactPdx015StringSchemaVersionAndQueryBinding() {
        EmploymentContractTemplateQueryPort.TemplateQuery expected = query();
        EmploymentContractTemplateSnapshotV1 snapshot = snapshot();
        EmploymentContractTemplateQueryPort port = actual -> {
            assertThat(actual).isEqualTo(expected);
            return snapshot;
        };

        EmploymentContractTemplateSnapshotV1 actual = port.requirePublished(expected);

        assertThat(actual).isSameAs(snapshot);
        assertThat(actual.contentSchemaVersion()).isEqualTo("employment-contract.v12");
        assertThat(actual.tenantId()).isEqualTo(71L);
        assertThat(actual.state()).isEqualTo("PUBLISHED");
    }

    @Test
    void pdx015RejectsNumericLikeOrMalformedSchemaVersions() {
        assertThatThrownBy(() -> snapshot("12"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot("employment-contract.V1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyProvableGlobalLegalEntityAndRelationshipSubjectScopesAreAccepted() {
        EmploymentContractTemplateQueryPort.TemplateQuery query = query();
        EmploymentContractTemplateQueryPort.requireTrustedScope(
                query, snapshot("GLOBAL", null));
        EmploymentContractTemplateQueryPort.requireTrustedScope(
                query, snapshot("LEGAL_ENTITY", query.legalEmployerPublicId()));
        EmploymentContractTemplateQueryPort.requireTrustedScope(
                query, snapshot("SUBJECT", query.relationshipPublicId()));

        assertThatThrownBy(() -> EmploymentContractTemplateQueryPort.requireTrustedScope(
                query, snapshot("LEGAL_ENTITY", UUID.randomUUID())))
                .isInstanceOf(EmploymentContractException.class);
        assertThatThrownBy(() -> EmploymentContractTemplateQueryPort.requireTrustedScope(
                query, snapshot("TENANT", UUID.randomUUID())))
                .isInstanceOf(EmploymentContractException.class);
    }

    private EmploymentContractTemplateQueryPort.TemplateQuery query() {
        return new EmploymentContractTemplateQueryPort.TemplateQuery(
                71L,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                4, "ko-KR", LocalDate.parse("2026-10-19"));
    }

    private EmploymentContractTemplateSnapshotV1 snapshot() {
        return snapshot("employment-contract.v12");
    }

    private EmploymentContractTemplateSnapshotV1 snapshot(String schemaVersion) {
        return snapshot(schemaVersion, "GLOBAL", null);
    }

    private EmploymentContractTemplateSnapshotV1 snapshot(
            String scopeType, UUID scopeId) {
        return snapshot("employment-contract.v12", scopeType, scopeId);
    }

    private EmploymentContractTemplateSnapshotV1 snapshot(
            String schemaVersion, String scopeType, UUID scopeId) {
        return new EmploymentContractTemplateSnapshotV1(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                schemaVersion, "STANDARD", LocalDate.parse("2026-01-01"),
                null, "ko-KR", "a".repeat(64),
                Instant.parse("2026-10-18T00:00:00Z"), scopeId, scopeType, 9,
                "PUBLISHED", new EmploymentContractTemplateSnapshotV1.TemplateContentReference(
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                2, "b".repeat(64)),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "EMPLOYMENT_CONTRACT", 4, 71L);
    }
}
