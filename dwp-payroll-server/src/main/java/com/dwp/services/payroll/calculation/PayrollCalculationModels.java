package com.dwp.services.payroll.calculation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class PayrollCalculationModels {

    static final String ENGINE_VERSION = "PAY_CALCULATION_V1";

    private PayrollCalculationModels() {
    }

    enum RunType { REGULAR, OFF_CYCLE, RETRO, CORRECTION }

    enum RunState {
        PREPARING, READY, CALCULATING, VALIDATED, APPROVED, FINALIZED, FAILED, RESULT_UNKNOWN
    }

    enum AttemptState {
        READY, CALCULATING, VALIDATED, FAILED, RESULT_UNKNOWN, SUPERSEDED
    }

    enum ReceiptStatus {
        ACCEPTED, RUNNING, SUCCEEDED, REJECTED, FAILED, RESULT_UNKNOWN
    }

    enum CommandType {
        RUN_CALCULATE("PAY_RUN_CALCULATE"),
        RUN_APPROVE("PAY_RUN_APPROVE"),
        RUN_FINALIZE("PAY_RUN_FINALIZE");

        private final String originatingAction;

        CommandType(String originatingAction) {
            this.originatingAction = originatingAction;
        }

        String originatingAction() {
            return originatingAction;
        }
    }

    @JsonPropertyOrder({"reasonCode", "approvalReceiptId", "comment"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(using = ActionCommandDeserializer.class)
    record ActionCommand(
            String reasonCode,
            String approvalReceiptId,
            String comment) {

        ActionCommand {
            codePoints(reasonCode, 1, 64, "reasonCode");
            if (approvalReceiptId != null) {
                if (!approvalReceiptId.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                                + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
                    throw new IllegalArgumentException(
                            "approvalReceiptId must be a UUID");
                }
                UUID.fromString(approvalReceiptId);
            }
            codePoints(comment, 0, 1000, "comment");
        }
    }

    @JsonPropertyOrder({"amount", "currency"})
    record Money(String amount, String currency) {
        Money {
            if (amount == null
                    || !amount.matches("(0|[1-9][0-9]{0,14})(\\.[0-9]{1,4})?")) {
                throw new IllegalArgumentException("amount must be a canonical nonnegative decimal");
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be ISO-4217 shaped");
            }
        }

        static Money of(BigDecimal amount, String currency) {
            return new Money(amount.setScale(4).toPlainString(), currency);
        }
    }

    @JsonPropertyOrder({
            "runId", "runType", "payPeriodId", "state", "workerCount",
            "gross", "net", "version"})
    record RunResponse(
            UUID runId,
            RunType runType,
            UUID payPeriodId,
            RunState state,
            int workerCount,
            Money gross,
            Money net,
            long version) {

        RunResponse {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(runType, "runType");
            Objects.requireNonNull(payPeriodId, "payPeriodId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(gross, "gross");
            Objects.requireNonNull(net, "net");
            if (workerCount < 0 || version < 0) {
                throw new IllegalArgumentException("workerCount and version must be nonnegative");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "receiptId", "commandType", "originatingAction",
            "subjectPrincipalPublicId", "populationScopeDigest",
            "fieldPolicyRevision", "purposeCode", "authorizationRevision",
            "status", "correlationId", "requestDigest", "createdAt",
            "aggregateVersion", "resultRef"})
    record PayrollCommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            ReceiptStatus status,
            UUID correlationId,
            String requestDigest,
            Instant createdAt,
            Long aggregateVersion,
            String resultRef) {

        PayrollCommandReceipt {
            Objects.requireNonNull(receiptId, "receiptId");
            commandType = normalizeRequired(commandType, 64, "commandType");
            originatingAction = normalizeRequired(
                    originatingAction, 128, "originatingAction");
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            digest(populationScopeDigest, "populationScopeDigest");
            if (fieldPolicyRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("receipt revisions must be positive");
            }
            purposeCode = normalizeRequired(purposeCode, 64, "purposeCode");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(correlationId, "correlationId");
            digest(requestDigest, "requestDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            if (aggregateVersion != null && aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion must be nonnegative");
            }
            resultRef = normalizeOptional(resultRef, 240, "resultRef");
        }
    }

    record RunRevision(
            long tenantId,
            UUID runId,
            long revision,
            RunType runType,
            UUID payPeriodId,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourcePayloadDigest,
            UUID legalEntityId,
            UUID payrollGroupId,
            String populationKind,
            String populationScopeDigest,
            RunState state,
            long makerActorId,
            UUID makerSubjectPublicId,
            int latestAttemptNo,
            int workerCount,
            BigDecimal gross,
            BigDecimal net,
            String currency,
            String resultDigest,
            UUID approvalCaseId,
            String decisionDigest,
            Long approvedByActorId,
            Instant approvedAt,
            String auditBundleDigest,
            UUID commandReceiptId,
            Instant recordedAt) {

        RunResponse publicView() {
            return new RunResponse(
                    runId, runType, payPeriodId, state, workerCount,
                    Money.of(gross, currency), Money.of(net, currency), revision);
        }
    }

    record Attempt(
            long tenantId,
            UUID runId,
            int attemptNo,
            UUID attemptId,
            Integer predecessorAttemptNo,
            String engineVersion,
            UUID commandReceiptId,
            Instant createdAt) {
    }

    record AttemptStatus(
            long tenantId,
            UUID runId,
            int attemptNo,
            int statusSequence,
            AttemptState state,
            String failureCode,
            Instant occurredAt) {
    }

    record SourceInput(
            long sourceInputId,
            UUID sourcePublicId,
            UUID sourceEventId,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotPayloadDigest,
            UUID planId,
            UUID cycleId,
            int lineSequence,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            BigDecimal approvedAmount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String payloadDigest) {
    }

    record FormulaBinding(UUID formulaId, long version, String astDigest) {
    }

    record QuantizationBinding(
            int sequence,
            Integer parentSequence,
            PayrollCalculationSemanticMappingPort.RoundingStage stage,
            FormulaBinding formula,
            int scale,
            java.math.RoundingMode mode,
            BigDecimal increment,
            String policyDigest) {

        QuantizationBinding {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(increment, "increment");
            if (scale < 0 || scale > 8) {
                throw new IllegalArgumentException(
                        "quantization binding scale must be 0..8");
            }
            try {
                increment = increment.setScale(
                        scale, java.math.RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "quantization binding increment is not canonical", exception);
            }
            if (increment.signum() <= 0 || increment.precision() > 24) {
                throw new IllegalArgumentException(
                        "quantization binding increment is invalid");
            }
            digest(policyDigest, "policyDigest");
        }
    }

    record SemanticBinding(
            UUID sourcePublicId,
            UUID elementId,
            long elementVersion,
            String elementSemanticDigest,
            PayrollCalculationSemanticMappingPort.Category category,
            String valueCurrencyMappingId,
            String valueCurrencyMappingDigest,
            List<FormulaBinding> formulaDependencies,
            List<QuantizationBinding> quantizationPolicies,
            String engineVersion,
            String functionSetVersion,
            String bindingDigest) {

        SemanticBinding {
            formulaDependencies = List.copyOf(formulaDependencies);
            quantizationPolicies = List.copyOf(quantizationPolicies);
        }
    }

    record FrozenSnapshot(
            long tenantId,
            UUID runId,
            int attemptNo,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourcePayloadDigest,
            String formulaSetDigest,
            String targetPopulationDigest,
            String compensationInputDigest,
            String semanticBindingDigest,
            List<SourceInput> inputs,
            List<FormulaBinding> formulas,
            List<SemanticBinding> semanticBindings,
            Instant pinnedAt) {

        FrozenSnapshot {
            inputs = List.copyOf(inputs);
            formulas = List.copyOf(formulas);
            semanticBindings = List.copyOf(semanticBindings);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("frozen calculation snapshot requires inputs");
            }
        }
    }

    record CalculatedLine(
            UUID resultLineId,
            long sourceInputId,
            UUID workerId,
            UUID workerResultId,
            String componentCode,
            String sourcePayloadDigest,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            long formulaVersion,
            String formulaAstDigest,
            PayrollCalculationSemanticMappingPort.Category category,
            String currency,
            BigDecimal amount,
            String formulaInputDigest,
            String formulaOutputDigest,
            UUID terminalTraceId,
            List<CalculatedTraceNode> traceNodes,
            String resultLineDigest,
            String traceDigest) {

        CalculatedLine {
            traceNodes = List.copyOf(traceNodes);
            if (traceNodes.isEmpty()
                    || !traceNodes.getLast().traceId().equals(terminalTraceId)) {
                throw new IllegalArgumentException(
                        "calculated line requires its terminal trace node");
            }
        }
    }

    record CalculatedTraceNode(
            UUID traceId,
            int sequence,
            UUID parentTraceId,
            String semanticBindingDigest,
            PayrollCalculationSemanticMappingPort.RoundingStage stage,
            UUID formulaId,
            Long formulaVersion,
            String formulaAstDigest,
            String inputDigest,
            String outputDigest,
            BigDecimal unroundedAmount,
            BigDecimal roundedAmount,
            String currency,
            int roundingScale,
            java.math.RoundingMode roundingMode,
            BigDecimal roundingIncrement,
            String roundingPolicyDigest,
            String traceDigest) {
    }

    record WorkerResult(
            UUID workerResultId,
            UUID workerId,
            String currency,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            BigDecimal employerCost,
            int lineCount,
            String workerResultDigest) {
    }

    record ControlTotal(String code, BigDecimal amount, String currency) {
    }

    record CalculationOutcome(
            List<CalculatedLine> lines,
            List<WorkerResult> workers,
            List<ControlTotal> controlTotals,
            BigDecimal gross,
            BigDecimal net,
            String currency,
            String resultDigest) {

        CalculationOutcome {
            lines = List.copyOf(lines);
            workers = List.copyOf(workers);
            controlTotals = List.copyOf(controlTotals);
        }
    }

    record Lease(
            long tenantId,
            UUID runId,
            int attemptNo,
            UUID attemptId,
            int claimNo,
            UUID leaseToken,
            String workerInstanceId,
            Instant claimedAt,
            Instant expiresAt) {
    }

    record Checkpoint(
            int checkpointNo,
            int nextInputOrdinal,
            int completedWorkerCount,
            String checkpointDigest) {
    }

    record CommandIdentity(
            long tenantId,
            UUID subjectPrincipalPublicId,
            CommandType commandType,
            String idempotencyKey,
            String requestDigest) {
    }

    record PersistedReceipt(
            long tenantId,
            long actorId,
            UUID runId,
            String populationKind,
            String contextScopeKey,
            String idempotencyKey,
            CommandType commandType,
            PayrollCommandReceipt receipt,
            PayrollStepUpEvidence stepUpEvidence) {
    }

    record EventFact(
            UUID eventId,
            String eventType,
            int schemaVersion,
            UUID runId,
            Integer attemptNo,
            long runRevision,
            UUID receiptId,
            long aggregateSequence,
            UUID correlationId,
            String payloadJson,
            Instant occurredAt) {
    }

    private static String normalizeRequired(String value, int maximum, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (!normalized.equals(value) || normalized.length() > maximum
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximum, String field) {
        if (value == null) return null;
        if (value.isBlank()) return null;
        return normalizeRequired(value, maximum, field);
    }

    private static void codePoints(
            String value, int minimum, int maximum, String field) {
        if (value == null) {
            if (minimum == 0) return;
            throw new IllegalArgumentException(field + " is required");
        }
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
    }

    static void digest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
    }
}
