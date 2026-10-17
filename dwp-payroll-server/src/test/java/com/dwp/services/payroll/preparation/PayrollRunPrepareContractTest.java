package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayrollRunPrepareContractTest {

    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PAY_GROUP =
            UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID PAY_PERIOD =
            UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-0000-0000-000000000204");
    private static final UUID PREPARATION =
            UUID.fromString("00000000-0000-0000-0000-000000000205");
    private static final UUID TARGET_SOURCE =
            UUID.fromString("00000000-0000-0000-0000-000000000206");
    private static final UUID SEMANTIC_SOURCE =
            UUID.fromString("00000000-0000-0000-0000-000000000207");
    private static final UUID SOURCE_SNAPSHOT =
            UUID.fromString("00000000-0000-0000-0000-000000000208");
    private static final String TARGET_DIGEST = "1".repeat(64);
    private static final String SCOPE_DIGEST = "2".repeat(64);
    private static final String SOURCE_DIGEST = "3".repeat(64);
    private static final String FORMULA_DIGEST = "4".repeat(64);
    private static final String SEMANTIC_DIGEST = "5".repeat(64);
    private static final String BINDING_DIGEST = "6".repeat(64);
    private static final String IDEMPOTENCY = "pay013-idempotency-0001";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private PayrollPreparationStore store;
    private PayrollPreparationStepUpGuard stepUp;
    private PayrollPreparationCanonical canonical;
    private PayrollPreparationService service;

    @BeforeEach
    void setUp() {
        store = mock(PayrollPreparationStore.class);
        stepUp = mock(PayrollPreparationStepUpGuard.class);
        canonical = new PayrollPreparationCanonical(
                mapper, "pay013-cursor-secret-32-byte-minimum",
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new PayrollPreparationService(
                store, canonical, stepUp, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsClosedG2BodyAllRunTypesAndReturnsAcceptedReceiptShape()
            throws Exception {
        for (PayrollPreparationModels.RunType runType
                : PayrollPreparationModels.RunType.values()) {
            String json = """
                    {
                      "preparationId":"%s",
                      "runType":"%s",
                      "payrollEntityId":"%s",
                      "payGroupId":"%s",
                      "payPeriodId":"%s",
                      "asOf":"%s",
                      "inputSnapshotDigest":"%s"
                    }
                    """.formatted(PREPARATION, runType, LEGAL_ENTITY,
                    PAY_GROUP, PAY_PERIOD, NOW, TARGET_DIGEST);
            assertThat(mapper.readValue(
                    json, PayrollPreparationModels.PrepareCommand.class).runType())
                    .isEqualTo(runType);
        }
        assertThatThrownBy(() -> mapper.readValue(commandJson()
                        .replace("\n}", ",\n\"clientClaim\":\"forbidden\"\n}"),
                PayrollPreparationModels.PrepareCommand.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue(commandJson().replaceFirst(
                        "\"preparationId\"", "\"preparationId\":\"" + PREPARATION
                                + "\",\"preparationId\""),
                PayrollPreparationModels.PrepareCommand.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);

        ResponseStatus status = java.util.Arrays.stream(
                        PayrollPreparationController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("prepare"))
                .findFirst().orElseThrow().getAnnotation(ResponseStatus.class);
        assertThat(status.value()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void prepareSuccessWireBodyIsTheClosedDirectG2Receipt() {
        PayrollPreparationService transport = mock(PayrollPreparationService.class);
        PayrollPreparationModels.PayrollCommandReceipt receipt =
                storedReceipt(canonical.requestDigest(command()), UUID.randomUUID());
        when(transport.prepare(any(), any(), any(), any())).thenReturn(receipt);
        PayrollPreparationController controller = new PayrollPreparationController(
                transport, mock(PayrollPreparationScopeResolver.class));

        PayrollPreparationModels.PayrollCommandReceipt response = controller.prepare(
                actor(PayrollPreparationRoute.PREPARE), IDEMPOTENCY, 0,
                "decision-41", "signed-challenge", receipt.correlationId(), command());
        com.fasterxml.jackson.databind.JsonNode body = mapper.valueToTree(response);

        assertThat(body.fieldNames()).toIterable().containsExactly(
                "receiptId", "commandType", "originatingAction",
                "subjectPrincipalPublicId", "populationScopeDigest",
                "fieldPolicyRevision", "purposeCode", "authorizationRevision",
                "status", "correlationId", "requestDigest", "createdAt",
                "aggregateVersion", "resultRef");
        assertThat(body.has("data")).isFalse();
        assertThat(body.has("success")).isFalse();
        assertThat(body.has("timestamp")).isFalse();
    }

    @Test
    void freshPrepareCommitsOneExactReceiptWithFrozenAuthorityFacts() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand command = command();
        PayrollPreparationModels.PreparationCandidate candidate = candidate(actor, command);
        PayrollStepUpEvidence evidence = evidence();
        when(store.command(1, SUBJECT, IDEMPOTENCY)).thenReturn(Optional.empty());
        when(stepUp.require(actor, command, headers())).thenReturn(evidence);
        when(store.candidate(actor, command)).thenReturn(candidate);

        UUID correlation = UUID.randomUUID();
        PayrollPreparationModels.PayrollCommandReceipt receipt =
                service.prepare(actor, command, headers(), correlation);

        assertThat(receipt.commandType()).isEqualTo("RUN_PREPARE");
        assertThat(receipt.originatingAction()).isEqualTo("PAY_RUN_PREPARE");
        assertThat(receipt.purposeCode()).isEqualTo("PAYROLL_PROCESSING");
        assertThat(receipt.status())
                .isEqualTo(PayrollPreparationModels.ReceiptStatus.SUCCEEDED);
        assertThat(receipt.aggregateVersion()).isEqualTo(2);
        assertThat(receipt.correlationId()).isEqualTo(correlation);
        assertThat(mapper.valueToTree(receipt).fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "receiptId", "commandType", "originatingAction",
                        "subjectPrincipalPublicId", "populationScopeDigest",
                        "fieldPolicyRevision", "purposeCode",
                        "authorizationRevision", "status", "correlationId",
                        "requestDigest", "createdAt", "aggregateVersion", "resultRef");

        ArgumentCaptor<PayrollPreparationModels.PreparationCommit> committed =
                ArgumentCaptor.forClass(PayrollPreparationModels.PreparationCommit.class);
        verify(store).commit(committed.capture());
        assertThat(committed.getValue().candidate().population().sources())
                .extracting(PayrollPreparationModels.SourceRevision::sourceType)
                .containsExactly(PayrollPreparationModels.SourceType.values());
        assertThat(committed.getValue().candidate().population().sources().getFirst()
                .sourceId()).isEqualTo(TARGET_SOURCE);
        assertThat(committed.getValue().candidate().population().sources().getFirst()
                .revision()).isEqualTo(73);
        assertThat(committed.getValue().candidate().population().sources().getLast()
                .sourceId()).isEqualTo(SEMANTIC_SOURCE);
        assertThat(committed.getValue().candidate().population().sources().getLast()
                .revision()).isEqualTo(91);
        assertThat(committed.getValue().stepUp()).isEqualTo(evidence);

        var order = inOrder(store, stepUp);
        order.verify(store).lockPreparation(1, PREPARATION);
        order.verify(store).command(1, SUBJECT, IDEMPOTENCY);
        order.verify(stepUp).require(actor, command, headers());
        order.verify(store).candidate(actor, command);
        order.verify(store).commit(any());
    }

    @Test
    void sameKeySameBodyConsumesFreshChallengeAndReplaysWithoutBusinessEffects() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand command = command();
        String digest = canonical.requestDigest(command);
        PayrollPreparationModels.PayrollCommandReceipt stored =
                storedReceipt(digest, UUID.randomUUID());
        when(store.command(1, SUBJECT, IDEMPOTENCY)).thenReturn(Optional.of(
                new PayrollPreparationModels.StoredCommand(
                        1, SUBJECT, IDEMPOTENCY, digest, PREPARATION, stored)));
        when(stepUp.require(actor, command, headers())).thenReturn(evidence());

        assertThat(service.prepare(actor, command, headers(), UUID.randomUUID()))
                .isSameAs(stored);
        verify(stepUp).require(actor, command, headers());
        verify(store).consumeStepUp(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(PREPARATION),
                org.mockito.ArgumentMatchers.eq(SUBJECT),
                org.mockito.ArgumentMatchers.eq(IDEMPOTENCY),
                org.mockito.ArgumentMatchers.eq(digest),
                any(PayrollStepUpEvidence.class),
                org.mockito.ArgumentMatchers.eq("IDEMPOTENT_REPLAY"),
                org.mockito.ArgumentMatchers.eq(NOW));
        verify(store, never()).candidate(any(), any());
        verify(store, never()).commit(any());
    }

    @Test
    void consumedCryptographicChallengeCannotReadAnIdempotentReplay() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand command = command();
        String digest = canonical.requestDigest(command);
        PayrollStepUpEvidence replayed = evidence();
        when(store.command(1, SUBJECT, IDEMPOTENCY)).thenReturn(Optional.of(
                new PayrollPreparationModels.StoredCommand(
                        1, SUBJECT, IDEMPOTENCY, digest, PREPARATION,
                        storedReceipt(digest, UUID.randomUUID()))));
        when(stepUp.require(actor, command, headers())).thenReturn(replayed);
        doThrow(new BaseException(
                ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                "The Payroll step-up challenge was already consumed."))
                .when(store).consumeStepUp(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(PREPARATION),
                        org.mockito.ArgumentMatchers.eq(SUBJECT),
                        org.mockito.ArgumentMatchers.eq(IDEMPOTENCY),
                        org.mockito.ArgumentMatchers.eq(digest),
                        org.mockito.ArgumentMatchers.eq(replayed),
                        org.mockito.ArgumentMatchers.eq("IDEMPOTENT_REPLAY"),
                        org.mockito.ArgumentMatchers.eq(NOW));

        assertCode(() -> service.prepare(
                actor, command, headers(), UUID.randomUUID()),
                ErrorCode.STEP_UP_CHALLENGE_REPLAY);
        verify(store, never()).candidate(any(), any());
        verify(store, never()).commit(any());
    }

    @Test
    void replayWithBogusOrReplayedChallengeNeverReturnsStoredOutcome() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand command = command();
        String digest = canonical.requestDigest(command);
        when(store.command(1, SUBJECT, IDEMPOTENCY)).thenReturn(Optional.of(
                new PayrollPreparationModels.StoredCommand(
                        1, SUBJECT, IDEMPOTENCY, digest, PREPARATION,
                        storedReceipt(digest, UUID.randomUUID()))));
        when(stepUp.require(actor, command, headers())).thenThrow(
                new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY, "replayed"));

        assertCode(() -> service.prepare(
                actor, command, headers(), UUID.randomUUID()),
                ErrorCode.STEP_UP_CHALLENGE_REPLAY);
        verify(store, never()).candidate(any(), any());
        verify(store, never()).commit(any());
    }

    @Test
    void idempotencyBodyMismatchConflictsBeforeChallengeOrSourceCalls() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand original = command();
        PayrollPreparationModels.PrepareCommand changed =
                new PayrollPreparationModels.PrepareCommand(
                        PREPARATION, PayrollPreparationModels.RunType.RETRO,
                        LEGAL_ENTITY, PAY_GROUP, PAY_PERIOD, NOW, TARGET_DIGEST);
        String originalDigest = canonical.requestDigest(original);
        when(store.command(1, SUBJECT, IDEMPOTENCY)).thenReturn(Optional.of(
                new PayrollPreparationModels.StoredCommand(
                        1, SUBJECT, IDEMPOTENCY, originalDigest, PREPARATION,
                        storedReceipt(originalDigest, UUID.randomUUID()))));

        assertCode(() -> service.prepare(
                actor, changed, headers(), UUID.randomUUID()),
                ErrorCode.RESOURCE_CONFLICT);
        verifyNoInteractions(stepUp);
        verify(store, never()).candidate(any(), any());
        verify(store, never()).commit(any());
    }

    @Test
    void unavailableCandidateFailsClosedAfterChallengeWithoutCommit() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand command = command();
        when(store.command(1, SUBJECT, IDEMPOTENCY)).thenReturn(Optional.empty());
        when(stepUp.require(actor, command, headers())).thenReturn(evidence());
        when(store.candidate(actor, command)).thenReturn(null);

        assertCode(() -> service.prepare(
                actor, command, headers(), UUID.randomUUID()),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verify(store, never()).commit(any());
    }

    @Test
    void futureAsOfFailsBeforeLockChallengeOrSourceEffects() {
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.PREPARE);
        PayrollPreparationModels.PrepareCommand future =
                new PayrollPreparationModels.PrepareCommand(
                        PREPARATION, PayrollPreparationModels.RunType.REGULAR,
                        LEGAL_ENTITY, PAY_GROUP, PAY_PERIOD,
                        NOW.plusSeconds(1), TARGET_DIGEST);
        assertCode(() -> service.prepare(
                actor, future, headers(), UUID.randomUUID()),
                ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(store, stepUp);
    }

    @Test
    void authoritativePortsRequireRealIdentityRevisionAndDigest() {
        PayrollPreparationModels.SourceLine source = sourceLine();
        PayrollPreparationModels.PreparedLine prepared = preparedLine(source);
        PayrollPreparationPopulationSnapshotPort.Materialization population =
                new PayrollPreparationPopulationSnapshotPort.Materialization(
                        PayrollPreparationPopulationSnapshotPort.Availability.EXACT,
                        TARGET_SOURCE, 73, TARGET_DIGEST, List.of(source));
        PayrollPreparationSemanticSnapshotPort.Resolution semantic =
                PayrollPreparationSemanticSnapshotPort.Resolution.exact(
                        SEMANTIC_SOURCE, 91, "PAY_RULE_AST_V1:1",
                        SEMANTIC_DIGEST, List.of(prepared));
        assertThat(population.projectionId()).isEqualTo(TARGET_SOURCE);
        assertThat(population.projectionRevision()).isEqualTo(73);
        assertThat(semantic.sourceId()).isEqualTo(SEMANTIC_SOURCE);
        assertThat(semantic.sourceRevision()).isEqualTo(91);
        assertThatThrownBy(() -> new PayrollPreparationPopulationSnapshotPort.Materialization(
                PayrollPreparationPopulationSnapshotPort.Availability.EXACT,
                TARGET_SOURCE, 0, TARGET_DIGEST, List.of(source)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayrollPreparationSemanticSnapshotPort.Resolution.exact(
                SEMANTIC_SOURCE, 0, "PAY_RULE_AST_V1:1",
                SEMANTIC_DIGEST, List.of(prepared)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PayrollPreparationModels.PreparationCandidate candidate(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command) {
        UUID formulaId =
                UUID.fromString("00000000-0000-0000-0000-000000000220");
        PayrollPreparationModels.FormulaRef formula =
                new PayrollPreparationModels.FormulaRef(
                        formulaId, 7, "7".repeat(64));
        PayrollPreparationModels.SourceLine source = sourceLine();
        PayrollPreparationModels.PreparedLine prepared = preparedLine(source);
        List<PayrollPreparationModels.SourceRevision> sources = List.of(
                new PayrollPreparationModels.SourceRevision(
                        PayrollPreparationModels.SourceType.TARGET_POPULATION_PROJECTION,
                        TARGET_SOURCE, 73, TARGET_DIGEST,
                        null, null, 1,
                        PayrollPreparationModels.SourceStatus.CURRENT),
                new PayrollPreparationModels.SourceRevision(
                        PayrollPreparationModels.SourceType.COMPENSATION_PLAN_SNAPSHOT,
                        SOURCE_SNAPSHOT, 5, SOURCE_DIGEST,
                        UUID.randomUUID(), 4L, 1,
                        PayrollPreparationModels.SourceStatus.CURRENT),
                new PayrollPreparationModels.SourceRevision(
                        PayrollPreparationModels.SourceType.PAYROLL_FOUNDATION_POLICY,
                        UUID.randomUUID(), 2, "8".repeat(64),
                        null, null, null,
                        PayrollPreparationModels.SourceStatus.CURRENT),
                new PayrollPreparationModels.SourceRevision(
                        PayrollPreparationModels.SourceType.PAYROLL_FORMULA_SET,
                        UUID.randomUUID(), 7, FORMULA_DIGEST,
                        null, null, 1,
                        PayrollPreparationModels.SourceStatus.CURRENT),
                new PayrollPreparationModels.SourceRevision(
                        PayrollPreparationModels.SourceType.SEMANTIC_MAPPING_SET,
                        SEMANTIC_SOURCE, 91, SEMANTIC_DIGEST,
                        null, null, 1,
                        PayrollPreparationModels.SourceStatus.CURRENT));
        List<PayrollPreparationModels.PreflightDecision> decisions = List.of(
                pass("PURPOSE_AUTHORITY_VERIFIED", null),
                pass("TARGET_POPULATION_CURRENT", sources.get(0)),
                pass("COMPENSATION_SNAPSHOT_COMPLETE", sources.get(1)),
                pass("SOURCE_APPROVAL_VERIFIED", sources.get(1)),
                pass("FOUNDATION_POLICY_PUBLISHED", sources.get(2)),
                pass("FORMULA_SET_PUBLISHED", sources.get(3)),
                pass("SEMANTIC_MAPPING_EXACT", sources.get(4)));
        PayrollPreparationModels.PopulationResponse population =
                new PayrollPreparationModels.PopulationResponse(
                        "population-revision-73", TARGET_DIGEST,
                        actor.scope().projectionRevision(), actor.scope().scopeDigest(),
                        command.selection(actor.subject().populationKind()), sources,
                        new PayrollPreparationModels.PreflightSummary(
                                PayrollPreparationModels.PreflightStatus.PASS, decisions),
                        new PayrollPreparationModels.PopulationPage(
                                null, "9".repeat(64)));
        PayrollPreparationModels.FrozenMaterial material =
                new PayrollPreparationModels.FrozenMaterial(
                        SOURCE_SNAPSHOT, 5, SOURCE_DIGEST,
                        FORMULA_DIGEST, "a".repeat(64), SEMANTIC_DIGEST,
                        "PAY_RULE_AST_V1:1", "GBP", List.of(formula),
                        List.of(prepared));
        return new PayrollPreparationModels.PreparationCandidate(population, material);
    }

    private PayrollPreparationModels.PreflightDecision pass(
            String code, PayrollPreparationModels.SourceRevision source) {
        return new PayrollPreparationModels.PreflightDecision(
                code, PayrollPreparationModels.DecisionStatus.PASS,
                source == null ? null : source.sourceType(),
                source == null ? null : source.sourceId(),
                source == null ? null : source.revision(), "b".repeat(64));
    }

    private PayrollPreparationModels.SourceLine sourceLine() {
        return new PayrollPreparationModels.SourceLine(
                1, UUID.randomUUID(), UUID.randomUUID(), SOURCE_SNAPSHOT,
                5, SOURCE_DIGEST, UUID.randomUUID(), PAY_PERIOD, 1,
                UUID.randomUUID(), UUID.randomUUID(), "BASE_PAY", "GBP",
                new BigDecimal("125.0000"), LocalDate.of(2026, 10, 1),
                null, "c".repeat(64));
    }

    private PayrollPreparationModels.PreparedLine preparedLine(
            PayrollPreparationModels.SourceLine source) {
        return new PayrollPreparationModels.PreparedLine(
                source.sourceInputId(), source.sourcePublicId(), source.sourceEventId(),
                source.snapshotId(), source.snapshotRevision(),
                source.snapshotPayloadDigest(), source.planId(), source.cycleId(),
                source.lineSequence(), source.workerId(), source.assignmentId(),
                source.componentCode(), source.currency(), source.approvedAmount(),
                source.effectiveFrom(), source.effectiveTo(), source.payloadDigest(),
                "{\"bindingDigest\":\"" + BINDING_DIGEST + "\"}",
                BINDING_DIGEST);
    }

    private PayrollPreparationAuthority.Actor actor(PayrollPreparationRoute route) {
        PayrollPreparationAuthority.Subject subject =
                new PayrollPreparationAuthority.Subject(
                        1, 101, SUBJECT, route, route.capabilityContractKey(),
                        route.purposeCode(), "PAYROLL_PROCESSING", "scope-key-201",
                        "LEGAL_ENTITY", 7, 9, "decision-9", NOW.plusSeconds(300),
                        "ELEVATED", Set.of(
                        "ACTION.HRIS_PAYROLL_RUN:EXECUTE",
                        "DATA.HRIS_PAYROLL_STATUS:VIEW"));
        return new PayrollPreparationAuthority.Actor(
                subject, new PayrollPreparationAuthority.Scope(
                "scope-revision-9", SCOPE_DIGEST,
                Set.of(new PayrollPreparationAuthority.PopulationTuple(
                        LEGAL_ENTITY, PAY_GROUP, subject.contextScopeKey()))));
    }

    private PayrollPreparationModels.PrepareCommand command() {
        return new PayrollPreparationModels.PrepareCommand(
                PREPARATION, PayrollPreparationModels.RunType.REGULAR,
                LEGAL_ENTITY, PAY_GROUP, PAY_PERIOD, NOW, TARGET_DIGEST);
    }

    private String commandJson() throws Exception {
        return mapper.writeValueAsString(command());
    }

    private PayrollPreparationStepUpGuard.Headers headers() {
        return new PayrollPreparationStepUpGuard.Headers(
                "signed-challenge", IDEMPOTENCY, "decision-9", 0);
    }

    private PayrollStepUpEvidence evidence() {
        return new PayrollStepUpEvidence(
                "challenge-1", "nonce-1", "issuer",
                NOW.plusSeconds(300), "d".repeat(64), "decision-9");
    }

    private PayrollPreparationModels.PayrollCommandReceipt storedReceipt(
            String requestDigest, UUID correlation) {
        return new PayrollPreparationModels.PayrollCommandReceipt(
                UUID.randomUUID(), "RUN_PREPARE", "PAY_RUN_PREPARE", SUBJECT,
                SCOPE_DIGEST, 7, "PAYROLL_PROCESSING", 9,
                PayrollPreparationModels.ReceiptStatus.SUCCEEDED,
                correlation, requestDigest, NOW, 2L,
                "payroll-run:" + UUID.randomUUID());
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ErrorCode expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
}
