package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(name = "ModernCompplanHrmSourceSnapshotRefV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record HrmSourceSnapshotRef(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID snapshotPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ownerRevision,
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String projectionSchemaId,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant asOf,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$")
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sha256,
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String purposeCode) implements ClosedInput {
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

    @Schema(name = "ModernCompplanCycleCreateRequestV2",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateCycleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9._-]{0,39}$") String cycleCode,
            @NotNull @Valid Guidelines guidelines,
            @NotNull @Valid Population population,
            @NotNull LocalDate effectiveFrom,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            LocalDate effectiveTo,
            @NotNull @Valid HrmSourceSnapshotRef populationSourceRef) implements ClosedInput {
        public CreateCycleRequest {
            validateDates(effectiveFrom, effectiveTo);
        }
    }

    @Schema(name = "ModernCompplanCycleReviseRequestV4",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ReviseCycleRequest(
            @NotNull @Valid Guidelines guidelines,
            @NotNull @Valid Population population,
            @NotNull @Valid HrmSourceSnapshotRef populationSourceRef,
            @NotNull LocalDate effectiveFrom,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            LocalDate effectiveTo) implements ClosedInput {
        public ReviseCycleRequest {
            validateDates(effectiveFrom, effectiveTo);
        }
    }

    @Schema(name = "ModernCompplanBudgetAllocateRequestV2",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record BudgetEntryRequest(
            @NotNull UUID organizationPublicId,
            @NotBlank String amount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull LedgerEntryType ledgerEntryType,
            @Schema(nullable = true)
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

    @Schema(name = "ModernCompplanProposalUpsertRequestV4",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record UpsertProposalRequest(@NotNull @Valid Proposal content)
            implements ClosedInput {
    }

    @Schema(name = "ModernCompplanProposalSubmitRequestV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SubmitProposalRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String submissionDigest,
            @Schema(nullable = true)
            LocalDate effectiveDate) implements ClosedInput {
    }

    @Schema(name = "ModernCompplanPlanApproveRequestV2",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
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

    @Schema(name = "ModernCompplanSnapshotPublishRequestV2",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PublishSnapshotRequest(@NotNull UUID approvalReceiptId)
            implements ClosedInput {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PerformanceCommandReceiptV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CommandReceipt(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID receiptId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            UUID aggregateId,
            Long aggregateVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID correlationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String payloadDigest) {
    }

    @Schema(name = "ModernCompplanBudgetEntryProjectionV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record BudgetEntry(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID entryId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long entrySequence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID organizationPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String amount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            LedgerEntryType entryType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            UUID sourceEntryPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
    }

    @Schema(name = "ModernCompplanBudgetAggregateV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record BudgetAggregate(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID organizationPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String allocatedAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String committedAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String remainingAmount) {
        public BudgetAggregate {
            Objects.requireNonNull(organizationPublicId,
                    "organizationPublicId must not be null");
            if (currency == null || !currency.matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException("currency must be an ISO alpha-3 code");
            }
            allocatedAmount = PostedMoney.require(allocatedAmount);
            committedAmount = PostedMoney.require(committedAmount);
            remainingAmount = PostedMoney.require(remainingAmount);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ModernCompplanProposalProjectionV4",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ProposalView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long aggregateVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID cycleId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID workerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID assignmentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String componentCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate effectiveFrom,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            LocalDate effectiveTo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long currentRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposalStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentDigest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean amountMasked,
            String amount,
            Proposal content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ModernCompplanCycleProjectionV5",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CycleView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID cycleId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long aggregateVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String cycleCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate effectiveFrom,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            LocalDate effectiveTo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CycleStatus status,
            Guidelines guidelines,
            Population population,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean budgetMasked,
            List<BudgetEntry> budgetEntries,
            List<BudgetAggregate> budgetAggregates) {
        public CycleView {
            budgetEntries = budgetEntries == null ? null : List.copyOf(budgetEntries);
            budgetAggregates = budgetAggregates == null
                    ? null : List.copyOf(budgetAggregates);
        }
    }

    @Schema(name = "ModernCompplanCyclesQueryResponseV5",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CycleCollection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CycleView> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant asOf,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scopeRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long fieldPolicyRevision) {
        public CycleCollection {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        }
    }

    @Schema(name = "ModernCompplanCycleQueryResponseV5",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CycleDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CycleView item,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant referenceEffectiveAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant freshnessWatermark,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long fieldPolicyRevision) {
    }

    @Schema(name = "ModernCompplanProposalsQueryResponseV4",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ProposalCollection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<ProposalView> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant referenceEffectiveAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant freshnessWatermark,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long fieldPolicyRevision) {
        public ProposalCollection {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        }
    }

    @Schema(name = "ModernCompplanProposalQueryResponseV4",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ProposalDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposalView item,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant referenceEffectiveAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant freshnessWatermark,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long fieldPolicyRevision) {
    }

    @Schema(name = "ModernCompplanTypedChildResultV3",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TypedChildResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String entityType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID publicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long aggregateVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ordinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resultDigest) {
    }

    @Schema(name = "ModernCompplanRichCommandResultV3",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RichCommandResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID commandReceiptId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID correlationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant completedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resultDigest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID aggregatePublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long aggregateVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<TypedChildResult> childResults) {
        public RichCommandResult {
            childResults = List.copyOf(Objects.requireNonNull(
                    childResults, "childResults must not be null"));
        }
    }

    @Schema(name = "ModernCompplanSnapshotPublishResponseV2",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SnapshotPublished(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID approvalReceiptId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID cycleId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID snapshotId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long snapshotRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sourceVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long aggregateVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int lineCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String payloadDigest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID correlationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant publishedAt) {
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
