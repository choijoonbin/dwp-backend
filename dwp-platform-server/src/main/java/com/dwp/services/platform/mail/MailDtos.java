package com.dwp.services.platform.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;

public final class MailDtos {

    private MailDtos() {
    }

    public record AccountSummary(
            UUID accountId,
            String emailAddress,
            String displayName,
            String accountKind,
            ProviderType providerType,
            String connectionState,
            String synchronizationState,
            boolean defaultAccount) {
    }

    public record Participant(String name, String email) {
    }

    public record ThreadSummary(
            UUID threadId,
            UUID accountId,
            String accountName,
            String folderType,
            UUID sharedInboxId,
            String sharedInboxName,
            String subject,
            String preview,
            List<Participant> participants,
            OffsetDateTime latestMessageAt,
            boolean unread,
            boolean starred,
            Importance importance,
            TriageLane triageLane,
            WorkflowState workflowState,
            OffsetDateTime snoozedUntil,
            Long assignedUserId,
            String assignedName,
            boolean attachments,
            boolean externalSender,
            Classification classification,
            int messageCount,
            long version) {
    }

    public record Message(
            UUID messageId,
            String senderEmail,
            String senderName,
            List<Map<String, Object>> recipients,
            String direction,
            String bodyFormat,
            String body,
            List<Map<String, Object>> attachments,
            OffsetDateTime sentAt,
            DeliveryState deliveryState,
            OffsetDateTime acceptedAt,
            String lastDeliveryError) {
    }

    public record InternalComment(
            UUID commentId,
            Long authorUserId,
            String authorName,
            String body,
            List<Long> mentionedUserIds,
            OffsetDateTime createdAt) {
    }

    public record ActionProposal(
            UUID proposalId,
            UUID threadId,
            ProposalType type,
            int actionContractVersion,
            ProposalStatus status,
            String title,
            String summary,
            List<Map<String, Object>> evidence,
            Map<String, Object> proposedPayload,
            BigDecimal confidence,
            String riskLevel,
            String requiredResourceKey,
            String requiredPermissionCode,
            String targetRoute,
            OffsetDateTime expiresAt,
            long version) {
    }

    public record ThreadDetail(
            ThreadSummary thread,
            List<Message> messages,
            List<InternalComment> internalComments,
            List<ActionProposal> proposals,
            List<SharedInboxMember> sharedInboxMembers) {
    }

    public record SharedInboxMember(
            Long userId,
            String displayName,
            String emailAddress,
            String memberRole) {
    }

    public record HomeMetrics(
            int unread,
            int urgent,
            int needsReply,
            int assigned,
            int snoozed,
            int activeProposals) {
    }

    public record SharedInboxPulse(
            UUID sharedInboxId,
            String name,
            String address,
            int openCount,
            int unassignedCount,
            int overdueCount,
            int serviceTargetMinutes) {
    }

    public record HomeResponse(
            List<AccountSummary> accounts,
            HomeMetrics metrics,
            List<ThreadSummary> focusQueue,
            List<ActionProposal> proposals,
            List<SharedInboxPulse> sharedInboxes,
            OffsetDateTime generatedAt) {
    }

    public record ThreadPage(
            List<ThreadSummary> items,
            long total,
            int page,
            int pageSize) {
    }

    public record ThreadActionRequest(
            @NotNull ThreadAction action,
            @NotNull @Min(0) Long version) {
    }

    public record SnoozeRequest(
            @NotNull OffsetDateTime until,
            @NotNull @Min(0) Long version) {
    }

    public record AssignRequest(
            @NotNull Long assignedUserId,
            @NotBlank @Size(max = 160) String assignedName,
            @NotNull @Min(0) Long version) {
    }

    public record CommentRequest(
            @NotBlank @Size(max = 4000) String body,
            @NotNull @Size(max = 100) List<Long> mentionedUserIds) {
    }

    public record ReplyRequest(
            @NotBlank @Size(max = 100_000) String body,
            @NotNull UUID idempotencyKey) {
    }

    public record ComposeRequest(
            @NotBlank @Email @Size(max = 255) String toEmail,
            @Size(max = 160) String toName,
            @NotBlank @Size(max = 500) String subject,
            @NotBlank @Size(max = 100_000) String body,
            @NotNull DeliveryMode deliveryMode,
            @NotNull UUID idempotencyKey) {
    }

    public record DraftUpdateRequest(
            @NotBlank @Email @Size(max = 255) String toEmail,
            @Size(max = 160) String toName,
            @NotBlank @Size(max = 500) String subject,
            @NotBlank @Size(max = 100_000) String body,
            @NotNull DeliveryMode deliveryMode,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record ProposalDecisionRequest(
            @NotNull ProposalDecision decision,
            @NotNull @Min(0) Long version) {
    }

    public record ProviderDescriptor(
            ProviderType providerType,
            String name,
            String protocol,
            String authenticationMode,
            List<String> capabilities,
            boolean pushSupported,
            boolean tenantWideSupported,
            AdapterRuntimeState runtimeState,
            String adapterVersion) {
    }

    public record ConnectionSummary(
            UUID connectionId,
            String connectionKey,
            String displayName,
            ProviderType providerType,
            String authenticationMode,
            String mailDomain,
            ConnectionState state,
            List<String> capabilities,
            boolean credentialConfigured,
            OffsetDateTime lastSynchronizedAt,
            String lastErrorCode,
            long version) {
    }

    public record SharedInboxSummary(
            UUID sharedInboxId,
            String inboxKey,
            String displayName,
            String address,
            String purpose,
            int serviceTargetMinutes,
            String lifecycleState,
            int openCount,
            int overdueCount,
            long version) {
    }

    public record TenantPolicy(
            boolean externalSenderBanner,
            boolean blockRemoteImages,
            boolean allowSharedInboxes,
            boolean aiAssistanceEnabled,
            boolean aiCrossAppActionsEnabled,
            boolean aiAutoExecuteEnabled,
            int retentionDays,
            int maximumAttachmentMb,
            long version) {
    }

    public record TenantPolicyRequest(
            boolean externalSenderBanner,
            boolean blockRemoteImages,
            boolean allowSharedInboxes,
            boolean aiAssistanceEnabled,
            boolean aiCrossAppActionsEnabled,
            @Min(30) @Max(3650) int retentionDays,
            @Min(1) @Max(150) int maximumAttachmentMb,
            @NotNull @Min(0) Long version) {
    }

    public record ConnectionUpdateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @Pattern(regexp = "^$|^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
            @Size(max = 255) String mailDomain,
            @Pattern(regexp = "^$|^(vault|aws-sm|gcp-sm|azure-kv|secret)://[A-Za-z0-9._/@:-]{1,470}$")
            @Size(max = 500) String credentialRef,
            @NotNull ConnectionState state,
            @NotNull @Min(0) Long version) {
    }

    public record SharedInboxUpdateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 1000) String purpose,
            @Min(15) @Max(10080) int serviceTargetMinutes,
            @NotNull @Pattern(regexp = "ACTIVE|ARCHIVED") String lifecycleState,
            @NotNull @Min(0) Long version) {
    }

    public record AdminOverview(
            int personalAccounts,
            int sharedAccounts,
            int activeConnections,
            int degradedConnections,
            int openSharedThreads,
            int pendingAiProposals,
            int queuedDeliveries,
            int failedDeliveries,
            TenantPolicy policy,
            List<ConnectionSummary> connections,
            List<SharedInboxSummary> sharedInboxes,
            List<ProviderDescriptor> providerCatalog,
            OffsetDateTime generatedAt) {
    }
}
