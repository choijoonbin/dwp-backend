package com.dwp.services.provider.resourcegovernance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provider-owned records for internal capacity and release-governance evidence.
 *
 * <p>These records intentionally never model a vendor invoice, registry signature, package
 * distribution, or deployment result. Those facts require the corresponding external owner.
 */
public final class ResourceGovernanceDtos {

    private ResourceGovernanceDtos() {
    }

    public record Commitment(
            UUID providerTenantId,
            String tenantKey,
            String tenantDisplayName,
            String resourceKey,
            String unit,
            BigDecimal quotaLimit,
            BigDecimal budgetLimit,
            String currencyCode,
            String lifecycleState,
            String sourceSystem,
            String externalFeedState,
            String controlMode,
            String controlScope,
            ActiveOverride activeOverride,
            ControlPeriod controlPeriod,
            InternalEvidenceFreshness internalEvidenceFreshness,
            long version,
            Instant updatedAt,
            LedgerTotals totals) {
    }

    /** An approved temporary overlay; it expires automatically at read/enforcement time. */
    public record ActiveOverride(
            UUID changeRequestId,
            Instant expiresAt) {
    }

    /** The period applies only to provider-owned internal ledger evidence. */
    public record ControlPeriod(
            Instant startsAt,
            Instant endsAt,
            String state) {
    }

    /**
     * Timestamp transparency for internal evidence.  This deliberately does not call an
     * unavailable vendor meter fresh, current, or reconciled.
     */
    public record InternalEvidenceFreshness(
            String state,
            long entryCount,
            Instant latestOccurredAt,
            Instant latestRecordedAt) {
    }

    public record LedgerTotals(
            BigDecimal allocated,
            BigDecimal released,
            BigDecimal adjusted,
            BigDecimal meteredInternalEvidence,
            BigDecimal budgetReserved,
            BigDecimal budgetReleased,
            BigDecimal budgetSpentInternalEvidence,
            BigDecimal allocationBalance,
            BigDecimal remainingQuota,
            BigDecimal budgetReservationBalance,
            BigDecimal remainingBudget,
            String quotaControlState,
            String budgetControlState) {
    }

    public record LedgerEntry(
            UUID ledgerEntryId,
            UUID providerTenantId,
            String resourceKey,
            String entryType,
            BigDecimal amount,
            String unit,
            String currencyCode,
            String evidenceRef,
            String idempotencyKey,
            String reason,
            Instant occurredAt,
            Instant controlPeriodStartsAt,
            Instant controlPeriodEndsAt,
            Long recordedBy,
            Instant recordedAt) {
    }

    public record LedgerPage(
            List<LedgerEntry> items,
            int limit,
            boolean hasMore) {
    }

    public record CommitmentDefinition(
            String unit,
            BigDecimal quotaLimit,
            BigDecimal budgetLimit,
            String currencyCode,
            Instant controlPeriodStartsAt,
            Instant controlPeriodEndsAt,
            String controlMode,
            String lifecycleState) {
    }

    /**
     * A resource change is a review record, not a vendor capacity reservation or invoice fact.
     * CONTRACT_CHANGE requires a published commercial renewal for the tenant organization;
     * TEMPORARY_OVERRIDE is time bounded and never mutates the commercial contract.
     */
    public record ResourceCommitmentChange(
            UUID changeRequestId,
            UUID providerTenantId,
            String tenantKey,
            String tenantDisplayName,
            String resourceKey,
            String changeKind,
            Long baselineCommitmentVersion,
            CommitmentDefinition baseline,
            CommitmentDefinition proposed,
            UUID commercialRenewalRevisionId,
            Instant overrideExpiresAt,
            String lifecycleState,
            String reservationState,
            String justification,
            Instant decisionDueAt,
            long requestedBy,
            Instant requestedAt,
            Long decidedBy,
            Instant decidedAt,
            String decisionReason,
            Long publishedBy,
            Instant publishedAt,
            long version,
            Instant updatedAt) {
    }

    public record ArtifactManifest(
            UUID artifactId,
            String productKey,
            String artifactVersion,
            String artifactType,
            int manifestSchemaVersion,
            JsonNode manifest,
            JsonNode compatibilityPolicy,
            ArtifactCompatibilitySummary compatibility,
            String declaredDigest,
            String lifecycleState,
            String compatibilityState,
            JsonNode compatibilityEvidence,
            String signatureState,
            String distributionState,
            long createdBy,
            long updatedBy,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<ArtifactReview> reviews) {
    }

    /** Typed, declared compatibility facts; unavailable or absent declarations remain explicit. */
    public record ArtifactCompatibilitySummary(
            ArtifactSchemaCompatibility schema,
            List<ArtifactClientCompatibility> clients,
            List<ArtifactDependencyCompatibility> dependencies,
            ArtifactCapabilityDelta capabilities,
            RollbackReadiness rollbackReadiness) {
    }

    public record ArtifactSchemaCompatibility(
            String state,
            String currentVersion,
            String targetVersion,
            String migrationState) {
    }

    public record ArtifactClientCompatibility(
            String clientType,
            String minimumVersion,
            String state) {
    }

    public record ArtifactDependencyCompatibility(
            String dependencyKey,
            String requiredVersion,
            String observedVersion,
            String state) {
    }

    public record ArtifactCapabilityDelta(
            List<String> added,
            List<String> removed,
            List<String> increased) {
    }

    /** Readiness is a declaration boundary, never a registry or runtime receipt. */
    public record RollbackReadiness(
            String state,
            List<String> reasons,
            String executionBoundary) {
    }

    public record ArtifactReview(
            UUID reviewId,
            UUID artifactId,
            String decision,
            String reason,
            JsonNode evidence,
            long reviewedBy,
            Instant reviewedAt) {
    }

    public record ArtifactRolloutPlan(
            UUID rolloutPlanId,
            UUID artifactId,
            String productKey,
            String artifactVersion,
            String name,
            JsonNode targetScope,
            JsonNode stages,
            JsonNode rollbackPlan,
            String rollbackFeasibility,
            String lifecycleState,
            String executorState,
            String reason,
            long requestedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            String decisionReason,
            long version,
            Instant createdAt,
            Instant updatedAt,
            RollbackReadiness rollbackReadiness,
            List<ArtifactRolloutEvidence> evidence) {
    }

    public record ArtifactRolloutEvidence(
            UUID evidenceId,
            UUID rolloutPlanId,
            String evidenceType,
            String evidenceState,
            JsonNode evidence,
            String source,
            long recordedBy,
            Instant recordedAt) {
    }

    public record UpsertCommitmentRequest(
            @NotBlank
            @Pattern(regexp = "SEAT|GIB|REQUEST|CURRENCY_MINOR|COUNT")
            String unit,
            @DecimalMin("0.0") BigDecimal quotaLimit,
            @DecimalMin("0.0") BigDecimal budgetLimit,
            @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @NotNull Instant controlPeriodStartsAt,
            @NotNull Instant controlPeriodEndsAt,
            @NotBlank
            @Pattern(regexp = "SOFT_ALERT|HARD_BLOCK")
            String controlMode,
            @NotBlank
            @Pattern(regexp = "ACTIVE|SUSPENDED|RETIRED")
            String lifecycleState,
            @Min(0) Long version) {
    }

    public record CreateResourceCommitmentChangeRequest(
            @NotBlank
            @Pattern(regexp = "CONTRACT_CHANGE|TEMPORARY_OVERRIDE")
            String changeKind,
            @Min(0) Long baselineCommitmentVersion,
            @NotNull @Valid UpsertCommitmentRequest proposed,
            UUID commercialRenewalRevisionId,
            Instant overrideExpiresAt,
            @NotBlank @Size(max = 160) String requestKey,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record ResourceCommitmentChangeDecisionRequest(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record AppendLedgerEntryRequest(
            @NotBlank
            @Pattern(regexp = "ALLOCATE|RELEASE|METER|ADJUST|BUDGET_RESERVE|BUDGET_RELEASE|BUDGET_SPEND")
            String entryType,
            @NotNull @Positive BigDecimal amount,
            @NotBlank
            @Pattern(regexp = "SEAT|GIB|REQUEST|CURRENCY_MINOR|COUNT")
            String unit,
            @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @NotBlank @Size(max = 500) String evidenceRef,
            @NotBlank @Size(max = 160) String idempotencyKey,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Instant occurredAt) {
    }

    public record TenantLifecycleRequest(
            UUID lifecycleRequestId,
            UUID providerTenantId,
            String tenantKey,
            String tenantDisplayName,
            String requestedAction,
            String lifecycleState,
            String holdEvaluationState,
            List<String> holdEvidenceRefs,
            String executionState,
            String justification,
            long requestedBy,
            Long submittedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            String decisionReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CreateTenantLifecycleRequest(
            @NotBlank @Pattern(regexp = "RETIRE|PURGE") String requestedAction,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record TenantLifecycleDecisionRequest(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record CreateArtifactManifestRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z][a-z0-9._-]{2,119}$")
            String productKey,
            @NotBlank @Size(max = 80) String artifactVersion,
            @NotBlank
            @Pattern(regexp = "WEB_APP|SERVICE|WORKER|SCHEMA|CONFIG_BUNDLE")
            String artifactType,
            @Positive int manifestSchemaVersion,
            @NotNull JsonNode manifest,
            @NotNull JsonNode compatibilityPolicy,
            @Pattern(regexp = "^[0-9a-f]{64}$") String declaredDigest) {
    }

    public record AssessArtifactCompatibilityRequest(
            @Min(0) long version,
            @NotBlank
            @Pattern(regexp = "COMPATIBLE|REVIEW_REQUIRED|BLOCKED")
            String compatibilityState,
            @NotNull JsonNode evidence,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record VersionedReasonRequest(
            @Min(0) long version,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record ArtifactReviewDecisionRequest(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "APPROVED|RETURNED") String decision,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull JsonNode evidence) {
    }

    public record CreateArtifactRolloutPlanRequest(
            @NotNull UUID artifactId,
            @NotBlank @Size(max = 200) String name,
            @NotNull JsonNode targetScope,
            @NotNull JsonNode stages,
            JsonNode rollbackPlan,
            @NotBlank
            @Pattern(regexp = "DECLARED|NOT_DECLARED|UNAVAILABLE")
            String rollbackFeasibility,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record ArtifactRolloutDecisionRequest(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record AppendArtifactEvidenceRequest(
            @NotBlank
            @Pattern(regexp = "COMPATIBILITY|PRE_FLIGHT|OBSERVATION|ROLLBACK_FEASIBILITY|MANUAL_RECEIPT")
            String evidenceType,
            @NotBlank
            @Pattern(regexp = "PASSED|FAILED|INCONCLUSIVE|NOT_DISPATCHED")
            String evidenceState,
            @NotNull JsonNode evidence) {
    }
}
