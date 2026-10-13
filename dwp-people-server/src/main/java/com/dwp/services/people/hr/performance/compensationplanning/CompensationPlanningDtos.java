package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CompensationPlanningDtos {

    private CompensationPlanningDtos() {
    }

    public enum CycleStatus {
        DRAFT, OPEN, PROPOSAL_REVIEW, APPROVED, PUBLISHED, CANCELLED
    }

    public enum LedgerEntryType {
        ALLOCATE, REVERSE
    }

    public enum ProposalStatus {
        DRAFT, SUBMITTED, FROZEN, CANCELLED
    }

    public interface ClosedInput {
        @JsonAnySetter
        default void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException(
                    "Unknown compensation-planning field: " + field);
        }
    }

    public record HrmSourceSnapshotRef(
            @NotNull UUID snapshotPublicId,
            long ownerRevision,
            @NotBlank String projectionSchemaId,
            @NotNull Instant asOf,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
            @NotBlank String purposeCode) implements ClosedInput {
        public HrmSourceSnapshotRef {
            if (ownerRevision <= 0) {
                throw new IllegalArgumentException("ownerRevision must be positive");
            }
            if (!"CompensationPopulationContent.v1".equals(projectionSchemaId)) {
                throw new IllegalArgumentException("projectionSchemaId is not supported");
            }
            if (!"HRIS_PER_COMPENSATION_POPULATION".equals(purposeCode)) {
                throw new IllegalArgumentException("purposeCode is not supported");
            }
        }
    }

    public record CreateCycleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9._-]{0,39}$") String cycleCode,
            @NotNull @Valid Guidelines guidelines,
            @NotNull @Valid Population population,
            @NotNull LocalDate effectiveFrom,
            @JsonProperty(required = true) LocalDate effectiveTo,
            @NotNull @Valid HrmSourceSnapshotRef populationSourceRef) implements ClosedInput {
        public CreateCycleRequest {
            validateDates(effectiveFrom, effectiveTo);
        }
    }

    public record ReviseCycleRequest(
            @NotNull @Valid Guidelines guidelines,
            @NotNull @Valid Population population,
            @NotNull @Valid HrmSourceSnapshotRef populationSourceRef,
            @NotNull LocalDate effectiveFrom,
            @JsonProperty(required = true) LocalDate effectiveTo) implements ClosedInput {
        public ReviseCycleRequest {
            validateDates(effectiveFrom, effectiveTo);
        }
    }

    public record BudgetEntryRequest(
            @NotNull UUID organizationPublicId,
            @NotBlank String amount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull LedgerEntryType ledgerEntryType,
            UUID reversesEntryPublicId) implements ClosedInput {
        public BudgetEntryRequest {
            amount = PostedMoney.require(amount);
            if (PostedMoney.decimal(amount).signum() <= 0) {
                throw new IllegalArgumentException("budget amount must be positive");
            }
            if ((ledgerEntryType == LedgerEntryType.ALLOCATE
                    && reversesEntryPublicId != null)
                    || (ledgerEntryType == LedgerEntryType.REVERSE
                    && reversesEntryPublicId == null)) {
                throw new IllegalArgumentException("budget source does not match entry type");
            }
        }
    }

    public record UpsertProposalRequest(@NotNull @Valid Proposal content)
            implements ClosedInput {
    }

    public record SubmitProposalRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String submissionDigest,
            LocalDate effectiveDate) implements ClosedInput {
    }

    public record ApprovePlanRequest(
            @NotBlank String decision,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9._-]{0,79}$")
            String reasonCode) implements ClosedInput {
        public ApprovePlanRequest {
            if (!"APPROVE".equals(decision)) {
                throw new IllegalArgumentException("only APPROVE is supported");
            }
        }
    }

    public record PublishSnapshotRequest(@NotNull UUID approvalReceiptId)
            implements ClosedInput {
    }

    public record CommandReceipt(
            UUID receiptId,
            String status,
            UUID aggregateId,
            Long aggregateVersion,
            UUID correlationId,
            Instant occurredAt,
            String payloadDigest) {
    }

    public record BudgetEntry(
            UUID entryId,
            long entrySequence,
            UUID organizationPublicId,
            String currency,
            String amount,
            LedgerEntryType entryType,
            UUID sourceEntryPublicId,
            Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProposalView(
            UUID proposalId,
            long aggregateVersion,
            UUID cycleId,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long currentRevision,
            ProposalStatus status,
            String contentDigest,
            boolean amountMasked,
            String amount,
            Proposal content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CycleView(
            UUID cycleId,
            long aggregateVersion,
            String cycleCode,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            CycleStatus status,
            Guidelines guidelines,
            Population population,
            boolean budgetMasked,
            List<BudgetEntry> budgetEntries) {
        public CycleView {
            budgetEntries = budgetEntries == null ? null : List.copyOf(budgetEntries);
        }
    }

    public record CycleCollection(
            List<CycleView> items,
            String nextCursor,
            boolean hasMore,
            Instant asOf,
            long scopeRevision,
            long fieldPolicyRevision) {
        public CycleCollection {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        }
    }

    public record CycleDetail(
            CycleView item,
            Instant referenceEffectiveAt,
            Instant freshnessWatermark,
            long fieldPolicyRevision) {
    }

    public record ProposalCollection(
            List<ProposalView> items,
            String nextCursor,
            boolean hasMore,
            Instant referenceEffectiveAt,
            Instant freshnessWatermark,
            long fieldPolicyRevision) {
        public ProposalCollection {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        }
    }

    public record ProposalDetail(
            ProposalView item,
            Instant referenceEffectiveAt,
            Instant freshnessWatermark,
            long fieldPolicyRevision) {
    }

    public record TypedChildResult(
            String entityType,
            UUID publicId,
            long aggregateVersion,
            int ordinal,
            String resultDigest) {
    }

    public record RichCommandResult(
            UUID commandReceiptId,
            String status,
            UUID correlationId,
            Instant completedAt,
            String resultDigest,
            UUID aggregatePublicId,
            long aggregateVersion,
            List<TypedChildResult> childResults) {
        public RichCommandResult {
            childResults = List.copyOf(Objects.requireNonNull(
                    childResults, "childResults must not be null"));
        }
    }

    public record SnapshotPublished(
            UUID approvalReceiptId,
            UUID cycleId,
            UUID snapshotId,
            long snapshotRevision,
            long sourceVersion,
            long aggregateVersion,
            int lineCount,
            String payloadDigest,
            UUID correlationId,
            Instant publishedAt) {
    }

    public record SnapshotLine(
            UUID lineId,
            int lineSequence,
            UUID proposalId,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            String approvedAmount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String lineDigest) {
    }

    public record ApprovedSnapshot(
            UUID approvalReceiptId,
            long approvalRevision,
            UUID cycleId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int lineCount,
            List<SnapshotLine> lines,
            String payloadDigest,
            UUID planId,
            UUID snapshotId,
            long snapshotRevision,
            long sourceVersion,
            long aggregateVersion,
            UUID correlationId,
            Instant occurredAt) {
        public ApprovedSnapshot {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        }
    }

    public static String canonicalIdempotencyKey(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1..128 opaque characters");
        }
        return value;
    }

    public static UUID canonicalCorrelationId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("X-Correlation-ID must be a canonical UUID");
        }
    }

    private static void validateDates(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "effectiveFrom must not be null");
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }
}
