package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011EventOutbox;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmploymentContractIdempotencyRecoveryTest {
    private static final long TENANT = 71L;
    private static final long EXPECTED = 3L;
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final UUID ACTOR = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID SUBJECT = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");
    private static final UUID RELATIONSHIP = UUID.fromString(
            "33333333-3333-4333-8333-333333333333");
    private static final UUID TEMPLATE = UUID.fromString(
            "44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION = UUID.fromString(
            "55555555-5555-4555-8555-555555555555");
    private static final String KEY = "employment-contract-idempotency-001";
    private static final String AUTH_REVISION = "psr-" + "a".repeat(64);

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void differentHashReplayConflictsBeforeLockArtifactOrMutation() {
        Fixture fixture = fixture(mock(EmploymentContractArtifactPort.class));
        when(fixture.store.receipt(TENANT, KEY)).thenReturn(Optional.of(
                receipt("f".repeat(64), "RUNNING", null)));

        assertThatThrownBy(() -> fixture.issue())
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> assertThat(
                        ((EmploymentContractException) error).code())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verify(fixture.store, never()).lockAggregate(anyLong(), any(), any());
        verifyNoInteractions(fixture.artifacts, fixture.highRisk);
    }

    @Test
    void staleVersionReturnsCurrentVersionBeforeTemplateArtifactAndPepSideEffects() {
        Fixture fixture = fixture(mock(EmploymentContractArtifactPort.class));
        when(fixture.store.receipt(TENANT, KEY)).thenReturn(Optional.empty());
        when(fixture.store.reserve(any())).thenReturn(Optional.of(
                receipt(fixture.requestDigest(), "RUNNING", null)));
        EmploymentContractStore.Aggregate stale = fixture.aggregate(4);
        when(fixture.store.lockAggregate(
                TENANT, RELATIONSHIP, fixture.request.effectiveFrom()))
                .thenReturn(List.of(stale));

        assertThatThrownBy(() -> fixture.issue())
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> {
                    EmploymentContractException value =
                            (EmploymentContractException) error;
                    assertThat(value.code())
                            .isEqualTo("EMPLOYMENT_CONTRACT_VERSION_CONFLICT");
                    assertThat(value.currentVersion()).isEqualTo(4L);
                });
        verifyNoInteractions(fixture.templates, fixture.artifacts, fixture.highRisk);
    }

    @Test
    void concurrentReservationWinnerReplaysWithoutProviderInvocation() {
        Fixture fixture = fixture(mock(EmploymentContractArtifactPort.class));
        EmploymentContractStore.Receipt winner = receipt(
                fixture.requestDigest(), "RUNNING", null);
        when(fixture.store.receipt(TENANT, KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(fixture.store.reserve(any())).thenReturn(Optional.empty());

        EmploymentContractService.CommandResult result = fixture.issue();

        assertThat(result.receipt().status()).isEqualTo("RUNNING");
        verify(fixture.store, never()).lockAggregate(anyLong(), any(), any());
        verifyNoInteractions(fixture.templates, fixture.artifacts, fixture.highRisk);
    }

    @Test
    void sameHashReplayKeepsOriginalReceiptWhenCorrelationChanges() {
        Fixture fixture = fixture(mock(EmploymentContractArtifactPort.class));
        when(fixture.store.receipt(TENANT, KEY)).thenReturn(Optional.of(
                receipt(fixture.requestDigest(), "RUNNING", null)));

        EmploymentContractService.CommandResult replay = fixture.issue(
                UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));

        assertThat(replay.receipt().correlationId()).isEqualTo(CORRELATION);
        verify(fixture.store, never()).lockAggregate(anyLong(), any(), any());
        verifyNoInteractions(fixture.templates, fixture.artifacts, fixture.highRisk);
    }

    @Test
    void mismatchedTemplateScopeFailsBeforeArtifactAndPersistence() {
        Fixture fixture = fixture(mock(EmploymentContractArtifactPort.class));
        when(fixture.store.receipt(TENANT, KEY)).thenReturn(Optional.empty());
        when(fixture.store.reserve(any())).thenReturn(Optional.of(
                receipt(fixture.requestDigest(), "RUNNING", null)));
        when(fixture.store.lockAggregate(
                TENANT, RELATIONSHIP, fixture.request.effectiveFrom()))
                .thenReturn(List.of(fixture.aggregate(EXPECTED)));
        when(fixture.templates.requirePublished(any())).thenReturn(
                fixture.templateWithScope("LEGAL_ENTITY", UUID.randomUUID()));

        assertThatThrownBy(fixture::issue)
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> assertThat(
                        ((EmploymentContractException) error).status()).isEqualTo(503));
        verifyNoInteractions(fixture.artifacts, fixture.highRisk);
        verify(fixture.store, never()).insertIssued(any());
    }

    @Test
    void retryAfterProviderSuccessAndRollbackReusesDeterministicArtifactRequestId() {
        List<UUID> artifactIds = new ArrayList<>();
        EmploymentContractArtifactPort artifacts = request -> {
            artifactIds.add(request.artifactRequestId());
            if (artifactIds.size() == 1) {
                throw EmploymentContractException.unavailable(
                        "simulated transaction rollback after provider success");
            }
            return new EmploymentContractDtos.ArtifactResult(
                    request.artifactRequestId(), request.contractPublicId(),
                    request.templateSnapshot().templatePublicId(),
                    request.templateSnapshot().templateVersion(),
                    request.generationSnapshotDigest(),
                    UUID.fromString("66666666-6666-4666-8666-666666666666"),
                    new EmploymentContractDtos.DocumentReference(
                            UUID.fromString("77777777-7777-4777-8777-777777777777"),
                            1, "b".repeat(64)), NOW);
        };
        Fixture fixture = fixture(artifacts);
        when(fixture.store.receipt(TENANT, KEY)).thenReturn(Optional.empty());
        when(fixture.store.reserve(any())).thenReturn(Optional.of(
                receipt(fixture.requestDigest(), "RUNNING", null)));
        when(fixture.store.lockAggregate(
                TENANT, RELATIONSHIP, fixture.request.effectiveFrom()))
                .thenReturn(List.of(fixture.aggregate(EXPECTED)));
        when(fixture.templates.requirePublished(any())).thenReturn(fixture.template);
        when(fixture.store.insertIssued(any())).thenAnswer(invocation -> {
            EmploymentContractStore.Issue issue = invocation.getArgument(0);
            return new EmploymentContractStore.IssuedContract(
                    issue.contractPublicId(), 1, issue.artifact().issuedAt());
        });
        when(fixture.store.complete(anyLong(), any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> receipt(
                        fixture.requestDigest(), "SUCCEEDED",
                        "{\"contractVersion\":1}"));

        assertThatThrownBy(fixture::issue)
                .isInstanceOf(EmploymentContractException.class);
        EmploymentContractService.CommandResult retry = fixture.issue();

        assertThat(retry.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(artifactIds).hasSize(2);
        assertThat(artifactIds.get(0)).isEqualTo(artifactIds.get(1));
        verify(fixture.store).insertIssued(any());
    }

    private Fixture fixture(EmploymentContractArtifactPort artifacts) {
        PeopleRequestContext.set(
                7L, TENANT, ACTOR, Set.of(),
                Set.of(EmploymentContractAuthorization.PERMISSION));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EmploymentContractCanonicalizer canonicalizer =
                new EmploymentContractCanonicalizer(mapper);
        EmploymentContractAuthorization authorization =
                mock(EmploymentContractAuthorization.class);
        EmploymentContractStore store = mock(EmploymentContractStore.class);
        EmploymentContractTemplateQueryPort templates =
                mock(EmploymentContractTemplateQueryPort.class);
        HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
        EmploymentContractAuthorization.Decision decision =
                new EmploymentContractAuthorization.Decision(
                        PeopleRequestContext.require(), AUTH_REVISION, "scope-v1", null);
        when(authorization.authorize(null)).thenReturn(decision);
        when(authorization.requireMutationPopulation(any(), anyLong(), any()))
                .thenReturn(decision);
        Hrm011EventOutbox events = new Hrm011EventOutbox(mapper, canonicalizer);
        EmploymentContractService service = new EmploymentContractService(
                new EmploymentContractRolloutGate(Set.of(TENANT)), authorization,
                store, templates, artifacts, canonicalizer, highRisk, events,
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        EmploymentContractTemplateSnapshotV1 template = template();
        EmploymentContractStore.Aggregate aggregate = aggregate(EXPECTED);
        EmploymentContractDtos.GenerationSnapshot generation =
                new EmploymentContractDtos.GenerationSnapshot(
                        aggregate.relationship(), aggregate.assignment(),
                        aggregate.compensationBasis(), template, "ko-KR");
        EmploymentContractDtos.IssueRequest request =
                new EmploymentContractDtos.IssueRequest(
                        RELATIONSHIP, TEMPLATE, 1, "ko-KR",
                        LocalDate.parse("2026-10-19"), null,
                        canonicalizer.generationDigest(generation));
        return new Fixture(service, store, authorization, templates, artifacts,
                highRisk, canonicalizer, template, request);
    }

    private static EmploymentContractStore.Aggregate aggregate(long relationshipVersion) {
        return new EmploymentContractStore.Aggregate(
                10, 11, 12, 91, SUBJECT,
                new EmploymentContractDtos.RelationshipSnapshot(
                        RELATIONSHIP, relationshipVersion,
                        UUID.fromString("88888888-8888-4888-8888-888888888888"),
                        UUID.fromString("99999999-9999-4999-8999-999999999999"),
                        LocalDate.parse("2026-01-01"), null),
                new EmploymentContractDtos.AssignmentSnapshot(
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        1, LocalDate.parse("2026-01-01"), null),
                new EmploymentContractDtos.CompensationBasisSnapshot(
                        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        1, "SALARY", "100000", "KRW", "ANNUAL",
                        LocalDate.parse("2026-01-01"), null));
    }

    private EmploymentContractTemplateSnapshotV1 template() {
        return new EmploymentContractTemplateSnapshotV1(
                UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                "employment-contract.v1", "STANDARD",
                LocalDate.parse("2026-01-01"), null, "ko-KR",
                "c".repeat(64), NOW.minusSeconds(60), null, "GLOBAL", 1,
                "PUBLISHED", new EmploymentContractTemplateSnapshotV1.TemplateContentReference(
                UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                1, "d".repeat(64)), TEMPLATE, "EMPLOYMENT_CONTRACT", 1, TENANT);
    }

    private EmploymentContractStore.Receipt receipt(
            String digest, String state, String result) {
        return new EmploymentContractStore.Receipt(
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                digest, ACTOR, RELATIONSHIP,
                EmploymentContractAuthorization.PURPOSE, EXPECTED, CORRELATION,
                state, result, "e".repeat(64), 1, 1, NOW);
    }

    private record Fixture(
            EmploymentContractService service,
            EmploymentContractStore store,
            EmploymentContractAuthorization authorization,
            EmploymentContractTemplateQueryPort templates,
            EmploymentContractArtifactPort artifacts,
            HcmHighRiskCommandGuard highRisk,
            EmploymentContractCanonicalizer canonicalizer,
            EmploymentContractTemplateSnapshotV1 template,
            EmploymentContractDtos.IssueRequest request) {

        EmploymentContractService.CommandResult issue() {
            return issue(CORRELATION);
        }

        EmploymentContractService.CommandResult issue(UUID correlationId) {
            return service.issue(
                    request, KEY, '"' + Long.toString(EXPECTED) + '"', EXPECTED,
                    correlationId, null,
                    new HcmStepUpHeaders(
                            "signed-challenge", KEY, AUTH_REVISION, EXPECTED));
        }

        EmploymentContractTemplateSnapshotV1 templateWithScope(
                String scopeType, UUID scopeId) {
            return new EmploymentContractTemplateSnapshotV1(
                    template.approvalReceiptId(), template.contentSchemaVersion(),
                    template.contractType(), template.effectiveFrom(), template.effectiveTo(),
                    template.locale(), template.payloadDigest(), template.publishedAt(),
                    scopeId, scopeType, template.sourceRevision(), template.state(),
                    template.templateContentReference(), template.templatePublicId(),
                    template.templateType(), template.templateVersion(), template.tenantId());
        }

        String requestDigest() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("schemaVersion", "PEP-HRM-011.v1");
            input.put("method", "POST");
            input.put("path", EmploymentContractAuthorization.BROWSER_PATH);
            input.put("body", request);
            input.put("ifMatch", '"' + Long.toString(EXPECTED) + '"');
            input.put("expectedObjectVersion", EXPECTED);
            input.put("trustedPurpose", EmploymentContractAuthorization.PURPOSE);
            return canonicalizer.digest(input);
        }

        EmploymentContractStore.Aggregate aggregate(long version) {
            return EmploymentContractIdempotencyRecoveryTest.aggregate(version);
        }
    }
}
