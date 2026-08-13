package com.dwp.services.people.workforce;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkforceExportDtos {

    private WorkforceExportDtos() {
    }

    public record PreviewRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,79}") String datasetKey,
            @NotNull @Size(max = 12) Map<
                    @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{0,79}") String,
                    @Size(max = 240) String> selection) {
    }

    public record DatasetSummary(
            String datasetKey,
            String name,
            String description,
            List<String> requiredFieldGroups,
            List<String> allowedSelectionKeys,
            long version) {
    }

    public record Preview(
            boolean authorized,
            boolean executionEnabled,
            String datasetKey,
            List<String> allowedSelectionKeys,
            String populationType,
            List<UUID> organizationIds,
            List<String> fieldGroups,
            String exportFormat,
            String maskingProfile,
            String watermarkTemplate,
            int artifactTtlHours,
            int maximumAttempts,
            int maximumManualRetries,
            List<String> blockers,
            String message,
            Instant evaluatedAt) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,79}") String datasetKey,
            @NotNull @Size(max = 12) Map<
                    @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{0,79}") String,
                    @Size(max = 240) String> selection,
            @NotBlank @Pattern(regexp = "CSV") String exportFormat,
            @NotBlank @Size(max = 320) String recipientReference,
            @NotBlank @Size(min = 10, max = 1000) String purpose,
            @NotBlank @Size(min = 3, max = 240) String sourceReference) {
    }

    public record DecisionRequest(
            @PositiveOrZero long version,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record RequestSummary(
            UUID requestId,
            String datasetKey,
            Map<String, String> selection,
            String populationType,
            List<UUID> organizationIds,
            List<String> fieldGroups,
            String exportFormat,
            String maskingProfile,
            String watermarkText,
            String recipientReference,
            String purpose,
            String sourceReference,
            String lifecycleState,
            boolean executionEnabled,
            List<String> blockers,
            String requestSha256,
            String artifactSha256,
            Long artifactSizeBytes,
            Instant artifactExpiresAt,
            int attemptCount,
            int retryCycleAttemptCount,
            int manualRetryCount,
            Instant nextAttemptAt,
            Instant cancellationRequestedAt,
            Instant completedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AttemptEvent(
            UUID attemptEventId,
            int attemptNumber,
            String eventType,
            String workerReference,
            String failureCode,
            String redactedFailureMessage,
            String artifactSha256,
            Long artifactSizeBytes,
            Instant occurredAt) {
    }

    public record ArtifactEvidence(
            @NotBlank @Size(max = 1000) String artifactReference,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String artifactSha256,
            @NotNull @PositiveOrZero Long artifactSizeBytes,
            @NotNull Instant artifactExpiresAt) {
    }
}
