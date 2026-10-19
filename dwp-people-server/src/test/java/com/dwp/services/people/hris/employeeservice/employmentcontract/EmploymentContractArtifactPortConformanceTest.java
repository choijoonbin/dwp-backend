package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmploymentContractArtifactPortConformanceTest {

    @Test
    void productionAdapterIsRetryableFailClosed() {
        FailClosedEmploymentContractArtifactAdapter adapter =
                new FailClosedEmploymentContractArtifactAdapter();
        assertThatThrownBy(() -> adapter.issue(request()))
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> {
                    EmploymentContractException value =
                            (EmploymentContractException) error;
                    assertThat(value.code())
                            .isEqualTo("EMPLOYMENT_CONTRACT_ARTIFACT_UNAVAILABLE");
                    assertThat(value.status()).isEqualTo(503);
                    assertThat(value.retryable()).isTrue();
                });
    }

    @Test
    void conformanceDoubleIsIdempotentAndEchoesEveryBinding() {
        Map<UUID, EmploymentContractDtos.ArtifactResult> results = new HashMap<>();
        EmploymentContractArtifactPort port = value -> results.computeIfAbsent(
                value.artifactRequestId(), ignored -> new EmploymentContractDtos.ArtifactResult(
                        value.artifactRequestId(), value.contractPublicId(),
                        value.templateSnapshot().templatePublicId(),
                        value.templateSnapshot().templateVersion(),
                        value.generationSnapshotDigest(),
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        new EmploymentContractDtos.DocumentReference(
                                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                                1, "c".repeat(64)),
                        Instant.parse("2026-10-19T03:00:00Z")));

        EmploymentContractDtos.ArtifactResult first = port.issue(request());
        EmploymentContractDtos.ArtifactResult retry = port.issue(request());

        assertThat(retry).isSameAs(first);
        assertThat(first.artifactRequestId()).isEqualTo(request().artifactRequestId());
        assertThat(first.contractPublicId()).isEqualTo(request().contractPublicId());
        assertThat(first.generationSnapshotDigest())
                .isEqualTo(request().generationSnapshotDigest());
        assertThat(results).hasSize(1);
    }

    @Test
    void persistedSnapshotVersionsMustBePositiveBeforeProviderInvocation() {
        assertThatThrownBy(() -> new EmploymentContractDtos.AssignmentSnapshot(
                UUID.randomUUID(), 0, LocalDate.parse("2026-01-01"), null))
                .isInstanceOf(EmploymentContractException.class);
        assertThatThrownBy(() -> new EmploymentContractDtos.CompensationBasisSnapshot(
                UUID.randomUUID(), 0, "SALARY", "1", "KRW", "ANNUAL",
                LocalDate.parse("2026-01-01"), null))
                .isInstanceOf(EmploymentContractException.class);
    }

    private EmploymentContractDtos.ArtifactRequest request() {
        var relationship = new EmploymentContractDtos.RelationshipSnapshot(
                UUID.fromString("11111111-1111-4111-8111-111111111111"), 0,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                LocalDate.parse("2026-01-01"), null);
        var assignment = new EmploymentContractDtos.AssignmentSnapshot(
                UUID.fromString("44444444-4444-4444-8444-444444444444"), 1,
                LocalDate.parse("2026-01-01"), null);
        var basis = new EmploymentContractDtos.CompensationBasisSnapshot(
                UUID.fromString("55555555-5555-4555-8555-555555555555"), 1,
                "SALARY", "100000", "KRW", "ANNUAL",
                LocalDate.parse("2026-01-01"), null);
        return new EmploymentContractDtos.ArtifactRequest(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                71L, UUID.fromString("88888888-8888-4888-8888-888888888888"),
                relationship, assignment, basis, template(), "ko-KR",
                LocalDate.parse("2026-10-19"), null, "d".repeat(64));
    }

    private EmploymentContractTemplateSnapshotV1 template() {
        return new EmploymentContractTemplateSnapshotV1(
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                "employment-contract.v1", "STANDARD",
                LocalDate.parse("2026-01-01"), null, "ko-KR",
                "a".repeat(64), Instant.parse("2026-10-18T00:00:00Z"),
                null, "GLOBAL", 1, "PUBLISHED",
                new EmploymentContractTemplateSnapshotV1.TemplateContentReference(
                        UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                        1, "b".repeat(64)),
                UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
                "EMPLOYMENT_CONTRACT", 1, 71L);
    }
}
