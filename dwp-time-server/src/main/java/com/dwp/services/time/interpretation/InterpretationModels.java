package com.dwp.services.time.interpretation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed transport and domain models for deterministic TIM interpretation. */
public final class InterpretationModels {

    public enum InterpretationAction {
        VIEW,
        RUN,
        TRACE
    }

    public enum PopulationKind {
        LEGAL_ENTITY,
        ORG_TREE,
        ORG_UNIT,
        SELF,
        TIME_GROUP
    }

    public enum RuleValueType {
        BOOLEAN,
        NUMBER,
        RATE,
        QUANTITY_MINUTE,
        QUANTITY_HOUR,
        QUANTITY_DAY
    }

    public enum RuleOperator {
        QUANTITY_LITERAL,
        RATE_LITERAL,
        BOOLEAN_LITERAL,
        INPUT,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        MIN,
        MAX,
        COMPARE_LT,
        COMPARE_EQ,
        AND,
        OR,
        IF,
        ROUND
    }

    public enum ClockFactType {
        IN,
        OUT,
        BREAK_START,
        BREAK_END
    }

    public enum RunStatus {
        SUCCEEDED,
        FAILED,
        RESULT_UNKNOWN,
        SUPERSEDED
    }

    public enum EntryCode {
        WORKED_MINUTES,
        REGULAR_MINUTES,
        OVERTIME_MINUTES,
        UNPAID_BREAK_MINUTES
    }

    public record VerifiedInterpretationRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Set<String> permissions,
            Set<InterpretationAction> actions,
            PopulationKind populationKind,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String decisionRevision,
            Instant revalidateAt,
            UUID correlationId,
            Set<UUID> authorizedWorkerPublicIds,
            Set<String> maskedFieldGroups,
            Set<String> omittedFieldGroups) {

        public VerifiedInterpretationRequest {
            if (tenantId <= 0 || actorId <= 0 || populationRevision <= 0
                    || grantRevision <= 0 || fieldPolicyRevision <= 0
                    || authorizationRevision <= 0) {
                throw new IllegalArgumentException("authority revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(populationKind, "populationKind");
            Objects.requireNonNull(populationPublicId, "populationPublicId");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            Objects.requireNonNull(correlationId, "correlationId");
            permissions = immutable(permissions);
            actions = immutable(actions);
            authorizedWorkerPublicIds = immutable(authorizedWorkerPublicIds);
            maskedFieldGroups = immutable(maskedFieldGroups);
            omittedFieldGroups = immutable(omittedFieldGroups);
            requireDigest(populationScopeDigest, "populationScopeDigest");
            requireText(purposeCode, "purposeCode");
            if (decisionRevision == null
                    || !decisionRevision.matches("psr-[0-9a-f]{64}")) {
                throw new IllegalArgumentException("decisionRevision is invalid");
            }
            if (authorizedWorkerPublicIds.isEmpty()
                    || authorizedWorkerPublicIds.size() > 1_000) {
                throw new IllegalArgumentException("authorized workers must be bounded");
            }
        }
    }

    public record InterpretCommand(
            @NotEmpty @Size(max = 1000) List<@NotNull UUID> workerIds,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            @NotNull Instant asOf) {

        public InterpretCommand {
            workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
            if (workerIds.isEmpty() || workerIds.size() > 1_000
                    || workerIds.stream().distinct().count() != workerIds.size()) {
                throw new IllegalArgumentException("workerIds must be unique and bounded");
            }
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(asOf, "asOf");
            if (periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException("periodEnd must not precede periodStart");
            }
        }
    }

    public record InputReference(
            String owner,
            String schemaId,
            UUID publicId,
            long revision,
            String contentHash,
            Instant validFrom,
            Instant validToExclusive) {

        public InputReference {
            requireText(owner, "owner");
            requireText(schemaId, "schemaId");
            Objects.requireNonNull(publicId, "publicId");
            if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
            requireDigest(contentHash, "contentHash");
            if (validToExclusive != null && validFrom != null
                    && !validToExclusive.isAfter(validFrom)) {
                throw new IllegalArgumentException("owner reference validity is invalid");
            }
        }
    }

    public record TypedRuleNode(
            String nodeId,
            RuleOperator operator,
            List<String> arguments,
            RuleValueType returnType,
            Object value,
            InputReference inputRef,
            InputReference roundingRef) {

        public TypedRuleNode {
            requireText(nodeId, "nodeId");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(returnType, "returnType");
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            if (arguments.size() > 3
                    || arguments.stream().anyMatch(argument -> argument == null
                            || !argument.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))) {
                throw new IllegalArgumentException("arguments are invalid");
            }
        }
    }

    public record TypedRuleAst(
            String registryId,
            int registryVersion,
            String rootNodeId,
            @NotEmpty List<@Valid TypedRuleNode> nodes) {

        public TypedRuleAst {
            requireText(registryId, "registryId");
            if (registryVersion <= 0) {
                throw new IllegalArgumentException("registryVersion must be positive");
            }
            requireText(rootNodeId, "rootNodeId");
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            if (nodes.isEmpty() || nodes.size() > 512) {
                throw new IllegalArgumentException("nodes must be bounded");
            }
        }
    }

    public record RuleOutput(EntryCode outputCode, TypedRuleAst expression) {
        public RuleOutput {
            Objects.requireNonNull(outputCode, "outputCode");
            Objects.requireNonNull(expression, "expression");
        }
    }

    public record PublishedRuleVersion(
            long tenantId,
            UUID ruleVersionPublicId,
            long revision,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String registryId,
            int registryVersion,
            String ruleDigest,
            List<RuleOutput> outputs) {

        public PublishedRuleVersion {
            if (tenantId <= 0 || revision <= 0 || registryVersion <= 0) {
                throw new IllegalArgumentException("rule identity is invalid");
            }
            Objects.requireNonNull(ruleVersionPublicId, "ruleVersionPublicId");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException("rule effective period is invalid");
            }
            requireText(registryId, "registryId");
            requireDigest(ruleDigest, "ruleDigest");
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            if (outputs.isEmpty() || outputs.stream().map(RuleOutput::outputCode)
                    .distinct().count() != outputs.size()) {
                throw new IllegalArgumentException("rule outputs must be unique");
            }
        }
    }

    public record ClockInputFact(
            long eventOrder,
            UUID clockEventPublicId,
            long tenantId,
            UUID workerPublicId,
            ClockFactType eventType,
            Instant occurredAt,
            String sourceTimeZone,
            int sourceUtcOffsetMinutes,
            String timeZoneRuleVersion,
            String payloadDigest) {

        public ClockInputFact {
            if (eventOrder <= 0 || tenantId <= 0
                    || sourceUtcOffsetMinutes < -840 || sourceUtcOffsetMinutes > 840) {
                throw new IllegalArgumentException("clock fact identity is invalid");
            }
            Objects.requireNonNull(clockEventPublicId, "clockEventPublicId");
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(sourceTimeZone, "sourceTimeZone");
            requireText(timeZoneRuleVersion, "timeZoneRuleVersion");
            requireDigest(payloadDigest, "payloadDigest");
        }
    }

    public record InterpretationInputs(
            List<ClockInputFact> clockFacts,
            PublishedRuleVersion ruleVersion) {
        public InterpretationInputs {
            clockFacts = clockFacts == null ? List.of() : List.copyOf(clockFacts);
            Objects.requireNonNull(ruleVersion, "ruleVersion");
        }
    }

    public record WorkerMinuteResult(
            UUID workerPublicId,
            int workedMinutes,
            int regularMinutes,
            int overtimeMinutes,
            int unpaidBreakMinutes) {

        public WorkerMinuteResult {
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            if (workedMinutes < 0 || regularMinutes < 0 || overtimeMinutes < 0
                    || unpaidBreakMinutes < 0 || regularMinutes + overtimeMinutes != workedMinutes) {
                throw new IllegalArgumentException("minute totals are inconsistent");
            }
        }
    }

    public record TraceStep(
            int sequence,
            String ruleCode,
            int inputMinutes,
            int outputMinutes) {
        public TraceStep {
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
            requireText(ruleCode, "ruleCode");
        }
    }

    public record InterpretationComputation(
            String inputDigest,
            String ruleDigest,
            String outputDigest,
            List<WorkerMinuteResult> results,
            List<TraceStep> traceSteps) {
        public InterpretationComputation {
            requireDigest(inputDigest, "inputDigest");
            requireDigest(ruleDigest, "ruleDigest");
            requireDigest(outputDigest, "outputDigest");
            results = results == null ? List.of() : List.copyOf(results);
            traceSteps = traceSteps == null ? List.of() : List.copyOf(traceSteps);
        }
    }

    public record InterpretationEntry(
            UUID entryPublicId,
            UUID workerPublicId,
            EntryCode entryCode,
            int minutes,
            UUID reversesEntryPublicId) {
        public InterpretationEntry {
            Objects.requireNonNull(entryPublicId, "entryPublicId");
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            Objects.requireNonNull(entryCode, "entryCode");
            if (reversesEntryPublicId == null && minutes < 0) {
                throw new IllegalArgumentException("only reversals may be negative");
            }
            if (reversesEntryPublicId != null && minutes > 0) {
                throw new IllegalArgumentException("reversals must negate prior entries");
            }
        }
    }

    public record InterpretationRun(
            UUID runPublicId,
            long tenantId,
            UUID subjectPrincipalPublicId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant asOf,
            UUID ruleVersionPublicId,
            long ruleVersionRevision,
            String inputDigest,
            String ruleDigest,
            String outputDigest,
            int attemptNo,
            RunStatus status,
            boolean ledgerDeltaApplied,
            UUID supersedesRunPublicId,
            UUID correlationId,
            Instant createdAt,
            List<WorkerMinuteResult> results,
            List<InterpretationEntry> entries,
            List<TraceStep> traceSteps) {

        public InterpretationRun {
            Objects.requireNonNull(runPublicId, "runPublicId");
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(ruleVersionPublicId, "ruleVersionPublicId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(createdAt, "createdAt");
            if (tenantId <= 0 || ruleVersionRevision <= 0 || attemptNo <= 0) {
                throw new IllegalArgumentException("run revisions are invalid");
            }
            requireDigest(inputDigest, "inputDigest");
            requireDigest(ruleDigest, "ruleDigest");
            requireDigest(outputDigest, "outputDigest");
            results = results == null ? List.of() : List.copyOf(results);
            if (results.isEmpty()) {
                throw new IllegalArgumentException("run results are required");
            }
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (!ledgerDeltaApplied && !entries.isEmpty()) {
                throw new IllegalArgumentException("zero-delta replay cannot contain ledger entries");
            }
            traceSteps = traceSteps == null ? List.of() : List.copyOf(traceSteps);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InterpretationCommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String status,
            UUID correlationId,
            String requestDigest,
            Instant createdAt,
            Long aggregateVersion,
            String resultRef,
            @JsonIgnore UUID runPublicId,
            @JsonIgnore String outputDigest,
            @JsonIgnore boolean replayed) {

        public InterpretationCommandReceipt {
            Objects.requireNonNull(receiptId, "receiptId");
            requireText(commandType, "commandType");
            requireText(originatingAction, "originatingAction");
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            requireDigest(populationScopeDigest, "populationScopeDigest");
            requireText(purposeCode, "purposeCode");
            requireText(status, "status");
            Objects.requireNonNull(correlationId, "correlationId");
            requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            requireText(resultRef, "resultRef");
        }
    }

    public record InterpretationAppend(
            VerifiedInterpretationRequest authority,
            UUID idempotencyKey,
            String requestDigest,
            InterpretationRun run) {
        public InterpretationAppend {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(run, "run");
        }
    }

    public record InterpretationTrace(
            UUID runId,
            String inputDigest,
            String ruleDigest,
            List<TraceStep> steps) {
        public InterpretationTrace {
            Objects.requireNonNull(runId, "runId");
            requireDigest(inputDigest, "inputDigest");
            requireDigest(ruleDigest, "ruleDigest");
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record InterpretationQuery(
            long tenantId,
            Set<UUID> workerScope,
            Instant asOf,
            int limit,
            int offset,
            LocalDate effectiveOn,
            List<String> statusCodes) {
        public InterpretationQuery {
            if (tenantId <= 0 || limit < 1 || limit > 101 || offset < 0) {
                throw new IllegalArgumentException("interpretation query is invalid");
            }
            workerScope = immutable(workerScope);
            Objects.requireNonNull(asOf, "asOf");
            statusCodes = statusCodes == null ? List.of() : List.copyOf(statusCodes);
        }
    }

    public record InterpretationSummary(
            UUID runPublicId,
            UUID workerPublicId,
            LocalDate periodStart,
            LocalDate periodEnd,
            RunStatus status,
            UUID ruleVersionPublicId,
            long ruleVersionRevision,
            int workedMinutes,
            int regularMinutes,
            int overtimeMinutes,
            int attemptNo,
            String inputDigest,
            String ruleDigest,
            String outputDigest,
            Instant createdAt) {}

    public record RuleEvaluation(
            long value,
            Map<String, Long> quantityValues,
            Map<String, Boolean> booleanValues) {
        public RuleEvaluation {
            quantityValues = Map.copyOf(quantityValues);
            booleanValues = Map.copyOf(booleanValues);
        }
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static <T> Set<T> immutable(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private InterpretationModels() {}
}
