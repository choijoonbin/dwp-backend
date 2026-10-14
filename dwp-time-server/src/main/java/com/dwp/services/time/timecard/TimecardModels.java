package com.dwp.services.time.timecard;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed transport and owner-domain models for BASE-TFR-TIM-017. */
public final class TimecardModels {

    public enum TimecardState {
        OPEN,
        SUBMITTED,
        APPROVED,
        REJECTED,
        CORRECTION_REQUIRED,
        LOCKED,
        CLOSED,
        SUPERSEDED
    }

    public enum TimecardDecision {
        APPROVE(TimecardState.APPROVED),
        REJECT(TimecardState.REJECTED),
        REQUIRE_CORRECTION(TimecardState.CORRECTION_REQUIRED);

        private final TimecardState targetState;

        TimecardDecision(TimecardState targetState) {
            this.targetState = targetState;
        }

        public TimecardState targetState() {
            return targetState;
        }
    }

    public enum TimecardAction {
        VIEW,
        CORRECT,
        SUBMIT,
        DECIDE,
        RECEIPT_VIEW
    }

    public enum PopulationKind {
        SELF,
        TARGET_POPULATION
    }

    public enum CommandKind {
        CORRECT("TIMECARD_CORRECT", "TIME_TIMECARD_CORRECT"),
        SUBMIT("TIMECARD_SUBMIT", "TIME_TIMECARD_SUBMIT"),
        DECIDE("TIMECARD_DECIDE", "TIME_TIMECARD_DECIDE");

        private final String commandType;
        private final String originatingAction;

        CommandKind(String commandType, String originatingAction) {
            this.commandType = commandType;
            this.originatingAction = originatingAction;
        }

        public String commandType() {
            return commandType;
        }

        public String originatingAction() {
            return originatingAction;
        }

        public String requiredPermission(PopulationKind populationKind) {
            return switch (this) {
                case CORRECT -> switch (populationKind) {
                    case SELF -> "DATA.HR_TIME:CREATE";
                    case TARGET_POPULATION -> "DATA.HR_TIME:UPDATE";
                };
                case SUBMIT -> {
                    if (populationKind != PopulationKind.SELF) {
                        throw new IllegalArgumentException(
                                "Timecard submit origin must be SELF");
                    }
                    yield "DATA.HR_TIME:CREATE";
                }
                case DECIDE -> {
                    if (populationKind != PopulationKind.TARGET_POPULATION) {
                        throw new IllegalArgumentException(
                                "Timecard decision origin must be TARGET_POPULATION");
                    }
                    yield "DATA.HR_TIME:APPROVE";
                }
            };
        }
    }

    public record VerifiedTimecardRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            UUID actorWorkerPublicId,
            Set<String> permissions,
            Set<TimecardAction> actions,
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

        public VerifiedTimecardRequest {
            if (tenantId <= 0 || actorId <= 0 || populationRevision <= 0
                    || grantRevision <= 0 || fieldPolicyRevision <= 0
                    || authorizationRevision <= 0) {
                throw new IllegalArgumentException("authority revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(actorWorkerPublicId, "actorWorkerPublicId");
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

    public record TimecardV1(
            UUID timecardId,
            UUID workerId,
            LocalDate periodStart,
            LocalDate periodEnd,
            TimecardState state,
            int totalMinutes,
            long ledgerRevision,
            long version) {

        public TimecardV1 {
            Objects.requireNonNull(timecardId, "timecardId");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(state, "state");
            if (periodEnd.isBefore(periodStart) || totalMinutes < 0
                    || ledgerRevision < 1 || version < 0) {
                throw new IllegalArgumentException("timecard projection is invalid");
            }
        }
    }

    public record TimecardCorrectionEntryV1(
            @NotNull LocalDate workDate,
            @NotBlank @Size(max = 64) String payCode,
            int minutes) {

        public TimecardCorrectionEntryV1 {
            Objects.requireNonNull(workDate, "workDate");
            requireBoundedText(payCode, 64, "payCode");
            if (minutes == 0 || minutes < -1_440 || minutes > 1_440) {
                throw new IllegalArgumentException(
                        "minutes must be nonzero and between -1440 and 1440");
            }
        }
    }

    public record TimecardCorrectionCommandV1(
            @NotBlank @Size(max = 64) String reasonCode,
            @NotEmpty @Size(max = 100)
            List<@NotNull @Valid TimecardCorrectionEntryV1> entries) {

        public TimecardCorrectionCommandV1 {
            requireBoundedText(reasonCode, 64, "reasonCode");
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (entries.isEmpty() || entries.size() > 100) {
                throw new IllegalArgumentException("correction entries must be bounded");
            }
        }
    }

    public record TimecardSubmitCommandV1(@Size(max = 64) String reasonCode) {
        public TimecardSubmitCommandV1 {
            if (reasonCode != null) {
                requireBoundedText(reasonCode, 64, "reasonCode");
            }
        }
    }

    public record TimecardDecisionCommandV1(
            @NotNull TimecardDecision decision,
            @NotBlank @Size(max = 64) String reasonCode,
            @Size(max = 1000) String comment) {

        public TimecardDecisionCommandV1 {
            Objects.requireNonNull(decision, "decision");
            requireBoundedText(reasonCode, 64, "reasonCode");
            if (comment != null && comment.length() > 1_000) {
                throw new IllegalArgumentException("comment is too long");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TimeCommandReceiptV1(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID timecardId,
            String status,
            String requestDigest,
            Instant acceptedAt,
            Instant completedAt,
            String resultRef) {

        public TimeCommandReceiptV1 {
            Objects.requireNonNull(receiptId, "receiptId");
            requireText(commandType, "commandType");
            requireText(originatingAction, "originatingAction");
            Objects.requireNonNull(timecardId, "timecardId");
            if (!Set.of("ACCEPTED", "RUNNING", "SUCCEEDED", "REJECTED", "FAILED",
                    "RESULT_UNKNOWN").contains(status)) {
                throw new IllegalArgumentException("receipt status is invalid");
            }
            requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    public record TimecardRecord(
            TimecardV1 projection,
            UUID submittedBySubjectPublicId,
            UUID latestCorrectionMakerSubjectPublicId,
            boolean periodClosed) {

        public TimecardRecord {
            Objects.requireNonNull(projection, "projection");
        }
    }

    public record StoredTimecardReceipt(
            TimeCommandReceiptV1 receipt,
            UUID subjectPrincipalPublicId,
            UUID workerPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String originatingPurposeCode,
            long authorizationRevision,
            PopulationKind originatingPopulationKind,
            String originatingRequiredPermission) {

        public StoredTimecardReceipt {
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            requireDigest(populationScopeDigest, "populationScopeDigest");
            if (fieldPolicyRevision <= 0 || authorizationRevision <= 0) {
                throw new IllegalArgumentException("receipt authority revisions are invalid");
            }
            requireText(originatingPurposeCode, "originatingPurposeCode");
            Objects.requireNonNull(
                    originatingPopulationKind, "originatingPopulationKind");
            requireText(originatingRequiredPermission, "originatingRequiredPermission");
        }
    }

    public record TimecardCommand(
            CommandKind kind,
            VerifiedTimecardRequest authority,
            UUID timecardId,
            String idempotencyKey,
            long expectedVersion,
            String requestDigest,
            String reasonCode,
            List<TimecardCorrectionEntryV1> correctionEntries,
            TimecardDecision decision,
            String decisionComment,
            Instant occurredAt) {

        public TimecardCommand {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(timecardId, "timecardId");
            if (idempotencyKey == null || idempotencyKey.length() < 16
                    || idempotencyKey.length() > 128
                    || !idempotencyKey.equals(idempotencyKey.strip())) {
                throw new IllegalArgumentException("idempotencyKey is invalid");
            }
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            requireDigest(requestDigest, "requestDigest");
            correctionEntries = correctionEntries == null
                    ? List.of() : List.copyOf(correctionEntries);
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public record TimecardMaterialization(
            long tenantId,
            UUID timecardId,
            UUID workerId,
            LocalDate periodStart,
            LocalDate periodEnd,
            int totalMinutes,
            long ledgerRevision,
            UUID interpretationRunPublicId,
            long actorId,
            UUID actorSubjectPublicId,
            UUID correlationId,
            Instant occurredAt) {

        public TimecardMaterialization {
            if (tenantId <= 0 || actorId <= 0 || totalMinutes < 0 || ledgerRevision < 1) {
                throw new IllegalArgumentException("materialization values are invalid");
            }
            Objects.requireNonNull(timecardId, "timecardId");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(actorSubjectPublicId, "actorSubjectPublicId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException("materialization period is invalid");
            }
        }
    }

    private TimecardModels() {}

    private static <T> Set<T> immutable(Set<T> source) {
        return source == null ? Set.of() : Set.copyOf(source);
    }

    static void requireText(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    static void requireDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    static void requireBoundedText(String value, int maximum, String label) {
        requireText(value, label);
        if (value.length() > maximum) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }
}
