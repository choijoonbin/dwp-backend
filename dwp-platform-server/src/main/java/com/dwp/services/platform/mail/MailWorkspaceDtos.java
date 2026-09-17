package com.dwp.services.platform.mail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Product contracts for the complete Mail user and administrator workspaces. */
public final class MailWorkspaceDtos {

    private MailWorkspaceDtos() {
    }

    public record Recipient(
            @NotNull RecipientType type,
            @Size(max = 160) String name,
            @NotBlank @Email @Size(max = 255) String email) {
    }

    public enum RecipientType { TO, CC, BCC }
    public enum BodyFormat { TEXT, HTML }
    public enum AssetScope { PERSONAL, ACCOUNT, ORGANIZATION }

    public record Attachment(
            UUID attachmentId,
            String fileName,
            String contentType,
            long sizeBytes,
            String scanState,
            long version,
            OffsetDateTime createdAt) {
    }

    public record ComposeCapabilities(
            boolean multipleRecipients,
            boolean cc,
            boolean bcc,
            boolean html,
            boolean attachments,
            boolean scheduling,
            long maximumAttachmentBytes) {
    }

    public record ComposeContext(
            List<MailDtos.AccountSummary> accounts,
            ComposeCapabilities capabilities,
            List<Template> templates,
            List<Signature> signatures,
            Preferences preferences,
            Map<String, String> variables,
            OffsetDateTime generatedAt) {
    }

    public record ComposeOptions(
            UUID accountId,
            @NotNull @Size(max = 500) List<@Valid Recipient> recipients,
            @NotNull BodyFormat bodyFormat,
            @NotNull @Size(max = 100) List<UUID> attachmentIds,
            OffsetDateTime scheduledAt,
            @Size(max = 80) String timeZone,
            UUID templateId,
            UUID signatureId) {
    }

    public record AdvancedComposeRequest(
            UUID accountId,
            @NotEmpty @Size(max = 500) List<@Valid Recipient> recipients,
            @NotBlank @Size(max = 500) String subject,
            @NotBlank @Size(max = 100_000) String body,
            @NotNull BodyFormat bodyFormat,
            @NotNull @Size(max = 100) List<UUID> attachmentIds,
            OffsetDateTime scheduleAt,
            @Size(max = 80) String timeZone,
            UUID templateId,
            UUID signatureId,
            @NotNull UUID idempotencyKey) {
    }

    public record AdvancedComposeResult(
            MailDtos.ThreadDetail thread,
            DeliveryReceipt receipt) {
    }

    public record SearchCriteria(
            String query,
            UUID accountId,
            String scope,
            String from,
            String to,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean unread,
            Boolean needsReply,
            Boolean hasAttachment,
            UUID folderId,
            String state,
            String lane) {
    }

    public record SavedView(
            UUID savedViewId,
            String name,
            SearchCriteria criteria,
            int sortOrder,
            boolean defaultView,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record SavedViewRequest(
            @NotBlank @Size(max = 160) String name,
            @NotNull @Valid SearchCriteria criteria,
            @Min(0) @Max(1000) Integer sortOrder,
            Boolean defaultView,
            @Min(0) Long version) {
    }

    public record FollowUp(
            UUID followUpId,
            UUID threadId,
            String subject,
            String participantName,
            String participantEmail,
            OffsetDateTime expectedReplyAt,
            String timeZone,
            String note,
            String status,
            OffsetDateTime lastCheckedAt,
            long version) {
    }

    public record FollowUpRequest(
            @NotNull OffsetDateTime expectedReplyAt,
            @NotBlank @Size(max = 80) String timeZone,
            @Size(max = 1000) String note,
            @Min(0) Long version) {
    }

    public record Template(
            UUID templateId,
            String name,
            String subject,
            String body,
            BodyFormat bodyFormat,
            AssetScope scope,
            UUID accountId,
            boolean editable,
            String mandatoryContent,
            boolean active,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record TemplateRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 100_000) String body,
            @NotNull BodyFormat bodyFormat,
            @NotNull AssetScope scope,
            UUID accountId,
            @Min(0) Long version) {
    }

    public record Signature(
            UUID signatureId,
            String name,
            String body,
            BodyFormat bodyFormat,
            AssetScope scope,
            UUID accountId,
            boolean defaultForNew,
            boolean defaultForReply,
            boolean editable,
            String mandatoryContent,
            boolean active,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record SignatureRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 50_000) String body,
            @NotNull BodyFormat bodyFormat,
            @NotNull AssetScope scope,
            UUID accountId,
            boolean defaultForNew,
            boolean defaultForReply,
            @Min(0) Long version) {
    }

    public record WritingAssets(List<Template> templates, List<Signature> signatures) {
    }

    public record Preferences(
            String density,
            String remoteImages,
            int sendDelaySeconds,
            boolean keyboardShortcuts,
            boolean notifyNewMail,
            boolean notifySharedAssignment,
            boolean notifyFollowUpDue,
            UUID defaultAccountId,
            UUID defaultSignatureId,
            Map<String, String> orgLocks,
            long version) {
    }

    public record PreferencesRequest(
            @NotBlank String density,
            @NotBlank String remoteImages,
            @Min(0) @Max(120) int sendDelaySeconds,
            boolean keyboardShortcuts,
            boolean notifyNewMail,
            boolean notifySharedAssignment,
            boolean notifyFollowUpDue,
            UUID defaultAccountId,
            UUID defaultSignatureId,
            @NotNull @Min(0) Long version) {
    }

    public record DeliverySummary(
            UUID deliveryId,
            UUID receiptId,
            UUID threadId,
            String subject,
            String recipientSummary,
            String accountName,
            String kind,
            OffsetDateTime requestedAt,
            OffsetDateTime scheduledAt,
            String state,
            boolean canReschedule,
            boolean canCancel,
            boolean canReconcile,
            long version) {
    }

    public record DeliveryPage(
            List<DeliverySummary> items,
            long total,
            int page,
            int pageSize,
            OffsetDateTime generatedAt) {
    }

    public record DeliveryTimeline(
            String state,
            OffsetDateTime occurredAt,
            String description,
            String source,
            String evidenceState,
            String code) {
    }

    public record DeliveryReceipt(
            UUID deliveryId,
            UUID receiptId,
            UUID threadId,
            String subject,
            String recipientSummary,
            String accountName,
            String kind,
            OffsetDateTime requestedAt,
            OffsetDateTime scheduledAt,
            String state,
            boolean canReschedule,
            boolean canCancel,
            boolean canReconcile,
            long version,
            List<Recipient> recipients,
            List<DeliveryTimeline> timeline,
            OffsetDateTime lastCheckedAt,
            List<Map<String, Object>> evidence,
            String retryEligibility) {
    }

    public record RescheduleRequest(
            @NotNull OffsetDateTime scheduledAt,
            @NotBlank @Size(max = 80) String timeZone,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record RuleOrderItem(@NotNull UUID ruleId, @NotNull @Min(0) Long version) {
    }

    public record RuleOrderRequest(@NotEmpty List<@Valid RuleOrderItem> rules) {
    }

    public record LifecyclePreview(
            UUID threadId,
            String action,
            boolean allowed,
            List<String> blockers,
            UUID targetFolderId,
            String targetFolderName,
            int affectedCount,
            long version) {
    }

    public record ProposalUpdateRequest(
            @NotNull Map<String, Object> proposedPayload,
            @NotNull @Min(0) Long version) {
    }

    public record GroupSendReceipt(
            UUID receiptId,
            UUID groupId,
            long groupVersion,
            String recipientMode,
            int recipientCount,
            UUID threadId,
            OffsetDateTime acceptedAt,
            String state) {
    }

    /* Administrator operations */

    public record AdminSourceEvidence(
            String sourceId,
            String state,
            OffsetDateTime observedAt,
            String errorCode) {
    }

    public record AdminException(
            String exceptionId,
            String kind,
            String severity,
            String safeResourceRef,
            Integer impactCount,
            OffsetDateTime lastObservedAt,
            String correlationId,
            String nextAction) {
    }

    public record AdminCommandAudit(
            UUID auditId,
            String commandType,
            String safeResourceRef,
            String actorName,
            String result,
            OffsetDateTime occurredAt,
            String correlationId) {
    }

    public record AdminOperationsSnapshot(
            OffsetDateTime generatedAt,
            List<AdminSourceEvidence> sources,
            List<AdminException> exceptions,
            List<AdminCommandAudit> commands) {
    }

    public record ConnectionOperationRequest(
            String capability,
            String scope,
            @Email String recipient,
            Boolean confirmedExternalImpact,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record ConnectionOperation(
            UUID operationId,
            UUID connectionId,
            String kind,
            String state,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            String correlationId,
            OffsetDateTime evidenceGeneratedAt,
            String errorCode,
            boolean replayed) {
    }

    public record AccessPermissions(
            boolean read,
            boolean sendAs,
            boolean sendOnBehalf,
            boolean assign,
            boolean manage) {
    }

    public record SharedInboxAccessMember(
            UUID memberId,
            Long userId,
            String displayName,
            String department,
            String state,
            OffsetDateTime expiresAt,
            AccessPermissions permissions,
            String providerState,
            long version) {
    }

    public record SharedInboxAccess(
            UUID sharedInboxId,
            long version,
            String providerState,
            List<SharedInboxAccessMember> members,
            AccessImpact impact) {
    }

    public record AccessImpact(int activeAssignments, int openDrafts,
                               int pendingCommands, boolean providerRevocationRequired) {
    }

    public record SharedInboxMemberRequest(
            @NotNull Long userId,
            @Size(max = 160) String displayName,
            @Size(max = 160) String department,
            @NotNull @Valid AccessPermissions permissions,
            OffsetDateTime expiresAt,
            boolean impactAcknowledged,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record SharedInboxMemberRevokeRequest(
            boolean impactAcknowledged,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record PolicyEvidenceRow(
            String policyKey,
            String configuredValue,
            String effectiveValue,
            String effectiveState,
            String scope,
            String evidenceSource,
            OffsetDateTime evidenceAt,
            String errorCode) {
    }

    public record PolicyHistory(
            UUID historyId,
            long version,
            String changedBy,
            OffsetDateTime changedAt,
            String diffSummary,
            String result,
            String correlationId) {
    }

    public record PolicyGovernance(
            OffsetDateTime generatedAt,
            long policyVersion,
            List<PolicyEvidenceRow> rows,
            List<PolicyHistory> history) {
    }

    public record ResourceRetentionPolicy(
            String resourceType,
            int configuredDays,
            Integer effectiveDays,
            String source,
            String evidenceState) {
    }

    public record LegalHold(
            UUID holdId,
            String name,
            String safeCaseRef,
            Map<String, Object> scope,
            String status,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            long version) {
    }

    public record LegalHoldRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 240) String safeCaseRef,
            @NotNull Map<String, Object> scope,
            @NotNull OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            @NotNull UUID idempotencyKey,
            @Min(0) Long version) {
    }

    public record LegalHoldReleaseRequest(
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record PurgeJob(
            UUID jobId,
            UUID candidateSnapshotId,
            String state,
            int deletedThreads,
            int deletedMessages,
            List<Map<String, Object>> stepResults,
            String verificationState,
            String errorCode,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
    }

    public record RetentionSnapshot(
            OffsetDateTime generatedAt,
            long policyVersion,
            List<ResourceRetentionPolicy> resourcePolicies,
            List<LegalHold> holds,
            List<PurgeJob> purgeJobs) {
    }

    public record PurgePreviewRequest(
            @NotNull Map<String, Object> scope,
            @NotEmpty List<String> resourceTypes,
            @NotNull OffsetDateTime before,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long policyVersion) {
    }

    public record PurgePreview(
            UUID candidateSnapshotId,
            String fingerprint,
            int totalCandidates,
            int heldCount,
            int eligibleCount,
            List<String> partialSources,
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt,
            long policyVersion) {
    }

    public record PurgeApprovalRequest(
            @NotBlank String decision,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long policyVersion) {
    }

    public record PurgeApproval(
            UUID approvalId,
            UUID candidateSnapshotId,
            int distinctApproverCount,
            long policyVersion,
            OffsetDateTime approvedAt) {
    }

    public record PurgeExecuteRequest(
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long policyVersion,
            @NotBlank @Size(min = 64, max = 64) String fingerprint) {
    }

    public record DeliveryAuditTimeline(
            String stage,
            String state,
            OffsetDateTime at,
            String source,
            String evidenceState,
            String code) {
    }

    public record DeliveryAuditItem(
            UUID deliveryId,
            String safeResourceRef,
            String commandType,
            String actorName,
            String accountName,
            String providerType,
            String stage,
            String state,
            String retryEligibility,
            String providerDisposition,
            String idempotencyState,
            boolean reconcileCapability,
            boolean cancelCapability,
            OffsetDateTime lastEvidenceAt,
            String correlationId,
            List<DeliveryAuditTimeline> timeline,
            long version) {
    }

    public record DeliveryAuditPage(
            List<DeliveryAuditItem> items,
            long total,
            int page,
            int pageSize,
            OffsetDateTime generatedAt) {
    }

    public record DeliveryRecoveryRequest(
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record DeliveryExportRequest(
            @NotNull Map<String, Object> filters,
            @NotBlank @Size(max = 500) String purpose,
            @NotNull UUID idempotencyKey) {
    }

    public record DeliveryExport(
            UUID exportId,
            String state,
            OffsetDateTime expiresAt,
            String watermark,
            String downloadUrl) {
    }
}
