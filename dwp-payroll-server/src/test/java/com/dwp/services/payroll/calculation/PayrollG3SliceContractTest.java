package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollCalculationAuthority;
import com.dwp.services.payroll.security.PayrollFieldProjectionDecisionPort;
import com.dwp.services.payroll.security.PayrollFieldProjectionGuard;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.dwp.services.payroll.security.PayrollStepUpHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ActionCommand;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandType;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PayrollCommandReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunType;
import static com.dwp.services.payroll.calculation.PayrollCalculationTestSupport.NOW;
import static com.dwp.services.payroll.calculation.PayrollCalculationTestSupport.RUN_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayrollG3SliceContractTest {

    @Test
    void closedG2TransportAndCurrentHeadersAreExact() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        PayrollCommandReceipt receipt = new PayrollCommandReceipt(
                UUID.randomUUID(), CommandType.RUN_CALCULATE.name(),
                CommandType.RUN_CALCULATE.originatingAction(), UUID.randomUUID(),
                "a".repeat(64), 7, "PAYROLL_PROCESSING", 41,
                ReceiptStatus.ACCEPTED, UUID.randomUUID(), "b".repeat(64),
                NOW, 1L, "payroll-run:" + RUN_ID);
        JsonNode receiptJson = mapper.valueToTree(receipt);
        assertThat(fields(receiptJson)).containsExactlyInAnyOrder(
                "receiptId", "commandType", "originatingAction",
                "subjectPrincipalPublicId", "populationScopeDigest",
                "fieldPolicyRevision", "purposeCode", "authorizationRevision",
                "status", "correlationId", "requestDigest", "createdAt",
                "aggregateVersion", "resultRef");
        assertThat(receipt.originatingAction()).isEqualTo("PAY_RUN_CALCULATE");
        assertThat(CommandType.RUN_APPROVE.originatingAction())
                .isEqualTo("PAY_RUN_APPROVE");
        assertThat(CommandType.RUN_FINALIZE.originatingAction())
                .isEqualTo("PAY_RUN_FINALIZE");

        assertThat(PayrollCalculationController.IDEMPOTENCY).isEqualTo("Idempotency-Key");
        assertThat(PayrollCalculationController.EXPECTED_VERSION)
                .isEqualTo("X-DWP-Expected-Object-Version");
        assertThat(PayrollCalculationController.EXPECTED_DECISION)
                .isEqualTo("X-DWP-Expected-Decision-Revision");
        assertThat(PayrollCalculationController.STEP_UP)
                .isEqualTo("X-DWP-Step-Up-Challenge");
        assertThat(PayrollCalculationController.CORRELATION).isEqualTo("X-Correlation-ID");

        assertThatThrownBy(() -> mapper.readValue(
                "{\"reasonCode\":\"CALCULATE\",\"unexpected\":true}",
                ActionCommand.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("unknown or duplicate field");
        assertThatThrownBy(() -> mapper.readValue(
                "{\"reasonCode\":\"CALCULATE\"} {}", ActionCommand.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("trailing JSON content");

        String omittedOptionals = "{\"reasonCode\":\" APPROVE \"}";
        ActionCommand omitted = mapper.readValue(
                omittedOptionals, ActionCommand.class);
        PayrollCalculationCanonical canonical =
                new PayrollCalculationCanonical(mapper);
        assertThat(canonical.digest(omitted))
                .isEqualTo(canonical.digest(mapper.readTree(omittedOptionals)));
        String upperUuid = "00000000-0000-4000-8000-00000000ABCD";
        String presentOptionals = "{\"reasonCode\":\"APPROVE\","
                + "\"approvalReceiptId\":\"" + upperUuid + "\","
                + "\"comment\":\"  \"}";
        ActionCommand present = mapper.readValue(
                presentOptionals, ActionCommand.class);
        assertThat(present.approvalReceiptId()).isEqualTo(upperUuid);
        assertThat(present.comment()).isEqualTo("  ");
        assertThat(canonical.digest(present))
                .isEqualTo(canonical.digest(mapper.readTree(presentOptionals)));
        assertThat(new ActionCommand("😀".repeat(64), null, ""))
                .isNotNull();
        assertThatThrownBy(() -> new ActionCommand(
                "😀".repeat(65), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springHttpMapperRejectsTrailingRootBeforeControllerSideEffects()
            throws Exception {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        ActionCommandProbe probe = new ActionCommandProbe();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(probe)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();

        mvc.perform(post("/pay011-action-command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"CALCULATE\"} {}"))
                .andExpect(status().isBadRequest());
        assertThat(probe.calls()).isZero();
    }

    @Test
    void payGold001IsDeterministicAndByteEquivalentOnReplay() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationEngine engine = new PayrollCalculationEngine(
                canonical, PayrollCalculationTestSupport.exactSemanticMappings());
        var snapshot = PayrollCalculationTestSupport.preparation(
                1, RUN_ID, 1, NOW).snapshot();

        var first = engine.calculate(snapshot);
        var replay = engine.calculate(snapshot);

        assertThat(first.lines()).hasSize(3);
        assertThat(first.gross()).isEqualByComparingTo("3200.00");
        assertThat(first.net()).isEqualByComparingTo("3100.00");
        assertThat(first.controlTotals()).extracting(
                PayrollCalculationModels.ControlTotal::code,
                PayrollCalculationModels.ControlTotal::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GROSS", new BigDecimal("3200.0000")),
                        org.assertj.core.groups.Tuple.tuple("DEDUCTIONS", new BigDecimal("100.0000")),
                        org.assertj.core.groups.Tuple.tuple("NET", new BigDecimal("3100.0000")),
                        org.assertj.core.groups.Tuple.tuple("EMPLOYER_COST", new BigDecimal("0.0000")));
        assertThat(replay.resultDigest()).isEqualTo(first.resultDigest());
        assertThat(canonical.bytes(replay)).isEqualTo(canonical.bytes(first));
        assertThat(first.lines()).extracting(
                PayrollCalculationModels.CalculatedLine::traceDigest)
                .containsExactlyElementsOf(replay.lines().stream()
                        .map(PayrollCalculationModels.CalculatedLine::traceDigest).toList());
    }

    @Test
    void recoveryAttemptAndRematerializedLocalIdsPreserveSemanticDigests() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationEngine engine = new PayrollCalculationEngine(
                canonical, PayrollCalculationTestSupport.exactSemanticMappings());
        var attemptOne = PayrollCalculationTestSupport.preparation(
                1, RUN_ID, 1, NOW).snapshot();
        var attemptTwo = copySnapshot(attemptOne, 2, attemptOne.inputs());
        var rematerialized = copySnapshot(attemptOne, 1,
                attemptOne.inputs().stream().map(input ->
                        new PayrollCalculationModels.SourceInput(
                                input.sourceInputId() + 10_000,
                                input.sourcePublicId(), input.sourceEventId(),
                                input.snapshotId(), input.snapshotRevision(),
                                input.snapshotPayloadDigest(), input.planId(),
                                input.cycleId(), input.lineSequence(), input.workerId(),
                                input.assignmentId(), input.componentCode(), input.currency(),
                                input.approvedAmount(), input.effectiveFrom(),
                                input.effectiveTo(), input.payloadDigest())).toList());

        var original = engine.calculate(attemptOne);
        var recovered = engine.calculate(attemptTwo);
        var replayedMaterialization = engine.calculate(rematerialized);

        assertSemanticEquality(original, recovered);
        assertSemanticEquality(original, replayedMaterialization);
        List<UUID> originalWorkerResultIds = original.workers().stream()
                .map(PayrollCalculationModels.WorkerResult::workerResultId).toList();
        assertThat(recovered.workers()).extracting(
                        PayrollCalculationModels.WorkerResult::workerResultId)
                .doesNotContainAnyElementsOf(originalWorkerResultIds);
        assertThat(replayedMaterialization.workers()).extracting(
                        PayrollCalculationModels.WorkerResult::workerResultId)
                .containsExactlyElementsOf(originalWorkerResultIds);
        assertThat(recovered.lines()).extracting(
                        PayrollCalculationModels.CalculatedLine::resultLineId)
                .doesNotContainAnyElementsOf(original.lines().stream()
                        .map(PayrollCalculationModels.CalculatedLine::resultLineId).toList());
    }

    @Test
    void aggregateOverflowIsTypedAndCannotProduceAnOutcome() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationEngine engine = new PayrollCalculationEngine(
                canonical, PayrollCalculationTestSupport.exactSemanticMappings(Map.of(
                "BASE", new BigDecimal("999999999999999.9999"),
                "ALLOWANCE", new BigDecimal("999999999999999.9999"))));

        assertThatThrownBy(() -> engine.calculate(PayrollCalculationTestSupport.preparation(
                1, RUN_ID, 1, NOW).snapshot()))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("NUMERIC(19,4)");
    }

    @Test
    void operationIdsPathsAndStatusesMatchPinnedV41OpenApi() {
        Map<String, String> operationIds = Map.of(
                "calculate", "calculatePayrollRun",
                "run", "getPayrollRun",
                "approve", "approvePayrollRun",
                "finalizeRun", "finalizePayrollRun",
                "receipt", "getPayrollCommandReceipt");
        Map<String, String> paths = Map.of(
                "calculate", "/runs/{runId}/attempts",
                "run", "/runs/{runId}",
                "approve", "/runs/{runId}/approvals",
                "finalizeRun", "/runs/{runId}/finalize",
                "receipt", "/command-receipts/{receiptId}");
        Arrays.stream(PayrollCalculationController.class.getDeclaredMethods())
                .filter(method -> operationIds.containsKey(method.getName()))
                .forEach(method -> {
                    assertThat(method.getAnnotation(Operation.class).operationId())
                            .isEqualTo(operationIds.get(method.getName()));
                    String path = method.isAnnotationPresent(PostMapping.class)
                            ? method.getAnnotation(PostMapping.class).value()[0]
                            : method.getAnnotation(GetMapping.class).value()[0];
                    assertThat(path).isEqualTo(paths.get(method.getName()));
                    if (method.isAnnotationPresent(PostMapping.class)) {
                        assertThat(method.getAnnotation(ResponseStatus.class).value())
                                .isEqualTo(HttpStatus.ACCEPTED);
                    }
                });
    }

    @Test
    void idempotencyCasAndTenantIsolationFailClosed() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationTestSupport.InMemoryStore store =
                new PayrollCalculationTestSupport.InMemoryStore();
        PayrollCalculationService service = PayrollCalculationTestSupport.service(
                store, canonical, mock(PayrollHighRiskCommandGuard.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PayrollCalculationAuthority.Actor actor =
                PayrollCalculationTestSupport.calculator(1, 101);
        ActionCommand command = new ActionCommand("REGULAR_RUN", null, null);
        store.seedPreparedRun(PayrollCalculationTestSupport.preparedRun(1, 101, RUN_ID));

        PayrollCommandReceipt first = service.calculate(
                actor, RUN_ID, "pay-gold-003", 2, UUID.randomUUID(), command);
        PayrollCommandReceipt replay = service.calculate(
                actor, RUN_ID, "pay-gold-003", 2,
                first.correlationId(), command);

        assertThat(replay).isEqualTo(first);
        assertThat(store.attemptCount(1, RUN_ID)).isEqualTo(1);
        assertThat(store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();
        assertThatThrownBy(() -> service.calculate(
                actor, RUN_ID, "pay-gold-003", 2, first.correlationId(),
                new ActionCommand("CHANGED", null, null)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> service.calculate(
                actor, RUN_ID, "stale-command", 0, UUID.randomUUID(), command))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThat(store.attemptCount(1, RUN_ID)).isEqualTo(1);
        assertThat(store.prepareCalls()).isZero();
        assertThat(store.freezeCalls()).isZero();
        assertThat(store.cloneCalls()).isZero();

        PayrollCalculationAuthority.Actor otherTenant = PayrollCalculationTestSupport.actor(
                2, 202, PayrollCalculationAuthority.Action.RUN_VIEW,
                "PAYROLL_PROCESSING", "PAY_GROUP",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_PAYROLL_STATUS:VIEW"));
        assertThatThrownBy(() -> service.run(otherTenant, RUN_ID))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void approvalAndFinalizationEnforceCasSodReplayAndAtomicEffects() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationTestSupport.InMemoryStore store =
                new PayrollCalculationTestSupport.InMemoryStore();
        PayrollHighRiskCommandGuard guard = mock(PayrollHighRiskCommandGuard.class);
        when(guard.require(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(new PayrollStepUpEvidence(
                        "challenge-1", "nonce-1", "issuer",
                        NOW.plusSeconds(300), "e".repeat(64), "decision-41"));
        PayrollCalculationService service = PayrollCalculationTestSupport.service(
                store, canonical, guard, Clock.fixed(NOW, ZoneOffset.UTC));
        store.seedPreparedRun(run(
                1, RunType.REGULAR, RunState.VALIDATED, 101, 1,
                new BigDecimal("3200.0000"), new BigDecimal("3100.0000"),
                "9".repeat(64), null, null, null, null));
        var approver = PayrollCalculationTestSupport.actor(
                1, 202, PayrollCalculationAuthority.Action.APPROVE,
                "PAYROLL_PROCESSING", "PAY_GROUP",
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_APPROVE:APPROVE"));
        ActionCommand approval = new ActionCommand("APPROVE_REGULAR", null, null);
        PayrollStepUpHeaders approvalHeaders = new PayrollStepUpHeaders(
                "signed", "approve-1", "decision-41", 1);

        assertThatThrownBy(() -> service.approve(
                approver, RUN_ID, "approve-stale", 0, UUID.randomUUID(),
                approval, approvalHeaders))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        var maker = PayrollCalculationTestSupport.actor(
                1, 101, PayrollCalculationAuthority.Action.APPROVE,
                "PAYROLL_PROCESSING", "PAY_GROUP",
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_APPROVE:APPROVE"));
        assertThatThrownBy(() -> service.approve(
                maker, RUN_ID, "approve-maker", 1, UUID.randomUUID(),
                approval, approvalHeaders))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SOD_CONFLICT));
        assertNoCommandEffects(store, 1, 0, 0, 0);

        var approved = service.approve(
                approver, RUN_ID, "approve-1", 1, UUID.randomUUID(),
                approval, approvalHeaders);
        assertThat(service.approve(
                approver, RUN_ID, "approve-1", 1, approved.correlationId(),
                approval, approvalHeaders)).isEqualTo(approved);
        assertThat(store.currentRun(1, RUN_ID).orElseThrow().state())
                .isEqualTo(RunState.APPROVED);
        assertThat(store.outboxCount(1, RUN_ID, "PayrollRunApproved")).isEqualTo(1);
        assertNoCommandEffects(store, 2, 1, 1, 1);

        var finalizer = PayrollCalculationTestSupport.actor(
                1, 202, PayrollCalculationAuthority.Action.FINALIZE,
                "PAYROLL_PROCESSING", "PAY_GROUP",
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_RELEASE:EXECUTE"));
        ActionCommand finalization = new ActionCommand("FINALIZE_REGULAR", null, null);
        PayrollStepUpHeaders finalizationHeaders = new PayrollStepUpHeaders(
                "signed", "finalize-1", "decision-41", 2);
        assertThatThrownBy(() -> service.finalizeRun(
                finalizer, RUN_ID, "finalize-stale", 1, UUID.randomUUID(),
                finalization, finalizationHeaders))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertNoCommandEffects(store, 2, 1, 1, 1);

        service.finalizeRun(
                finalizer, RUN_ID, "finalize-1", 2, UUID.randomUUID(),
                finalization, finalizationHeaders);
        assertThat(store.currentRun(1, RUN_ID).orElseThrow().state())
                .isEqualTo(RunState.FINALIZED);
        assertThat(store.outboxCount(1, RUN_ID, "PayrollFinalized")).isEqualTo(1);
        assertNoCommandEffects(store, 3, 2, 2, 2);
    }

    @Test
    void commandScopeExcludesNonRegularAndReceiptLookupReauthorizesOrigin() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationTestSupport.InMemoryStore store =
                new PayrollCalculationTestSupport.InMemoryStore();
        PayrollCalculationService service = PayrollCalculationTestSupport.service(
                store, canonical, mock(PayrollHighRiskCommandGuard.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        store.seedPreparedRun(run(
                1, RunType.OFF_CYCLE, RunState.PREPARING, 101, 0,
                BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                null, null, null, null, null));
        assertThatThrownBy(() -> service.calculate(
                PayrollCalculationTestSupport.calculator(1, 101), RUN_ID,
                "off-cycle", 1, UUID.randomUUID(),
                new ActionCommand("CALCULATE", null, null)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertNoCommandEffects(store, 1, 0, 0, 0);

        PayrollCalculationTestSupport.InMemoryStore receiptStore =
                new PayrollCalculationTestSupport.InMemoryStore();
        PayrollCalculationService receiptService = PayrollCalculationTestSupport.service(
                receiptStore, canonical, mock(PayrollHighRiskCommandGuard.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        receiptStore.seedPreparedRun(run(
                2, RunType.REGULAR, RunState.READY, 101, 0,
                BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                null, null, null, null, "LEGAL_ENTITY"));
        var legalCalculator = PayrollCalculationTestSupport.actor(
                1, 101, PayrollCalculationAuthority.Action.CALCULATE,
                "PAYROLL_PROCESSING", "LEGAL_ENTITY",
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_RUN:EXECUTE"));
        var issued = receiptService.calculate(
                legalCalculator, RUN_ID, "legal-calculate", 2, UUID.randomUUID(),
                new ActionCommand("CALCULATE", null, null));
        var receiptActor = PayrollCalculationTestSupport.actor(
                1, 101, PayrollCalculationAuthority.Action.RECEIPT_VIEW,
                "COMMAND_STATUS", "ORIGINATING_COMMAND_SCOPE",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_COMMAND_RECEIPT:VIEW",
                        "ACTION.HRIS_PAYROLL_RUN:EXECUTE"));
        assertThat(receiptService.receipt(receiptActor, issued.receiptId()))
                .isEqualTo(issued);

        var otherTenant = PayrollCalculationTestSupport.actor(
                2, 101, PayrollCalculationAuthority.Action.RECEIPT_VIEW,
                "COMMAND_STATUS", "ORIGINATING_COMMAND_SCOPE",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_COMMAND_RECEIPT:VIEW",
                        "ACTION.HRIS_PAYROLL_RUN:EXECUTE"));
        assertOpaque(() -> receiptService.receipt(otherTenant, issued.receiptId()));

        var receiptSubject = receiptActor.subject();
        var staleAuthority = new PayrollCalculationAuthority.Actor(
                new PayrollCalculationAuthority.Subject(
                        receiptSubject.tenantId(), receiptSubject.actorId(),
                        receiptSubject.subjectPrincipalPublicId(),
                        receiptSubject.routeAction(), receiptSubject.routeContractKey(),
                        receiptSubject.capabilityContractKey(), receiptSubject.purposeCode(),
                        receiptSubject.contextKey(), receiptSubject.contextScopeKey(),
                        receiptSubject.populationKind(), 6, 40,
                        receiptSubject.decisionRevision(), receiptSubject.revalidateAt(),
                        receiptSubject.accessMode(), receiptSubject.permissions()),
                receiptActor.scope());
        assertOpaque(() -> receiptService.receipt(staleAuthority, issued.receiptId()));

        PayrollCalculationTestSupport.InMemoryStore nonLegalStore =
                new PayrollCalculationTestSupport.InMemoryStore();
        PayrollCalculationService nonLegalService = PayrollCalculationTestSupport.service(
                nonLegalStore, canonical, mock(PayrollHighRiskCommandGuard.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        nonLegalStore.seedPreparedRun(
                PayrollCalculationTestSupport.preparedRun(1, 101, RUN_ID));
        var nonLegalReceipt = nonLegalService.calculate(
                PayrollCalculationTestSupport.calculator(1, 101), RUN_ID,
                "group-calculate", 2, UUID.randomUUID(),
                new ActionCommand("CALCULATE", null, null));
        assertOpaque(() -> nonLegalService.receipt(
                receiptActor, nonLegalReceipt.receiptId()));
    }

    @Test
    void featureIsOffAndMissingFieldProjectionFailsClosed() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains(
                "DWP_HRIS_PAYROLL_CALCULATION_ENABLED:false",
                "DWP_HRIS_PAYROLL_CALCULATION_LEASE_DURATION:PT30S",
                "DWP_HRIS_PAYROLL_CALCULATION_CHECKPOINT_SIZE:100",
                "DWP_HRIS_PAYROLL_CALCULATION_MAX_INPUTS_PER_CLAIM:1000");
        PayrollFieldProjectionGuard guard = new PayrollFieldProjectionGuard(
                (subject, group) -> PayrollFieldProjectionDecisionPort.Decision.UNAVAILABLE);
        assertThatThrownBy(() -> guard.requireFull(
                PayrollCalculationTestSupport.calculator(1, 101).subject()))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private List<String> fields(JsonNode value) {
        List<String> fields = new ArrayList<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    @RestController
    private static final class ActionCommandProbe {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/pay011-action-command")
        void accept(@RequestBody ActionCommand command) {
            calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }
    }

    private PayrollCalculationModels.FrozenSnapshot copySnapshot(
            PayrollCalculationModels.FrozenSnapshot source,
            int attemptNo,
            List<PayrollCalculationModels.SourceInput> inputs) {
        return new PayrollCalculationModels.FrozenSnapshot(
                source.tenantId(), source.runId(), attemptNo,
                source.sourceSnapshotId(), source.sourceSnapshotRevision(),
                source.sourcePayloadDigest(), source.formulaSetDigest(),
                source.targetPopulationDigest(), source.compensationInputDigest(),
                source.semanticBindingDigest(), inputs, source.formulas(),
                source.semanticBindings(), source.pinnedAt());
    }

    private void assertSemanticEquality(
            PayrollCalculationModels.CalculationOutcome expected,
            PayrollCalculationModels.CalculationOutcome actual) {
        assertThat(actual.resultDigest()).isEqualTo(expected.resultDigest());
        assertThat(actual.lines()).extracting(
                        PayrollCalculationModels.CalculatedLine::resultLineDigest,
                        PayrollCalculationModels.CalculatedLine::traceDigest)
                .containsExactlyElementsOf(expected.lines().stream()
                        .map(line -> org.assertj.core.groups.Tuple.tuple(
                                line.resultLineDigest(), line.traceDigest())).toList());
        assertThat(actual.workers().stream().map(this::workerSemantics).toList())
                .containsExactlyElementsOf(expected.workers().stream()
                        .map(this::workerSemantics).toList());
        assertThat(actual.controlTotals()).isEqualTo(expected.controlTotals());
    }

    private List<Object> workerSemantics(
            PayrollCalculationModels.WorkerResult worker) {
        return List.of(
                worker.workerId(), worker.currency(), worker.gross(),
                worker.deductions(), worker.net(), worker.employerCost(),
                worker.lineCount(), worker.workerResultDigest());
    }

    private PayrollCalculationModels.RunRevision run(
            long revision,
            RunType type,
            RunState state,
            long makerActorId,
            int latestAttemptNo,
            BigDecimal gross,
            BigDecimal net,
            String resultDigest,
            UUID approvalCaseId,
            String decisionDigest,
            Long approvedBy,
            String populationKind) {
        return new PayrollCalculationModels.RunRevision(
                1, RUN_ID, revision, type, PayrollCalculationTestSupport.PAY_PERIOD,
                UUID.fromString("00000000-0000-0000-0000-000000001106"),
                9, PayrollCalculationTestSupport.DIGEST,
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                populationKind == null ? "PAY_GROUP" : populationKind,
                PayrollCalculationTestSupport.TARGET_DIGEST, state,
                makerActorId, PayrollCalculationTestSupport.SUBJECT,
                latestAttemptNo, state == RunState.VALIDATED ? 1 : 0,
                gross, net, "TST", resultDigest,
                approvalCaseId, decisionDigest, approvedBy,
                approvedBy == null ? null : NOW,
                null, null, NOW);
    }

    private void assertNoCommandEffects(
            PayrollCalculationTestSupport.InMemoryStore store,
            int runRevisions,
            int receipts,
            int audits,
            long totalEvents) {
        assertThat(store.runRevisionCount(1, RUN_ID)).isEqualTo(runRevisions);
        assertThat(store.receiptCount()).isEqualTo(receipts);
        assertThat(store.auditCount()).isEqualTo(audits);
        long events = store.outboxCount(1, RUN_ID, "PayrollRunApproved")
                + store.outboxCount(1, RUN_ID, "PayrollFinalized")
                + store.outboxCount(1, RUN_ID, "PayrollCalculated");
        assertThat(events).isEqualTo(totalEvents);
    }

    private void assertOpaque(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
