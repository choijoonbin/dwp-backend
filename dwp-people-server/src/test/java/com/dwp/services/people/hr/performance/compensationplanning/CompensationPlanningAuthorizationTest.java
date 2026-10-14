package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guideline;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.PopulationLine;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompensationPlanningAuthorizationTest {

    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID AGGREGATE =
            UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Test
    void administrativeRolesNeverReplaceTheExplicitPermission() {
        PeopleRequestContext.Actor roleOnly = new PeopleRequestContext.Actor(
                10L, 20L, SUBJECT, Set.of("ADMIN", "HR_ADMIN"), Set.of());

        assertCode(
                () -> CompensationPlanningAuthorization.requireActorPermission(
                        roleOnly, "UPDATE"),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsEveryMismatchedAuthorityDimensionFailClosed() {
        CompensationPlanningAuthorityPort.AuthorityRequest request = request();

        for (UnaryOperator<CompensationPlanningAuthorityPort.AuthorityEvidence> mutation
                : List.<UnaryOperator<CompensationPlanningAuthorityPort.AuthorityEvidence>>of(
                value -> copy(value, 11L, value.actorId(), value.subjectPrincipalPublicId(),
                        value.applicationEntitlement(), value.resource(), value.action(),
                        value.operationId(), value.capability(), value.purposeCode()),
                value -> copy(value, value.tenantId(), 21L,
                        value.subjectPrincipalPublicId(), value.applicationEntitlement(),
                        value.resource(), value.action(), value.operationId(),
                        value.capability(), value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(), UUID.randomUUID(),
                        value.applicationEntitlement(), value.resource(), value.action(),
                        value.operationId(), value.capability(), value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(),
                        value.subjectPrincipalPublicId(), "APP.OTHER", value.resource(),
                        value.action(), value.operationId(), value.capability(),
                        value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(),
                        value.subjectPrincipalPublicId(), value.applicationEntitlement(),
                        "DATA.OTHER", value.action(), value.operationId(),
                        value.capability(), value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(),
                        value.subjectPrincipalPublicId(), value.applicationEntitlement(),
                        value.resource(), "VIEW", value.operationId(),
                        value.capability(), value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(),
                        value.subjectPrincipalPublicId(), value.applicationEntitlement(),
                        value.resource(), value.action(), "different.operation",
                        value.capability(), value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(),
                        value.subjectPrincipalPublicId(), value.applicationEntitlement(),
                        value.resource(), value.action(), value.operationId(),
                        "hcm.other", value.purposeCode()),
                value -> copy(value, value.tenantId(), value.actorId(),
                        value.subjectPrincipalPublicId(), value.applicationEntitlement(),
                        value.resource(), value.action(), value.operationId(),
                        value.capability(), "OTHER_PURPOSE"))) {
            assertCode(
                    () -> CompensationPlanningAuthorization.verify(
                            request, mutation.apply(evidence()), false,
                            List.of("PROPOSAL_AMOUNT")),
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    @Test
    void requiresViewFieldDecisionsAndStepUpForApproveAndPublish() {
        CompensationPlanningAuthorityPort.AuthorityEvidence masked = new
                CompensationPlanningAuthorityPort.AuthorityEvidence(
                10L, 20L, SUBJECT,
                CompensationPlanningAuthorization.APPLICATION,
                CompensationPlanningAuthorization.DATA_RESOURCE,
                "UPDATE", "modern.compplan.cycle.revise",
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                CompensationPlanningAuthorization.PURPOSE,
                "a".repeat(64), population(),
                Map.of("PROPOSAL_AMOUNT",
                        CompensationPlanningAuthorityPort.FieldDecision.MASK),
                1L, 1L, true);
        assertCode(
                () -> CompensationPlanningAuthorization.verify(
                        request(), masked, false, List.of("PROPOSAL_AMOUNT")),
                ErrorCode.FORBIDDEN);

        CompensationPlanningAuthorityPort.AuthorityEvidence noStepUp = new
                CompensationPlanningAuthorityPort.AuthorityEvidence(
                10L, 20L, SUBJECT,
                CompensationPlanningAuthorization.APPLICATION,
                CompensationPlanningAuthorization.DATA_RESOURCE,
                "UPDATE", "modern.compplan.cycle.revise",
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                CompensationPlanningAuthorization.PURPOSE,
                "a".repeat(64), population(),
                Map.of("PROPOSAL_AMOUNT",
                        CompensationPlanningAuthorityPort.FieldDecision.VIEW),
                1L, 1L, false);
        assertCode(
                () -> CompensationPlanningAuthorization.verify(
                        request(), noStepUp, true, List.of("PROPOSAL_AMOUNT")),
                ErrorCode.STEP_UP_REQUIRED);
    }

    @Test
    void proposalMakerCannotApproveOrPublish() {
        assertCode(
                () -> CompensationPlanningAuthorization.requireIndependentApprover(
                        SUBJECT, List.of(UUID.randomUUID(), SUBJECT)),
                ErrorCode.SOD_CONFLICT);
    }

    @Test
    void successfulMakerReceiptSubjectCannotApproveOrPublishThroughService() {
        assertServiceMakerDenied(false);
        assertServiceMakerDenied(true);
    }

    @Test
    void hostileApprovalCardinalityFailsBeforeAnyTransition() {
        CompensationPlanningRepository repository =
                mock(CompensationPlanningRepository.class);
        CompensationPlanningAuthorityPort authority =
                mock(CompensationPlanningAuthorityPort.class);
        when(authority.authorize(any())).thenAnswer(invocation ->
                authorityEvidence(invocation.getArgument(0), "a".repeat(64)));
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        CompensationPlanningRepository.CycleRow cycle = cycle("PROPOSAL_REVIEW", now);
        when(repository.cycle(10L, AGGREGATE, true)).thenReturn(Optional.of(cycle));
        CompensationPlanningRepository.ProposalRow submitted = proposalRow(
                validProposal(UUID.randomUUID(), UUID.randomUUID(), "1"), now);
        when(repository.proposals(10L, cycle.internalId(), true))
                .thenReturn(List.of())
                .thenReturn(java.util.Collections.nCopies(100_001, submitted));
        CompensationPlanningService service = service(
                repository, authority,
                new CompensationPlanningCanonicalizer(new ObjectMapper()), now);

        PeopleRequestContext.set(20L, 10L, SUBJECT, Set.of(),
                Set.of("ACTION.HRIS_COMPENSATION_PLAN_APPROVE:APPROVE"));
        try {
            assertCode(() -> service.approve(
                    AGGREGATE, 7L,
                    new CompensationPlanningDtos.ApprovePlanRequest(
                            "APPROVE", "EMPTY_SET"),
                    "empty-set", UUID.randomUUID().toString(), null),
                    ErrorCode.INVALID_STATE);
            assertCode(() -> service.approve(
                    AGGREGATE, 7L,
                    new CompensationPlanningDtos.ApprovePlanRequest(
                            "APPROVE", "OVERFLOW_SET"),
                    "overflow-set", UUID.randomUUID().toString(), null),
                    ErrorCode.INVALID_STATE);
            assertNoApprovalMutation(repository);
        } finally {
            PeopleRequestContext.clear();
        }
    }

    @Test
    void nonHardCapCollisionZeroAndMoneyOverflowFailBeforeAnyTransition() {
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        UUID workerOne = UUID.fromString("00000000-0000-4000-8000-000000000010");
        UUID assignmentOne = UUID.fromString("00000000-0000-4000-8000-000000000011");
        UUID workerTwo = UUID.fromString("00000000-0000-4000-8000-000000000012");
        UUID assignmentTwo = UUID.fromString("00000000-0000-4000-8000-000000000013");
        UUID organization = UUID.fromString("00000000-0000-4000-8000-000000000014");
        Population oneMember = populationOf(List.of(
                populationLine(workerOne, assignmentOne, organization)));
        Guidelines hardCap = guidelines("999999999999999.9999");
        Guidelines nonHardCap = mock(Guidelines.class);
        when(nonHardCap.budgetMode()).thenReturn("EXCEPTION_APPROVAL");

        assertHostileApprovalDenied(
                List.of(validProposal(workerOne, assignmentOne, "1")),
                oneMember, nonHardCap, now, Map.of());
        assertHostileApprovalDenied(
                List.of(
                        validProposal(workerOne, assignmentOne, "1"),
                        validProposal(workerOne, assignmentOne, "2")),
                oneMember, hardCap, now, Map.of());
        assertHostileApprovalDenied(
                List.of(validProposal(workerOne, assignmentOne, "0")),
                oneMember, hardCap, now, Map.of());
        Population twoMembers = populationOf(List.of(
                populationLine(workerOne, assignmentOne, organization),
                populationLine(workerTwo, assignmentTwo, organization)));
        assertHostileApprovalDenied(
                List.of(
                        validProposal(workerOne, assignmentOne,
                                "999999999999999.9999"),
                        validProposal(workerTwo, assignmentTwo,
                                "999999999999999.9999")),
                twoMembers, hardCap, now,
                Map.of(new CompensationPlanningRepository.BudgetBucket(
                                organization, "USD"),
                        new BigDecimal("999999999999999.9999")));
    }

    @Test
    void viewOnlyDutyCannotReceiveDetailedCompensationFields() {
        PeopleRequestContext.Actor viewOnly = new PeopleRequestContext.Actor(
                20L, 10L, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:VIEW"));
        PeopleRequestContext.Actor proposer = new PeopleRequestContext.Actor(
                20L, 10L, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:UPDATE"));

        assertThat(CompensationPlanningAuthorization.permitsDetailField(
                viewOnly, evidence(), "PROPOSAL_AMOUNT")).isFalse();
        assertThat(CompensationPlanningAuthorization.permitsDetailField(
                proposer, evidence(), "PROPOSAL_AMOUNT")).isTrue();
    }

    @Test
    void unresolvedIdempotentReplayReturnsCanonicalCorrelationWithoutMutation() {
        long tenantId = 10L;
        long actorId = 20L;
        long expectedVersion = 7L;
        UUID cycleId = AGGREGATE;
        UUID organizationId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2027-01-01T00:00:00Z");
        String key = "budget-replay-1";
        CompensationPlanningDtos.BudgetEntryRequest request =
                new CompensationPlanningDtos.BudgetEntryRequest(
                        organizationId, "100", "USD",
                        CompensationPlanningDtos.LedgerEntryType.ALLOCATE, null);
        CompensationPlanningCanonicalizer canonicalizer =
                new CompensationPlanningCanonicalizer(new ObjectMapper());
        String hash = canonicalizer.commandDigest(
                "POST",
                "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycleId + "/budget-allocations",
                expectedVersion, tenantId, SUBJECT, request);
        CompensationPlanningRepository repository =
                mock(CompensationPlanningRepository.class);
        CompensationPlanningAuthorityPort authority =
                mock(CompensationPlanningAuthorityPort.class);
        when(authority.authorize(any())).thenAnswer(invocation -> {
            CompensationPlanningAuthorityPort.AuthorityRequest requested =
                    invocation.getArgument(0);
            return new CompensationPlanningAuthorityPort.AuthorityEvidence(
                    requested.tenantId(), requested.actorId(),
                    requested.subjectPrincipalPublicId(),
                    requested.applicationEntitlement(), requested.resource(),
                    requested.action(), requested.operationId(), requested.capability(),
                    requested.purposeCode(), "a".repeat(64), population(),
                    Map.of(
                            "BUDGET_AMOUNT",
                            CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                            "ORGANIZATION",
                            CompensationPlanningAuthorityPort.FieldDecision.VIEW),
                    1L, 1L, true);
        });
        when(repository.receiptByIdempotency(
                tenantId, SUBJECT, "hcm.compensation.plan.propose", key))
                .thenReturn(Optional.of(new CompensationPlanningRepository.ReceiptRecord(
                        receiptId, key, CompensationPlanningService.BUDGET,
                        "hcm.compensation.plan.propose", SUBJECT, cycleId,
                        expectedVersion, null, hash, "a".repeat(64), 1L,
                        CompensationPlanningAuthorization.PURPOSE, 1L,
                        "ACCEPTED", null,
                        acceptedAt, null, null)));
        CompensationPlanningService service = new CompensationPlanningService(
                repository, mock(PerTypedArtifactService.class),
                mock(PerTypedArtifactQueryPort.class),
                mock(CompensationPopulationMaterializer.class), canonicalizer,
                mock(AuditOutboxRecorder.class),
                Clock.fixed(acceptedAt, ZoneOffset.UTC), () -> authority,
                mock(HcmHighRiskCommandGuard.class));

        PeopleRequestContext.set(
                actorId, tenantId, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            CompensationPlanningDtos.CommandReceipt replay = service.allocateBudget(
                    cycleId, expectedVersion, request, key, correlationId.toString());

            assertThat(replay.receiptId()).isEqualTo(receiptId);
            assertThat(replay.status()).isEqualTo("RESULT_UNKNOWN");
            assertThat(replay.aggregateId()).isEqualTo(cycleId);
            assertThat(replay.correlationId()).isEqualTo(correlationId);
            assertThat(replay.occurredAt()).isEqualTo(acceptedAt);
            assertThat(replay.payloadDigest()).isEqualTo(hash);
            verify(repository, never()).reserveReceipt(
                    anyLong(), anyLong(), any(), any(), any(), any(), any(),
                    any(), anyLong(), any(), any());
            verify(repository, never()).appendBudget(
                    anyLong(), any(), any(), anyLong(), any(), any(), any(), any());
        } finally {
            PeopleRequestContext.clear();
        }
    }

    @Test
    void succeededReplayIsDeniedWhenCurrentScopeEvidenceHasChangedWithoutMutation() {
        long tenantId = 10L;
        long actorId = 20L;
        long expectedVersion = 7L;
        UUID organizationId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        String key = "budget-replay-scope-shrink";
        CompensationPlanningDtos.BudgetEntryRequest request =
                new CompensationPlanningDtos.BudgetEntryRequest(
                        organizationId, "100", "USD",
                        CompensationPlanningDtos.LedgerEntryType.ALLOCATE, null);
        CompensationPlanningCanonicalizer canonicalizer =
                new CompensationPlanningCanonicalizer(new ObjectMapper());
        String hash = canonicalizer.commandDigest(
                "POST",
                "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + AGGREGATE + "/budget-allocations",
                expectedVersion, tenantId, SUBJECT, request);
        CompensationPlanningRepository repository =
                mock(CompensationPlanningRepository.class);
        CompensationPlanningAuthorityPort authority =
                mock(CompensationPlanningAuthorityPort.class);
        when(authority.authorize(any())).thenAnswer(invocation -> {
            CompensationPlanningAuthorityPort.AuthorityRequest requested =
                    invocation.getArgument(0);
            return authorityEvidence(requested, "c".repeat(64));
        });
        when(repository.receiptByIdempotency(
                tenantId, SUBJECT, "hcm.compensation.plan.propose", key))
                .thenReturn(Optional.of(new CompensationPlanningRepository.ReceiptRecord(
                        receiptId, key, CompensationPlanningService.BUDGET,
                        "hcm.compensation.plan.propose", SUBJECT, AGGREGATE,
                        expectedVersion, 8L, hash, "b".repeat(64), 1L,
                        CompensationPlanningAuthorization.PURPOSE, 1L,
                        "SUCCEEDED", AGGREGATE, now, now, null)));
        CompensationPlanningService service = service(repository, authority, canonicalizer, now);

        PeopleRequestContext.set(actorId, tenantId, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            assertCode(() -> service.allocateBudget(
                    AGGREGATE, expectedVersion, request, key, UUID.randomUUID().toString()),
                    ErrorCode.FORBIDDEN);
            verify(repository, never()).reserveReceipt(
                    anyLong(), anyLong(), any(), any(), any(), any(), any(),
                    any(), anyLong(), any(), any());
            verify(repository, never()).appendBudget(
                    anyLong(), any(), any(), anyLong(), any(), any(), any(), any());
        } finally {
            PeopleRequestContext.clear();
        }
    }

    @Test
    void closedWireInputsRejectUnknownMissingAndDuplicateQueryFields() throws Exception {
        ObjectMapper permissive = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String valid = """
                {"components":[{"componentCode":"BASE","currency":"USD",
                "minimumAmount":"0","maximumAmount":"100",
                "exceptionPolicyCode":"NONE",
                "roundingPolicyVersionPublicId":"00000000-0000-4000-8000-000000000009"}],
                "budgetMode":"HARD_CAP","effectiveFrom":"2027-01-01","effectiveTo":null}
                """;
        assertThatCode(() -> permissive.readValue(valid, Guidelines.class))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissive.readValue(
                valid.replace("\"budgetMode\"", "\"unexpected\":true,\"budgetMode\""),
                Guidelines.class)).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> permissive.readValue(
                valid.replace(",\"effectiveTo\":null", ""), Guidelines.class))
                .hasMessageContaining("effectiveTo");

        LinkedMultiValueMap<String, String> unknown = new LinkedMultiValueMap<>();
        unknown.add("tenantId", "91");
        assertCode(
                () -> CompensationPlanningController.requireClosedQuery(
                        unknown, "referenceEffectiveAt"),
                ErrorCode.INVALID_INPUT_VALUE);
        LinkedMultiValueMap<String, String> duplicate = new LinkedMultiValueMap<>();
        duplicate.add("limit", "10");
        duplicate.add("limit", "20");
        assertCode(
                () -> CompensationPlanningController.requireClosedQuery(
                        duplicate, "limit"),
                ErrorCode.INVALID_INPUT_VALUE);
    }

    private static CompensationPlanningAuthorityPort.AuthorityRequest request() {
        return new CompensationPlanningAuthorityPort.AuthorityRequest(
                10L, 20L, SUBJECT,
                CompensationPlanningAuthorization.APPLICATION,
                CompensationPlanningAuthorization.DATA_RESOURCE,
                "UPDATE", "modern.compplan.cycle.revise",
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                CompensationPlanningAuthorization.PURPOSE, AGGREGATE);
    }

    private static CompensationPlanningAuthorityPort.AuthorityEvidence evidence() {
        return new CompensationPlanningAuthorityPort.AuthorityEvidence(
                10L, 20L, SUBJECT,
                CompensationPlanningAuthorization.APPLICATION,
                CompensationPlanningAuthorization.DATA_RESOURCE,
                "UPDATE", "modern.compplan.cycle.revise",
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                CompensationPlanningAuthorization.PURPOSE,
                "a".repeat(64), population(),
                Map.of("PROPOSAL_AMOUNT",
                        CompensationPlanningAuthorityPort.FieldDecision.VIEW),
                1L, 1L, true);
    }

    private static void assertServiceMakerDenied(boolean publish) {
        long tenantId = 10L;
        long actorId = 20L;
        long version = 7L;
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        UUID proposalId = UUID.randomUUID();
        UUID approvalReceiptId = UUID.randomUUID();
        CompensationPlanningRepository repository =
                mock(CompensationPlanningRepository.class);
        CompensationPlanningAuthorityPort authority =
                mock(CompensationPlanningAuthorityPort.class);
        when(authority.authorize(any())).thenAnswer(invocation ->
                authorityEvidence(invocation.getArgument(0), "a".repeat(64)));
        CompensationPlanningRepository.CycleRow cycle =
                new CompensationPlanningRepository.CycleRow(
                        1L, AGGREGATE, version, now, UUID.randomUUID(), now,
                        UUID.randomUUID(), "FY27", UUID.randomUUID(), UUID.randomUUID(),
                        LocalDate.parse("2027-01-01"), null,
                        publish ? "APPROVED" : "PROPOSAL_REVIEW");
        CompensationPlanningRepository.ProposalRow proposal =
                new CompensationPlanningRepository.ProposalRow(
                        2L, proposalId, 1L, now, UUID.randomUUID(), now,
                        UUID.randomUUID(), cycle.internalId(), null, 1L,
                        publish ? "FROZEN" : "SUBMITTED", UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), "d".repeat(64));
        when(repository.cycle(tenantId, AGGREGATE, true)).thenReturn(Optional.of(cycle));
        when(repository.proposals(tenantId, cycle.internalId(), true))
                .thenReturn(List.of(proposal));
        when(repository.proposalMakerIds(tenantId, cycle.internalId()))
                .thenReturn(List.of(SUBJECT));
        when(repository.receiptById(tenantId, approvalReceiptId)).thenReturn(Optional.of(
                new CompensationPlanningRepository.ReceiptRecord(
                        approvalReceiptId, "approve-key", CompensationPlanningService.APPROVE,
                        "hcm.compensation.plan.approve", UUID.randomUUID(), AGGREGATE,
                        6L, 7L, "e".repeat(64), "a".repeat(64), 1L,
                        CompensationPlanningAuthorization.PURPOSE, 1L,
                        "SUCCEEDED", AGGREGATE, now, now, null)));
        CompensationPlanningService service = service(
                repository, authority,
                new CompensationPlanningCanonicalizer(new ObjectMapper()), now);

        PeopleRequestContext.set(actorId, tenantId, SUBJECT, Set.of(),
                Set.of("ACTION.HRIS_COMPENSATION_PLAN_APPROVE:APPROVE"));
        try {
            Runnable action = publish
                    ? () -> service.publish(
                            AGGREGATE, version,
                            new CompensationPlanningDtos.PublishSnapshotRequest(
                                    approvalReceiptId),
                            "publish-key", UUID.randomUUID().toString(), null)
                    : () -> service.approve(
                            AGGREGATE, version,
                            new CompensationPlanningDtos.ApprovePlanRequest(
                                    "APPROVE", "ANNUAL_REVIEW"),
                            "approve-key", UUID.randomUUID().toString(), null);
            assertCode(action, ErrorCode.SOD_CONFLICT);
            verify(repository, never()).reserveReceipt(
                    anyLong(), anyLong(), any(), any(), any(), any(), any(),
                    any(), anyLong(), any(), any());
            verify(repository, never()).approve(anyLong(), any(), anyLong(), any(), any());
            verify(repository, never()).insertSnapshot(
                    anyLong(), any(), any(), anyLong(), any(), any(), anyLong(),
                    any(), any(), any(), any());
        } finally {
            PeopleRequestContext.clear();
        }
    }

    private static CompensationPlanningService service(
            CompensationPlanningRepository repository,
            CompensationPlanningAuthorityPort authority,
            CompensationPlanningCanonicalizer canonicalizer,
            Instant now) {
        return new CompensationPlanningService(
                repository, mock(PerTypedArtifactService.class),
                mock(PerTypedArtifactQueryPort.class),
                mock(CompensationPopulationMaterializer.class), canonicalizer,
                mock(AuditOutboxRecorder.class),
                Clock.fixed(now, ZoneOffset.UTC), () -> authority,
                mock(HcmHighRiskCommandGuard.class));
    }

    private static void assertHostileApprovalDenied(
            List<Proposal> contents,
            Population population,
            Guidelines guidelines,
            Instant now,
            Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> budgets) {
        CompensationPlanningRepository repository =
                mock(CompensationPlanningRepository.class);
        CompensationPlanningAuthorityPort authority =
                mock(CompensationPlanningAuthorityPort.class);
        PerTypedArtifactQueryPort artifactQuery = mock(PerTypedArtifactQueryPort.class);
        AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
        when(authority.authorize(any())).thenAnswer(invocation ->
                authorityEvidence(invocation.getArgument(0), "a".repeat(64)));
        CompensationPlanningRepository.CycleRow cycle = cycle("PROPOSAL_REVIEW", now);
        List<CompensationPlanningRepository.ProposalRow> rows = contents.stream()
                .map(content -> proposalRow(content, now)).toList();
        when(repository.cycle(10L, AGGREGATE, true)).thenReturn(Optional.of(cycle));
        when(repository.proposals(10L, cycle.internalId(), true)).thenReturn(rows);
        when(repository.proposalMakerIds(10L, cycle.internalId()))
                .thenReturn(List.of(UUID.randomUUID()));
        when(repository.budgetTotals(10L, cycle.internalId())).thenReturn(budgets);
        Map<UUID, PerTypedArtifactQueryPort.TypedArtifact> artifacts = new HashMap<>();
        artifacts.put(cycle.populationArtifactId(), typed(
                cycle.populationArtifactId(), PerArtifactType.COMPENSATION_POPULATION,
                "a".repeat(64), population));
        artifacts.put(cycle.guidelineArtifactId(), typed(
                cycle.guidelineArtifactId(), PerArtifactType.COMPENSATION_GUIDELINES,
                "b".repeat(64), guidelines));
        for (int index = 0; index < rows.size(); index++) {
            CompensationPlanningRepository.ProposalRow row = rows.get(index);
            artifacts.put(row.artifactId(), typed(
                    row.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                    row.contentDigest(), contents.get(index)));
        }
        when(artifactQuery.query(anyLong(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> artifacts.get(invocation.getArgument(1)));
        CompensationPlanningService service = new CompensationPlanningService(
                repository, mock(PerTypedArtifactService.class), artifactQuery,
                mock(CompensationPopulationMaterializer.class),
                new CompensationPlanningCanonicalizer(new ObjectMapper()), audit,
                Clock.fixed(now, ZoneOffset.UTC), () -> authority,
                mock(HcmHighRiskCommandGuard.class));

        PeopleRequestContext.set(20L, 10L, SUBJECT, Set.of(),
                Set.of("ACTION.HRIS_COMPENSATION_PLAN_APPROVE:APPROVE"));
        try {
            assertCode(() -> service.approve(
                    AGGREGATE, 7L,
                    new CompensationPlanningDtos.ApprovePlanRequest(
                            "APPROVE", "HOSTILE_SET"),
                    "hostile-" + UUID.randomUUID(), UUID.randomUUID().toString(), null),
                    ErrorCode.INVALID_STATE);
            assertNoApprovalMutation(repository);
            verify(audit, never()).record(any());
        } finally {
            PeopleRequestContext.clear();
        }
    }

    private static CompensationPlanningRepository.CycleRow cycle(
            String status, Instant now) {
        return new CompensationPlanningRepository.CycleRow(
                1L, AGGREGATE, 7L, now, UUID.randomUUID(), now,
                UUID.randomUUID(), "FY27", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2027-01-01"), null, status);
    }

    private static CompensationPlanningRepository.ProposalRow proposalRow(
            Proposal content, Instant now) {
        return new CompensationPlanningRepository.ProposalRow(
                Math.abs(UUID.randomUUID().getLeastSignificantBits()), UUID.randomUUID(),
                1L, now, UUID.randomUUID(), now, UUID.randomUUID(), 1L,
                content, 1L, "SUBMITTED", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "d".repeat(64));
    }

    private static Proposal validProposal(UUID worker, UUID assignment, String amount) {
        return new Proposal(
                AGGREGATE, worker, assignment, "BASE", "USD", amount,
                LocalDate.parse("2027-01-01"), null, null, null);
    }

    private static Guidelines guidelines(String maximum) {
        return new Guidelines(List.of(new Guideline(
                "BASE", "USD", "0", maximum, "NONE", UUID.randomUUID())),
                "HARD_CAP", LocalDate.parse("2027-01-01"), null);
    }

    private static Population populationOf(List<PopulationLine> lines) {
        return new Population(Instant.parse("2027-01-01T00:00:00Z"), 1L, lines);
    }

    private static PopulationLine populationLine(
            UUID worker, UUID assignment, UUID organization) {
        return new PopulationLine(
                worker, assignment, organization, null, null, 1L, List.of("BASE"));
    }

    private static PerTypedArtifactQueryPort.TypedArtifact typed(
            UUID id,
            PerArtifactType type,
            String digest,
            com.dwp.services.people.hr.performance.typedartifact
                    .CompensationArtifactContents.Content content) {
        return new PerTypedArtifactQueryPort.TypedArtifact(
                id, type, 1L, 1, digest, LocalDate.parse("2027-01-01"), null, content);
    }

    private static void assertNoApprovalMutation(
            CompensationPlanningRepository repository) {
        verify(repository, never()).reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), any(),
                any(), anyLong(), any(), any());
        verify(repository, never()).approve(anyLong(), any(), anyLong(), any(), any());
        verify(repository, never()).completeReceipt(
                anyLong(), any(), anyLong(), any(), any());
        verify(repository, never()).insertTransitionOutbox(
                anyLong(), any(), any(), any(), any(), anyLong(), any(), any(),
                any(), any(), any());
    }

    private static CompensationPlanningAuthorityPort.AuthorityEvidence authorityEvidence(
            CompensationPlanningAuthorityPort.AuthorityRequest request,
            String scopeDigest) {
        return new CompensationPlanningAuthorityPort.AuthorityEvidence(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                request.applicationEntitlement(), request.resource(), request.action(),
                request.operationId(), request.capability(), request.purposeCode(),
                scopeDigest, population(), Map.of(
                "PROPOSAL_AMOUNT", CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                "WORKER", CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                "ASSIGNMENT", CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                "BUDGET_AMOUNT", CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                "ORGANIZATION", CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                "GUIDELINES", CompensationPlanningAuthorityPort.FieldDecision.VIEW,
                "POPULATION", CompensationPlanningAuthorityPort.FieldDecision.VIEW),
                1L, 1L, true);
    }

    private static CompensationPlanningAuthorityPort.TargetPopulation population() {
        return new CompensationPlanningAuthorityPort.TargetPopulation(
                true, Set.of(), Set.of(), Set.of());
    }

    private static CompensationPlanningAuthorityPort.AuthorityEvidence copy(
            CompensationPlanningAuthorityPort.AuthorityEvidence value,
            long tenantId,
            long actorId,
            UUID subject,
            String application,
            String resource,
            String action,
            String operation,
            String capability,
            String purpose) {
        return new CompensationPlanningAuthorityPort.AuthorityEvidence(
                tenantId, actorId, subject, application, resource, action,
                operation, capability, purpose, value.populationScopeDigest(),
                value.targetPopulation(), value.fieldDecisions(),
                value.fieldPolicyRevision(), value.authorizationRevision(),
                value.stepUpSatisfied());
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(code);
    }
}
