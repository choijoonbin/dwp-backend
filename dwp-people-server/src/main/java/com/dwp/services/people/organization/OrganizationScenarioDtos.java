package com.dwp.services.people.organization;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrganizationScenarioDtos {

    private OrganizationScenarioDtos() {
    }

    public record Scenario(
            UUID scenarioId,
            String scenarioKey,
            String name,
            String description,
            UUID sourceScenarioId,
            LocalDate baselineDate,
            LocalDate effectiveDate,
            String lifecycleState,
            Long ownerUserId,
            Instant submittedAt,
            Instant publishedAt,
            UUID publicationValidationRunId,
            String publicationEvidenceState,
            long version,
            List<Change> changes,
            Approval approval) {
    }

    public record Change(
            UUID changeId,
            int sequence,
            String changeType,
            int payloadSchemaVersion,
            String targetKind,
            String targetReference,
            String relatedReference,
            LocalDate effectiveDate,
            String beforeSnapshot,
            String afterSnapshot,
            int estimatedHeadcountDelta,
            double estimatedFteDelta,
            BigDecimal estimatedCostDelta,
            String costCurrency,
            String validationState,
            String validationMessage,
            long version) {
    }

    public record Approval(
            UUID approvalId,
            String gateKey,
            String requiredRoleCode,
            boolean separationOfDuties,
            String lifecycleState,
            Long requestedBy,
            Long decidedBy,
            String requestReason,
            String decisionReason,
            Instant requestedAt,
            Instant decidedAt,
            Instant expiresAt,
            UUID requestValidationRunId,
            UUID decisionValidationRunId,
            String evidenceBindingState,
            long version) {
    }

    public record DecisionPack(
            UUID scenarioId,
            long scenarioVersion,
            String lifecycleState,
            LocalDate baselineDate,
            LocalDate effectiveDate,
            String decisionState,
            int readinessScore,
            boolean baselineCurrent,
            String baselineFingerprint,
            String observedFingerprint,
            int blockingIssueCount,
            int warningCount,
            DecisionMetrics baseline,
            DecisionMetrics proposed,
            DecisionMetrics delta,
            List<DecisionCheck> checks,
            UUID validationRunId,
            Instant evaluatedAt) {
    }

    public record DecisionMetrics(
            int headcount,
            int organizationCount,
            int managerCount,
            int openPositionCount,
            BigDecimal plannedFte,
            BigDecimal workforceCost,
            String costCurrency,
            double averageManagerSpan,
            int maximumLayers,
            int organizationHealthScore,
            int dataQualityScore) {
    }

    public record DecisionCheck(
            String checkCode,
            String outcome,
            String severity,
            String entityType,
            String entityReference,
            Map<String, Object> evidence) {
    }

    public record ValidationRunSummary(
            UUID validationRunId,
            long scenarioVersion,
            String triggerType,
            String decisionState,
            int readinessScore,
            boolean baselineCurrent,
            int blockingIssueCount,
            int warningCount,
            Instant evaluatedAt,
            Long evaluatedBy,
            String correlationId) {
    }

    public record CreateScenarioRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,99}") String scenarioKey,
            @NotBlank @Size(max = 240) String name,
            @Size(max = 2000) String description,
            @NotNull LocalDate baselineDate,
            @NotNull LocalDate effectiveDate) {
    }

    public record CloneScenarioRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,99}") String scenarioKey,
            @NotBlank @Size(max = 240) String name,
            @Size(max = 2000) String description,
            @NotNull LocalDate effectiveDate) {
    }

    public record AddOrganizationMoveRequest(
            @NotNull UUID organizationId,
            @NotNull UUID newParentOrganizationId,
            @NotNull Long version) {
    }

    public record AddPositionMoveRequest(
            @NotNull UUID positionId,
            @NotNull UUID newParentPositionId,
            @NotNull Long version) {
    }

    public record CreatePositionRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,99}")
            String positionKey,
            @NotBlank @Size(max = 240) String title,
            @NotNull UUID organizationId,
            @NotNull UUID reportsToPositionId,
            @NotBlank
            @Pattern(regexp = "REGULAR|SHARED|ASSISTANT|TEMPORARY")
            String positionType,
            @NotBlank
            @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL")
            String criticality,
            @NotNull @DecimalMin("0.1") @DecimalMax("10.0") BigDecimal budgetedFte,
            @DecimalMin("0.0") BigDecimal annualCostAmount,
            @Pattern(regexp = "[A-Z]{3}") String costCurrency,
            @NotNull LocalDate availabilityDate,
            @NotNull Long version) {
    }

    public record ClosePositionRequest(@NotNull Long version) {
    }

    public record ValidateScenarioRequest(
            @NotNull Long version) {
    }

    public record SubmitScenarioRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long version) {
    }

    public record DecideScenarioRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long version) {
    }

    public record CancelScenarioRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long version) {
    }

    public record PublishScenarioRequest(@NotNull Long version) {
    }
}
