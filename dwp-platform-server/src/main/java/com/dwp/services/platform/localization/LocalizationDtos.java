package com.dwp.services.platform.localization;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LocalizationDtos {

    private LocalizationDtos() {
    }

    public record Workspace(
            long bundleCount,
            long draftCount,
            long reviewCount,
            long publishedCount,
            long issueCount,
            List<BundleSummary> bundles) {
    }

    public record BundleSummary(
            UUID bundleId,
            String bundleKey,
            String sourceLocale,
            String targetLocale,
            String lifecycleState,
            UUID currentPublishedRevisionId,
            Long currentPublishedRevisionNumber,
            String openRevisionState,
            Long openRevisionNumber,
            double completeness,
            long issueCount,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record Revision(
            UUID revisionId,
            UUID bundleId,
            String bundleKey,
            String sourceLocale,
            String targetLocale,
            long revisionNumber,
            UUID basedOnRevisionId,
            Map<String, String> sourceEntries,
            Map<String, String> entries,
            String lifecycleState,
            String changeSummary,
            String contentSha256,
            Long submittedBy,
            OffsetDateTime submittedAt,
            Long decidedBy,
            OffsetDateTime decidedAt,
            Long publishedBy,
            OffsetDateTime publishedAt,
            long version,
            OffsetDateTime createdAt,
            Long createdBy,
            OffsetDateTime updatedAt,
            List<Decision> decisions,
            Preview preview) {
    }

    public record Decision(
            UUID decisionId,
            String previousState,
            String decision,
            String reason,
            long actorId,
            OffsetDateTime decidedAt) {
    }

    public record Preview(
            Map<String, String> resolvedEntries,
            List<String> missingKeys,
            List<String> fallbackKeys,
            List<String> unknownKeys,
            List<PlaceholderIssue> placeholderIssues,
            double completeness,
            boolean publishable) {
    }

    public record PlaceholderIssue(
            String key,
            List<String> expected,
            List<String> actual) {
    }

    public record Diff(
            UUID revisionId,
            UUID comparedWithRevisionId,
            long added,
            long updated,
            long removed,
            long unchanged,
            List<DiffEntry> entries) {
    }

    public record DiffEntry(
            String key,
            String changeType,
            String sourceValue,
            String beforeValue,
            String afterValue,
            boolean fallback) {
    }

    public record CreateBundleRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z][a-z0-9.-]{2,119}$")
            String bundleKey,
            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")
            String sourceLocale,
            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")
            String targetLocale,
            @NotEmpty @Size(max = 2_000) Map<String, String> sourceEntries,
            @NotNull @Size(max = 2_000) Map<String, String> entries,
            @NotBlank @Size(min = 5, max = 1_000) String changeSummary) {
    }

    public record SaveDraftRequest(
            @NotEmpty @Size(max = 2_000) Map<String, String> sourceEntries,
            @NotNull @Size(max = 2_000) Map<String, String> entries,
            @NotBlank @Size(min = 5, max = 1_000) String changeSummary,
            @NotNull @Min(0) Long version) {
    }

    public record TransitionRequest(
            @NotBlank @Size(min = 5, max = 1_000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(min = 5, max = 1_000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record RestoreRequest(
            @NotBlank @Size(min = 5, max = 1_000) String changeSummary) {
    }
}
