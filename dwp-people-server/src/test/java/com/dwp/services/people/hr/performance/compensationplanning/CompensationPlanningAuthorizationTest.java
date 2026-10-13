package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
                CompensationPlanningAuthorization.RESOURCE,
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
                CompensationPlanningAuthorization.RESOURCE,
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
    void viewOnlyDutyCannotReceiveDetailedCompensationFields() {
        PeopleRequestContext.Actor viewOnly = new PeopleRequestContext.Actor(
                20L, 10L, SUBJECT, Set.of(), Set.of("DATA.HR_TALENT:VIEW"));
        PeopleRequestContext.Actor proposer = new PeopleRequestContext.Actor(
                20L, 10L, SUBJECT, Set.of(), Set.of("DATA.HR_TALENT:UPDATE"));

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
                        expectedVersion, null, hash, "ACCEPTED", null,
                        acceptedAt, null, null)));
        CompensationPlanningService service = new CompensationPlanningService(
                repository, mock(PerTypedArtifactService.class),
                mock(PerTypedArtifactQueryPort.class),
                mock(CompensationPopulationMaterializer.class), canonicalizer,
                mock(AuditOutboxRecorder.class),
                Clock.fixed(acceptedAt, ZoneOffset.UTC), () -> authority);

        PeopleRequestContext.set(
                actorId, tenantId, SUBJECT, Set.of(),
                Set.of("DATA.HR_TALENT:UPDATE"));
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
                CompensationPlanningAuthorization.RESOURCE,
                "UPDATE", "modern.compplan.cycle.revise",
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                CompensationPlanningAuthorization.PURPOSE, AGGREGATE);
    }

    private static CompensationPlanningAuthorityPort.AuthorityEvidence evidence() {
        return new CompensationPlanningAuthorityPort.AuthorityEvidence(
                10L, 20L, SUBJECT,
                CompensationPlanningAuthorization.APPLICATION,
                CompensationPlanningAuthorization.RESOURCE,
                "UPDATE", "modern.compplan.cycle.revise",
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                CompensationPlanningAuthorization.PURPOSE,
                "a".repeat(64), population(),
                Map.of("PROPOSAL_AMOUNT",
                        CompensationPlanningAuthorityPort.FieldDecision.VIEW),
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
