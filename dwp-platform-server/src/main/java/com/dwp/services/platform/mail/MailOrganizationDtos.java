package com.dwp.services.platform.mail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;
import static com.dwp.services.platform.mail.MailTypes.Importance;

public final class MailOrganizationDtos {

    private MailOrganizationDtos() {
    }

    public record FolderSummary(
            UUID folderId,
            UUID accountId,
            UUID parentFolderId,
            String folderKey,
            String displayName,
            String folderType,
            FolderColor color,
            ProviderSyncState synchronizationState,
            int sortOrder,
            int totalCount,
            int unreadCount,
            long version) {
    }

    public record RuleCondition(
            @NotNull RuleField field,
            @NotNull RuleOperator operator,
            @NotBlank @Size(max = 500) String value) {
    }

    public record RuleAction(
            @NotNull RuleActionType type,
            UUID folderId,
            Importance importance) {
    }

    public record RuleSummary(
            UUID ruleId,
            UUID accountId,
            String displayName,
            int priority,
            RuleMatchMode matchMode,
            List<RuleCondition> conditions,
            List<RuleAction> actions,
            boolean stopProcessing,
            boolean enabled,
            ProviderSyncState synchronizationState,
            OffsetDateTime lastRunAt,
            int lastMatchCount,
            long version) {
    }

    public record RuleRunSummary(
            UUID runId,
            UUID ruleId,
            String triggerKind,
            String status,
            int scannedCount,
            int matchedCount,
            int changedCount,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
    }

    public record OrganizationResponse(
            List<MailDtos.AccountSummary> accounts,
            List<FolderSummary> folders,
            List<RuleSummary> rules,
            List<RuleRunSummary> recentRuns,
            OffsetDateTime generatedAt) {
    }

    public record FolderCreateRequest(
            @NotNull UUID accountId,
            UUID parentFolderId,
            @NotBlank @Size(max = 160) String displayName,
            @NotNull FolderColor color) {
    }

    public record FolderUpdateRequest(
            UUID parentFolderId,
            @NotBlank @Size(max = 160) String displayName,
            @NotNull FolderColor color,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record RuleCreateRequest(
            @NotNull UUID accountId,
            @NotBlank @Size(max = 160) String displayName,
            @Min(1) @Max(10000) int priority,
            @NotNull RuleMatchMode matchMode,
            @NotEmpty @Size(max = 10) List<@Valid RuleCondition> conditions,
            @NotEmpty @Size(max = 8) List<@Valid RuleAction> actions,
            boolean stopProcessing,
            boolean enabled) {
    }

    public record RuleUpdateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @Min(1) @Max(10000) int priority,
            @NotNull RuleMatchMode matchMode,
            @NotEmpty @Size(max = 10) List<@Valid RuleCondition> conditions,
            @NotEmpty @Size(max = 8) List<@Valid RuleAction> actions,
            boolean stopProcessing,
            boolean enabled,
            @NotNull @Min(0) Long version) {
    }

    public record LifecycleRequest(
            @NotNull LifecycleAction action,
            UUID targetFolderId,
            @NotNull @Min(0) Long version) {
    }

    public record LifecycleResult(
            MailDtos.ThreadSummary thread,
            boolean deleted) {
    }
}
