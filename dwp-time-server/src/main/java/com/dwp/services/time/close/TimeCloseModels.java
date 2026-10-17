package com.dwp.services.time.close;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact public transport and owner-domain values for BASE-TFR-TIM-007. */
public final class TimeCloseModels {

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern REASON =
            Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");
    private static final Pattern QUANTITY =
            Pattern.compile("^-?(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,6})?$");

    private TimeCloseModels() {}

    public enum CloseState {
        OPEN, VALIDATING, CLOSING, CLOSED, REOPEN_REQUESTED, REOPENED, FAILED
    }

    public enum HandoffState {
        PENDING, ACKNOWLEDGED, REJECTED, RESULT_UNKNOWN
    }

    public enum CloseAction {
        QUERY, VALIDATE, CLOSE, REOPEN, HANDOFF_CREATE, HANDOFF_QUERY
    }

    public enum CommandKind {
        VALIDATE("PERIOD_VALIDATE", "TIME_PERIOD_VALIDATE",
                "WORK_TIME_PERIOD_CLOSURE"),
        CLOSE("PERIOD_CLOSE", "TIME_PERIOD_CLOSE",
                "WORK_TIME_PERIOD_CLOSURE"),
        REOPEN("PERIOD_REOPEN", "TIME_PERIOD_REOPEN",
                "WORK_TIME_PERIOD_CLOSURE"),
        HANDOFF("PAYROLL_HANDOFF", "TIME_PAYROLL_HANDOFF",
                "CLOSED_TIME_PAYROLL_TRANSFER");

        private final String commandType;
        private final String originatingAction;
        private final String purpose;

        CommandKind(String commandType, String originatingAction, String purpose) {
            this.commandType = commandType;
            this.originatingAction = originatingAction;
            this.purpose = purpose;
        }

        public String commandType() { return commandType; }

        public String originatingAction() { return originatingAction; }

        public String purpose() { return purpose; }
    }

    public record TimeCloseValidationCommandV1(Instant asOf) {
        public TimeCloseValidationCommandV1 {
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    public record TimeCloseCommandV1(Instant asOf, UUID approvalReceiptId) {
        public TimeCloseCommandV1 {
            Objects.requireNonNull(asOf, "asOf");
            requireUuid(approvalReceiptId, "approvalReceiptId");
        }
    }

    public record TimeReopenCommandV1(
            Instant asOf, UUID approvalReceiptId, String reasonCode) {
        public TimeReopenCommandV1 {
            Objects.requireNonNull(asOf, "asOf");
            requireUuid(approvalReceiptId, "approvalReceiptId");
            requireReason(reasonCode, "reasonCode");
        }
    }

    public record PayrollTimeHandoffCommandV1(
            UUID closedTimeResultId, UUID payrollEntityId, UUID payPeriodId) {
        public PayrollTimeHandoffCommandV1 {
            requireUuid(closedTimeResultId, "closedTimeResultId");
            requireUuid(payrollEntityId, "payrollEntityId");
            requireUuid(payPeriodId, "payPeriodId");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimeCloseCommandReceiptV1(
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
            String resultRef) {
        public TimeCloseCommandReceiptV1 {
            requireUuid(receiptId, "receiptId");
            requireText(commandType, "commandType");
            requireText(originatingAction, "originatingAction");
            requireUuid(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            requireDigest(populationScopeDigest, "populationScopeDigest");
            if (fieldPolicyRevision <= 0 || authorizationRevision <= 0) {
                throw new IllegalArgumentException("receipt revisions must be positive");
            }
            requireText(purposeCode, "purposeCode");
            boolean exactPair = switch (commandType) {
                case "PERIOD_VALIDATE" -> "TIME_PERIOD_VALIDATE".equals(originatingAction)
                        && "WORK_TIME_PERIOD_CLOSURE".equals(purposeCode);
                case "PERIOD_CLOSE" -> "TIME_PERIOD_CLOSE".equals(originatingAction)
                        && "WORK_TIME_PERIOD_CLOSURE".equals(purposeCode);
                case "PERIOD_REOPEN" -> "TIME_PERIOD_REOPEN".equals(originatingAction)
                        && "WORK_TIME_PERIOD_CLOSURE".equals(purposeCode);
                case "PAYROLL_HANDOFF" -> "TIME_PAYROLL_HANDOFF".equals(originatingAction)
                        && "CLOSED_TIME_PAYROLL_TRANSFER".equals(purposeCode);
                default -> false;
            };
            if (!exactPair) {
                throw new IllegalArgumentException("receipt command binding is invalid");
            }
            if (!Set.of("ACCEPTED", "RUNNING", "SUCCEEDED", "REJECTED",
                    "FAILED", "RESULT_UNKNOWN").contains(status)) {
                throw new IllegalArgumentException("status is invalid");
            }
            requireUuid(correlationId, "correlationId");
            requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            if (aggregateVersion != null && aggregateVersion <= 0) {
                throw new IllegalArgumentException("aggregateVersion must be positive");
            }
            if (resultRef != null && resultRef.isBlank()) {
                throw new IllegalArgumentException("resultRef must not be blank");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PayrollTimeHandoffStatusV1(
            UUID handoffId,
            UUID closePeriodId,
            UUID closedTimeResultId,
            long closeRevision,
            UUID payrollEntityId,
            UUID payPeriodId,
            int workerCount,
            int lineCount,
            String sourceDigest,
            String ruleDigest,
            String payloadDigest,
            HandoffState status,
            UUID receiverReceiptId,
            Long receiverReceiptRevision,
            String receiverReceiptDigest,
            String reasonCode,
            Instant outcomeAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        public PayrollTimeHandoffStatusV1 {
            requireUuid(handoffId, "handoffId");
            requireUuid(closePeriodId, "closePeriodId");
            requireUuid(closedTimeResultId, "closedTimeResultId");
            requireUuid(payrollEntityId, "payrollEntityId");
            requireUuid(payPeriodId, "payPeriodId");
            if (closeRevision <= 0 || workerCount < 0 || lineCount < 0 || version <= 0) {
                throw new IllegalArgumentException("handoff counts and versions are invalid");
            }
            requireDigest(sourceDigest, "sourceDigest");
            requireDigest(ruleDigest, "ruleDigest");
            requireDigest(payloadDigest, "payloadDigest");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            boolean receiver = receiverReceiptId != null
                    && receiverReceiptRevision != null && receiverReceiptRevision > 0
                    && receiverReceiptDigest != null && outcomeAt != null;
            if (status == HandoffState.PENDING) {
                if (receiverReceiptId != null || receiverReceiptRevision != null
                        || receiverReceiptDigest != null || reasonCode != null
                        || outcomeAt != null) {
                    throw new IllegalArgumentException("PENDING outcome evidence must be null");
                }
            } else if (!receiver) {
                throw new IllegalArgumentException("terminal outcome evidence is required");
            } else {
                requireDigest(receiverReceiptDigest, "receiverReceiptDigest");
                if (status == HandoffState.ACKNOWLEDGED && reasonCode != null) {
                    throw new IllegalArgumentException("ACKNOWLEDGED reasonCode must be null");
                }
                if (status != HandoffState.ACKNOWLEDGED) {
                    requireReason(reasonCode, "reasonCode");
                }
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PayrollTimeHandoffOutcomeV1(
            UUID closedTimeResultId,
            long closeRevision,
            String payloadDigest,
            HandoffState outcome,
            UUID receiverReceiptId,
            long receiverReceiptRevision,
            String receiverReceiptDigest,
            String reasonCode,
            Instant outcomeAt,
            UUID correlationId) {
        public PayrollTimeHandoffOutcomeV1 {
            requireUuid(closedTimeResultId, "closedTimeResultId");
            if (closeRevision <= 0 || receiverReceiptRevision <= 0) {
                throw new IllegalArgumentException("outcome revisions must be positive");
            }
            requireDigest(payloadDigest, "payloadDigest");
            if (outcome == null || outcome == HandoffState.PENDING) {
                throw new IllegalArgumentException("outcome must be terminal or unknown");
            }
            requireUuid(receiverReceiptId, "receiverReceiptId");
            requireDigest(receiverReceiptDigest, "receiverReceiptDigest");
            if (outcome == HandoffState.ACKNOWLEDGED) {
                if (reasonCode != null) {
                    throw new IllegalArgumentException("ACKNOWLEDGED reasonCode must be null");
                }
            } else {
                requireReason(reasonCode, "reasonCode");
            }
            Objects.requireNonNull(outcomeAt, "outcomeAt");
            requireUuid(correlationId, "correlationId");
        }
    }

    public record VerifiedCloseRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Set<String> permissions,
            Set<String> responsibilities,
            CloseAction action,
            String routeContractKey,
            String contextKey,
            String gatewayScopeKey,
            String accessMode,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String populationScopeDigest,
            UUID legalEntityPublicId,
            UUID timeGroupPublicId,
            long fieldPolicyRevision,
            long authorizationRevision,
            String decisionRevision,
            String purposeCode,
            Instant revalidateAt,
            UUID correlationId,
            Set<String> maskedFieldGroups,
            Set<String> omittedFieldGroups) {
        public VerifiedCloseRequest {
            if (tenantId <= 0 || actorId <= 0 || populationRevision <= 0
                    || grantRevision <= 0 || fieldPolicyRevision <= 0
                    || authorizationRevision <= 0) {
                throw new IllegalArgumentException("authority revisions must be positive");
            }
            requireUuid(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            permissions = Set.copyOf(Objects.requireNonNull(permissions));
            responsibilities = Set.copyOf(Objects.requireNonNull(responsibilities));
            Objects.requireNonNull(action, "action");
            requireText(routeContractKey, "routeContractKey");
            if (contextKey == null || !contextKey.matches("^psc-[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("contextKey is invalid");
            }
            requireText(gatewayScopeKey, "gatewayScopeKey");
            if (!Set.of("NORMAL", "ELEVATED").contains(accessMode)) {
                throw new IllegalArgumentException("accessMode is invalid");
            }
            requireUuid(populationPublicId, "populationPublicId");
            requireDigest(populationScopeDigest, "populationScopeDigest");
            requireUuid(legalEntityPublicId, "legalEntityPublicId");
            requireUuid(timeGroupPublicId, "timeGroupPublicId");
            if (decisionRevision == null
                    || !decisionRevision.matches("^psr-[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("decisionRevision is invalid");
            }
            requireText(purposeCode, "purposeCode");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            requireUuid(correlationId, "correlationId");
            maskedFieldGroups = Set.copyOf(Objects.requireNonNull(maskedFieldGroups));
            omittedFieldGroups = Set.copyOf(Objects.requireNonNull(omittedFieldGroups));
        }
    }

    public record ClosePeriod(
            long internalId,
            long tenantId,
            UUID publicId,
            String closeScope,
            UUID legalEntityPublicId,
            UUID timeGroupPublicId,
            UUID payrollEntityId,
            UUID payPeriodId,
            LocalDate periodStart,
            LocalDate periodEnd,
            CloseState state,
            long closeRevision,
            Long ledgerRevision,
            UUID latestResultId,
            UUID closedBySubjectId,
            UUID closeApprovalActorId,
            UUID correctionMakerSubjectId,
            long version) {
        public ClosePeriod {
            if (internalId <= 0 || tenantId <= 0 || closeRevision < 0 || version <= 0) {
                throw new IllegalArgumentException("period identity is invalid");
            }
            requireUuid(publicId, "publicId");
            requireText(closeScope, "closeScope");
            requireUuid(legalEntityPublicId, "legalEntityPublicId");
            requireUuid(timeGroupPublicId, "timeGroupPublicId");
            requireUuid(payrollEntityId, "payrollEntityId");
            requireUuid(payPeriodId, "payPeriodId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(state, "state");
        }
    }

    public record CloseBlocker(String code, UUID resourceId, String detailDigest) {
        public CloseBlocker {
            requireReason(code, "code");
            requireUuid(resourceId, "resourceId");
            requireDigest(detailDigest, "detailDigest");
        }
    }

    public record SourceEvidence(
            UUID ledgerEntryId,
            Instant postedAt,
            long ledgerRevision,
            BigDecimal quantity,
            String unit,
            String entryDigest) {
        public SourceEvidence {
            requireUuid(ledgerEntryId, "ledgerEntryId");
            Objects.requireNonNull(postedAt, "postedAt");
            if (ledgerRevision <= 0) throw new IllegalArgumentException("ledgerRevision");
            Objects.requireNonNull(quantity, "quantity");
            if (!QUANTITY.matcher(quantity.stripTrailingZeros().toPlainString()).matches()) {
                throw new IllegalArgumentException("quantity exceeds NUMERIC(19,6)");
            }
            requireText(unit, "unit");
            if (!Set.of("MINUTE", "HOUR", "DAY", "COUNT", "AMOUNT").contains(unit)) {
                throw new IllegalArgumentException("unit is invalid");
            }
            requireDigest(entryDigest, "entryDigest");
        }
    }

    public record ResultLine(
            UUID workerId,
            UUID assignmentId,
            String payCode,
            BigDecimal quantity,
            String unit,
            String currency,
            String sourceEntryDigest,
            List<SourceEvidence> sources) {
        public ResultLine {
            requireUuid(workerId, "workerId");
            requireUuid(assignmentId, "assignmentId");
            requireText(payCode, "payCode");
            Objects.requireNonNull(quantity, "quantity");
            if (!QUANTITY.matcher(quantity.stripTrailingZeros().toPlainString()).matches()) {
                throw new IllegalArgumentException("quantity exceeds NUMERIC(19,6)");
            }
            requireText(unit, "unit");
            if (!Set.of("MINUTE", "HOUR", "DAY", "COUNT", "AMOUNT").contains(unit)) {
                throw new IllegalArgumentException("unit is invalid");
            }
            if ("AMOUNT".equals(unit)) {
                if (currency == null || !currency.matches("^[A-Z]{3}$")
                        || "XXX".equals(currency)) {
                    throw new IllegalArgumentException("AMOUNT currency is invalid");
                }
            } else if (currency != null) {
                throw new IllegalArgumentException("currency is allowed only for AMOUNT");
            }
            requireDigest(sourceEntryDigest, "sourceEntryDigest");
            sources = List.copyOf(Objects.requireNonNull(sources));
            if (sources.isEmpty() || sources.size() > 100_000) {
                throw new IllegalArgumentException("sources must be bounded");
            }
        }

        Xcon008ClosedTimeResultV1.ClosedTimeLine toXcon() {
            return new Xcon008ClosedTimeResultV1.ClosedTimeLine(
                    workerId, assignmentId, payCode, quantity, currency,
                    sources.size(), sourceEntryDigest, unit);
        }
    }

    public record CloseEvidence(
            List<CloseBlocker> blockers,
            List<ResultLine> lines,
            UUID populationPublicId,
            long populationRevision,
            long ledgerRevisionFrom,
            long ledgerRevisionTo,
            String sourceDigest,
            String ruleDigest,
            String membershipDigest,
            String timecardDigest,
            Map<UUID, Long> workerLedgerHighWater) {
        public CloseEvidence {
            blockers = List.copyOf(Objects.requireNonNull(blockers));
            lines = List.copyOf(Objects.requireNonNull(lines));
            requireUuid(populationPublicId, "populationPublicId");
            if (populationRevision <= 0
                    || blockers.size() > 10_000 || lines.size() > 1_000_000
                    || ledgerRevisionFrom <= 0 || ledgerRevisionTo < ledgerRevisionFrom) {
                throw new IllegalArgumentException("close evidence is invalid");
            }
            requireDigest(sourceDigest, "sourceDigest");
            requireDigest(ruleDigest, "ruleDigest");
            requireDigest(membershipDigest, "membershipDigest");
            requireDigest(timecardDigest, "timecardDigest");
            workerLedgerHighWater = Map.copyOf(
                    Objects.requireNonNull(workerLedgerHighWater, "workerLedgerHighWater"));
            if (workerLedgerHighWater.size() > 1_000_000
                    || workerLedgerHighWater.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getKey().equals(new UUID(0, 0))
                                || entry.getValue() == null || entry.getValue() < 1)) {
                throw new IllegalArgumentException("worker ledger high-water is invalid");
            }
        }
    }

    public record ApprovalEvidence(
            UUID approvalReceiptId,
            UUID approvalActorSubjectId,
            String action,
            String subjectRef,
            long subjectRevision,
            String requestDigest,
            String decisionRevision,
            String decisionDigest,
            String reasonCode,
            Instant expiresAt) {
        public ApprovalEvidence {
            requireUuid(approvalReceiptId, "approvalReceiptId");
            requireUuid(approvalActorSubjectId, "approvalActorSubjectId");
            requireText(action, "action");
            requireText(subjectRef, "subjectRef");
            if (subjectRevision < 0) throw new IllegalArgumentException("subjectRevision");
            requireDigest(requestDigest, "requestDigest");
            if (decisionRevision == null
                    || !decisionRevision.matches("^psr-[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("decisionRevision");
            }
            requireDigest(decisionDigest, "decisionDigest");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record CloseCommand(
            VerifiedCloseRequest authority,
            CommandKind kind,
            UUID periodId,
            long expectedVersion,
            String idempotencyKey,
            String requestDigest,
            Instant asOf,
            UUID approvalReceiptId,
            String reasonCode,
            String stepUpChallenge,
            PayrollTimeHandoffCommandV1 handoff) {
        public CloseCommand {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(kind, "kind");
            requireUuid(periodId, "periodId");
            if (expectedVersion <= 0) throw new IllegalArgumentException("expectedVersion");
            if (idempotencyKey == null || idempotencyKey.length() < 16
                    || idempotencyKey.length() > 128
                    || !idempotencyKey.equals(idempotencyKey.strip())) {
                throw new IllegalArgumentException("idempotencyKey");
            }
            requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(asOf, "asOf");
            boolean approvalOperation = kind == CommandKind.CLOSE
                    || kind == CommandKind.REOPEN;
            if (approvalOperation != (approvalReceiptId != null)
                    || approvalOperation != (stepUpChallenge != null)) {
                throw new IllegalArgumentException(
                        "approval and step-up are required only for close/reopen");
            }
            if ((kind == CommandKind.REOPEN) != (reasonCode != null)) {
                throw new IllegalArgumentException("reasonCode is required only for reopen");
            }
            if (reasonCode != null) requireReason(reasonCode, "reasonCode");
            if ((kind == CommandKind.HANDOFF) != (handoff != null)) {
                throw new IllegalArgumentException("handoff body is bound only to handoff");
            }
        }
    }

    public record StoredReceipt(
            TimeCloseCommandReceiptV1 receipt,
            String idempotencyKey,
            String routeContractKey,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String requiredPermission,
            UUID aggregatePublicId) {
        public StoredReceipt {
            Objects.requireNonNull(receipt, "receipt");
            requireText(idempotencyKey, "idempotencyKey");
            requireText(routeContractKey, "routeContractKey");
            requireUuid(populationPublicId, "populationPublicId");
            if (populationRevision <= 0 || grantRevision <= 0) {
                throw new IllegalArgumentException("origin revisions");
            }
            requireText(requiredPermission, "requiredPermission");
            requireUuid(aggregatePublicId, "aggregatePublicId");
        }
    }

    public record PersistedClosedResult(
            long internalId,
            Xcon008ClosedTimeResultV1 result,
            Instant createdAt) {
        public PersistedClosedResult {
            if (internalId <= 0) throw new IllegalArgumentException("internalId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record HandoffRecord(
            long internalId,
            long tenantId,
            UUID correlationId,
            PayrollTimeHandoffStatusV1 projection) {
        public HandoffRecord {
            if (internalId <= 0 || tenantId <= 0) {
                throw new IllegalArgumentException("handoff identity is invalid");
            }
            requireUuid(correlationId, "correlationId");
            Objects.requireNonNull(projection, "projection");
        }
    }

    /** Immutable inputs passed into the atomic repository command boundary. */
    public record PlannedCloseCommand(
            CloseCommand command,
            ClosePeriod observedPeriod,
            CloseEvidence evidence,
            ApprovalEvidence approval,
            PersistedClosedResult selectedResult) {
        public PlannedCloseCommand {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(observedPeriod, "observedPeriod");
            if (command.kind() == CommandKind.VALIDATE
                    || command.kind() == CommandKind.CLOSE) {
                Objects.requireNonNull(evidence, "evidence");
            } else if (evidence != null) {
                throw new IllegalArgumentException("evidence belongs only to validate/close");
            }
            boolean approved = command.kind() == CommandKind.CLOSE
                    || command.kind() == CommandKind.REOPEN;
            if (approved != (approval != null)) {
                throw new IllegalArgumentException("approval evidence is mismatched");
            }
            if ((command.kind() == CommandKind.HANDOFF) != (selectedResult != null)) {
                throw new IllegalArgumentException("handoff result selection is mismatched");
            }
        }
    }

    static void requireDigest(String value, String name) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
    }

    static void requireReason(String value, String name) {
        if (value == null || !REASON.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void requireUuid(UUID value, String name) {
        if (value == null || value.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
