package com.dwp.services.platform.navigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class NavigationStudioDtos {

    private NavigationStudioDtos() {
    }

    public record ValidationIssue(
            String severity,
            String code,
            Long navigationItemId,
            String navigationKey,
            String message) {
    }

    public record ValidationReport(
            boolean valid,
            long errorCount,
            long warningCount,
            List<ValidationIssue> issues,
            OffsetDateTime checkedAt) {
    }

    public record DiffSummary(
            long added,
            long removed,
            long changed,
            long reordered,
            long lifecycleChanged) {
    }

    public record Revision(
            UUID navigationRevisionId,
            long revisionNumber,
            String lifecycleState,
            UUID baselineRevisionId,
            String baselineTreeHash,
            List<NavigationDtos.AdminNode> tree,
            ValidationReport validation,
            DiffSummary diff,
            String changeSummary,
            long version,
            OffsetDateTime createdAt,
            Long createdBy,
            OffsetDateTime updatedAt,
            OffsetDateTime publishedAt,
            Long publishedBy) {
    }

    public record Workspace(
            Revision published,
            Revision draft,
            List<Revision> history,
            List<NavigationDtos.AdminNode> currentTree,
            ValidationReport currentValidation) {
    }

    public record CreateDraftRequest(@Size(max = 500) String changeSummary) {
    }

    public record SaveDraftRequest(
            @Valid @NotNull @Size(max = 500) List<NavigationDtos.AdminNode> tree,
            @Size(max = 500) String changeSummary,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }
}
