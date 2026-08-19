package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;

@Service
public class MailService {

    private static final Set<String> FOLDERS = Set.of(
            "INBOX", "SENT", "DRAFTS", "ARCHIVE", "SPAM", "TRASH", "CUSTOM");

    private final MailQueryRepository queries;
    private final MailCommandRepository commands;
    private final MailProviderCatalog providerCatalog;
    private final MailDeliveryCompletionService deliveryCompletion;

    public MailService(
            MailQueryRepository queries,
            MailCommandRepository commands,
            MailProviderCatalog providerCatalog,
            MailDeliveryCompletionService deliveryCompletion) {
        this.queries = queries;
        this.commands = commands;
        this.providerCatalog = providerCatalog;
        this.deliveryCompletion = deliveryCompletion;
    }

    @Transactional(readOnly = true)
    public MailDtos.HomeResponse home(Long tenantId, Long userId) {
        List<MailDtos.AccountSummary> accounts = queries.accounts(tenantId, userId);
        requireMailbox(accounts);
        return new MailDtos.HomeResponse(
                accounts,
                queries.metrics(tenantId, userId),
                queries.threads(tenantId, userId, "", "", "INBOX", false, "", 0, 6),
                queries.proposals(tenantId, userId, null, 4),
                queries.sharedInboxPulse(tenantId, userId),
                OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public MailDtos.ThreadPage threads(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            boolean sharedOnly,
            String search,
            int page,
            int pageSize) {
        requireMailbox(queries.accounts(tenantId, userId));
        String resolvedLane = enumValue(lane, TriageLane.class);
        String resolvedState = enumValue(state, WorkflowState.class);
        String resolvedFolder = folderValue(folder);
        String resolvedSearch = normalizeSearch(search);
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        return new MailDtos.ThreadPage(
                queries.threads(
                        tenantId, userId, resolvedLane, resolvedState,
                        resolvedFolder, sharedOnly, resolvedSearch,
                        resolvedPage, resolvedPageSize),
                queries.threadCount(
                        tenantId, userId, resolvedLane, resolvedState,
                        resolvedFolder, sharedOnly, resolvedSearch),
                resolvedPage,
                resolvedPageSize);
    }

    @Transactional(readOnly = true)
    public MailDtos.ThreadDetail thread(Long tenantId, Long userId, UUID threadId) {
        MailDtos.ThreadSummary thread = visibleThread(tenantId, userId, threadId);
        return detail(tenantId, userId, thread);
    }

    @Transactional
    public MailDtos.ThreadDetail applyAction(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.ThreadActionRequest request) {
        MailDtos.ThreadSummary before = visibleThread(tenantId, userId, threadId);
        if (commands.applyAction(
                tenantId, userId, threadId, request.action(), request.version()) == 0) {
            conflict();
        }
        MailDtos.ThreadSummary after = visibleThread(tenantId, userId, threadId);
        recordThreadChange(
                tenantId, userId, threadId, correlationId,
                "mail.thread." + eventSegment(request.action()),
                before, after);
        return detail(tenantId, userId, after);
    }

    @Transactional
    public MailDtos.ThreadDetail snooze(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.SnoozeRequest request) {
        MailDtos.ThreadSummary before = visibleThread(tenantId, userId, threadId);
        OffsetDateTime now = OffsetDateTime.now();
        if (!request.until().isAfter(now.plusMinutes(1))
                || request.until().isAfter(now.plusYears(1))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Snooze time must be between one minute and one year from now.");
        }
        if (commands.snooze(
                tenantId, userId, threadId, request.until(), request.version()) == 0) {
            conflict();
        }
        MailDtos.ThreadSummary after = visibleThread(tenantId, userId, threadId);
        recordThreadChange(
                tenantId, userId, threadId, correlationId,
                "mail.thread.snoozed", before, after);
        return detail(tenantId, userId, after);
    }

    @Transactional
    public MailDtos.ThreadDetail assign(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.AssignRequest request) {
        MailDtos.ThreadSummary before = visibleThread(tenantId, userId, threadId);
        if (before.sharedInboxId() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only shared inbox conversations can be assigned.");
        }
        if (!queries.isActiveSharedInboxMember(
                tenantId, before.sharedInboxId(), request.assignedUserId())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The assignee is not an active member of this shared inbox.");
        }
        if (commands.assign(
                tenantId, userId, threadId, request.assignedUserId(),
                request.assignedName().trim(), request.version()) == 0) {
            conflict();
        }
        MailDtos.ThreadSummary after = visibleThread(tenantId, userId, threadId);
        recordThreadChange(
                tenantId, userId, threadId, correlationId,
                "mail.thread.assigned", before, after);
        return detail(tenantId, userId, after);
    }

    @Transactional
    public MailDtos.ThreadDetail comment(
            Long tenantId,
            Long userId,
            String authorName,
            UUID threadId,
            String correlationId,
            MailDtos.CommentRequest request) {
        MailDtos.ThreadSummary thread = visibleThread(tenantId, userId, threadId);
        UUID commentId = commands.insertComment(
                tenantId, userId, displayName(authorName, userId), threadId,
                request.body(), request.mentionedUserIds().stream().distinct().toList());
        commands.audit(
                tenantId, userId, "mail.comment.created", "MAIL_THREAD",
                threadId.toString(), correlationId, Map.of(),
                Map.of("commentId", commentId, "mentionCount", request.mentionedUserIds().size()));
        commands.domainEvent(
                tenantId, "MAIL_THREAD", threadId, "mail.comment.created",
                Map.of(
                        "threadId", threadId,
                        "commentId", commentId,
                        "sharedInboxId", thread.sharedInboxId() == null
                                ? "" : thread.sharedInboxId()),
                correlationId);
        return detail(tenantId, userId, thread);
    }

    @Transactional
    public MailDtos.ThreadDetail reply(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.ReplyRequest request) {
        MailDtos.ThreadSummary before = visibleThread(tenantId, userId, threadId);
        UUID deliveryThreadId = commands.deliveryThread(
                tenantId, userId, request.idempotencyKey());
        if (deliveryThreadId != null) {
            if (!deliveryThreadId.equals(threadId)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The idempotency key belongs to another mail thread.");
            }
            return detail(tenantId, userId, before);
        }
        boolean inserted = commands.insertReply(
                tenantId, userId, threadId, request.body(), request.idempotencyKey());
        if (!inserted) return detail(tenantId, userId, before);
        commands.enqueueDelivery(
                tenantId, userId, threadId, request.idempotencyKey(), correlationId);
        MailDtos.ThreadSummary after = visibleThread(tenantId, userId, threadId);
        commands.audit(
                tenantId, userId, "mail.reply.sent", "MAIL_THREAD",
                threadId.toString(), correlationId,
                state(before), Map.of(
                        "messageCount", after.messageCount(),
                        "idempotencyKey", request.idempotencyKey()));
        commands.domainEvent(
                tenantId, "MAIL_THREAD", threadId, "mail.reply.sent",
                Map.of(
                        "threadId", threadId,
                        "accountId", after.accountId(),
                        "classification", after.classification().name()),
                correlationId);
        return detail(tenantId, userId, after);
    }

    @Transactional
    public MailDtos.ThreadDetail retryDelivery(
            Long tenantId,
            Long userId,
            UUID threadId,
            UUID messageId,
            String correlationId) {
        MailDtos.ThreadSummary thread = visibleThread(tenantId, userId, threadId);
        boolean messageVisible = queries.messages(tenantId, threadId).stream()
                .anyMatch(message -> message.messageId().equals(messageId)
                        && message.deliveryState() == DeliveryState.FAILED);
        if (!messageVisible) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Only a failed visible message can be retried.");
        }
        if (!deliveryCompletion.retry(
                tenantId, userId, threadId, messageId, correlationId)) {
            conflict();
        }
        return detail(tenantId, userId, thread);
    }

    @Transactional
    public MailDtos.ThreadDetail compose(
            Long tenantId,
            Long userId,
            String correlationId,
            MailDtos.ComposeRequest request) {
        requireMailbox(queries.accounts(tenantId, userId));
        MailCommandRepository.ComposeResult result = commands.compose(tenantId, userId, request);
        if (result == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No active default mail account is available.");
        }
        UUID threadId = result.threadId();
        if (!result.created()) {
            MailDtos.ThreadSummary existing = visibleThread(tenantId, userId, threadId);
            return detail(tenantId, userId, existing);
        }
        if (request.deliveryMode() == DeliveryMode.SEND) {
            commands.enqueueDelivery(
                    tenantId, userId, threadId, request.idempotencyKey(), correlationId);
        }
        MailDtos.ThreadSummary thread = visibleThread(tenantId, userId, threadId);
        String event = request.deliveryMode() == DeliveryMode.DRAFT
                ? "mail.draft.saved" : "mail.message.queued";
        commands.audit(
                tenantId, userId, event, "MAIL_THREAD", threadId.toString(),
                correlationId, Map.of(), Map.of(
                        "deliveryMode", request.deliveryMode().name(),
                        "idempotencyKey", request.idempotencyKey()));
        commands.domainEvent(
                tenantId, "MAIL_THREAD", threadId, event,
                Map.of(
                        "threadId", threadId,
                        "accountId", thread.accountId(),
                        "deliveryMode", request.deliveryMode().name(),
                        "classification", thread.classification().name()),
                correlationId);
        return detail(tenantId, userId, thread);
    }

    @Transactional
    public MailDtos.ThreadDetail updateDraft(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.DraftUpdateRequest request) {
        MailDtos.ThreadSummary before = visibleThread(tenantId, userId, threadId);
        if (request.deliveryMode() == DeliveryMode.SEND) {
            UUID deliveryThreadId = commands.deliveryThread(
                    tenantId, userId, request.idempotencyKey());
            if (deliveryThreadId != null) {
                if (!deliveryThreadId.equals(threadId)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "The idempotency key belongs to another mail thread.");
                }
                return detail(tenantId, userId, before);
            }
        }
        if (!"DRAFTS".equals(before.folderType())
                || before.workflowState() != WorkflowState.DRAFT
                || before.sharedInboxId() != null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a personal draft can be edited.");
        }
        if (commands.updateDraft(tenantId, userId, threadId, request) == 0) {
            UUID deliveryThreadId = request.deliveryMode() == DeliveryMode.SEND
                    ? commands.deliveryThread(tenantId, userId, request.idempotencyKey())
                    : null;
            if (threadId.equals(deliveryThreadId)) {
                MailDtos.ThreadSummary existing = visibleThread(tenantId, userId, threadId);
                return detail(tenantId, userId, existing);
            }
            conflict();
        }
        if (request.deliveryMode() == DeliveryMode.SEND) {
            commands.enqueueDelivery(
                    tenantId, userId, threadId, request.idempotencyKey(), correlationId);
        }
        MailDtos.ThreadSummary after = visibleThread(tenantId, userId, threadId);
        String event = request.deliveryMode() == DeliveryMode.DRAFT
                ? "mail.draft.saved" : "mail.message.queued";
        commands.audit(
                tenantId, userId, event, "MAIL_THREAD", threadId.toString(),
                correlationId, state(before), Map.of(
                        "workflowState", after.workflowState().name(),
                        "folderType", after.folderType(),
                        "version", after.version(),
                        "idempotencyKey", request.idempotencyKey()));
        commands.domainEvent(
                tenantId, "MAIL_THREAD", threadId, event,
                Map.of(
                        "threadId", threadId,
                        "accountId", after.accountId(),
                        "deliveryMode", request.deliveryMode().name(),
                        "classification", after.classification().name(),
                        "idempotencyKey", request.idempotencyKey()),
                correlationId);
        return detail(tenantId, userId, after);
    }

    @Transactional
    public MailDtos.ActionProposal decideProposal(
            Long tenantId,
            Long userId,
            String permissions,
            UUID proposalId,
            String correlationId,
            MailDtos.ProposalDecisionRequest request) {
        MailDtos.ActionProposal before = queries.proposal(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        MailDtos.TenantPolicy policy = queries.policy(tenantId);
        if (request.decision() == ProposalDecision.ACCEPT) {
            MailAiActionCatalog.Policy actionPolicy = MailAiActionCatalog.validate(before);
            if (!policy.aiAssistanceEnabled()) {
                throw new BaseException(ErrorCode.INVALID_STATE, "AI assistance is disabled.");
            }
            if (actionPolicy.crossApplication() && !policy.aiCrossAppActionsEnabled()) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "Cross-application AI actions are disabled.");
            }
            if (!hasAuthority(
                    permissions,
                    actionPolicy.resourceKey(),
                    actionPolicy.permissionCode())) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The proposed action is outside the user's current permission scope.");
            }
        }
        if (commands.decideProposal(
                tenantId, userId, proposalId, request.decision(), request.version()) == 0) {
            conflict();
        }
        MailDtos.ActionProposal after = queries.proposal(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String event = request.decision() == ProposalDecision.ACCEPT
                ? "mail.action.accepted" : "mail.action.dismissed";
        commands.audit(
                tenantId, userId, event, "MAIL_ACTION_PROPOSAL",
                proposalId.toString(), correlationId,
                Map.of("status", before.status().name()),
                Map.of("status", after.status().name(), "type", after.type().name()));
        commands.domainEvent(
                tenantId, "MAIL_ACTION_PROPOSAL", proposalId, event,
                Map.of(
                        "proposalId", proposalId,
                        "threadId", after.threadId(),
                        "proposalType", after.type().name(),
                        "actionContractVersion", after.actionContractVersion(),
                        "targetResourceKey", after.requiredResourceKey(),
                        "targetPermissionCode", after.requiredPermissionCode(),
                        "targetRoute", after.targetRoute() == null ? "" : after.targetRoute(),
                        "requiresHumanConfirmation", true),
                correlationId);
        return after;
    }

    @Transactional(readOnly = true)
    public MailDtos.AdminOverview adminOverview(Long tenantId) {
        MailQueryRepository.AdminCounts counts = queries.adminCounts(tenantId);
        return new MailDtos.AdminOverview(
                counts.personalAccounts(), counts.sharedAccounts(),
                counts.activeConnections(), counts.degradedConnections(),
                counts.openSharedThreads(), counts.pendingAiProposals(),
                counts.queuedDeliveries(), counts.failedDeliveries(),
                queries.policy(tenantId), queries.connections(tenantId),
                queries.sharedInboxes(tenantId), providerCatalog.all(), OffsetDateTime.now());
    }

    @Transactional
    public MailDtos.TenantPolicy updatePolicy(
            Long tenantId,
            Long userId,
            String correlationId,
            MailDtos.TenantPolicyRequest request) {
        MailDtos.TenantPolicy before = queries.policy(tenantId);
        if (commands.updatePolicy(tenantId, userId, request) == 0) conflict();
        MailDtos.TenantPolicy after = queries.policy(tenantId);
        commands.audit(
                tenantId, userId, "mail.policy.updated", "MAIL_TENANT_POLICY",
                tenantId.toString(), correlationId,
                policyState(before), policyState(after));
        return after;
    }

    @Transactional
    public MailDtos.ConnectionSummary updateConnection(
            Long tenantId,
            Long userId,
            UUID connectionId,
            String correlationId,
            MailDtos.ConnectionUpdateRequest request) {
        MailDtos.ConnectionSummary before = queries.connection(tenantId, connectionId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        boolean credentialAvailable = before.credentialConfigured()
                || (request.credentialRef() != null && !request.credentialRef().isBlank());
        if (request.state() == ConnectionState.ACTIVE
                && before.providerType() != ProviderType.DWP_SANDBOX
                && !credentialAvailable) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "An external secret-store reference is required before activation.");
        }
        if (request.state() == ConnectionState.ACTIVE
                && !providerCatalog.isRuntimeAvailable(before.providerType())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The provider contract exists, but its runtime adapter is not deployed.");
        }
        if (commands.updateConnection(tenantId, userId, connectionId, request) == 0) {
            conflict();
        }
        MailDtos.ConnectionSummary after = queries.connection(tenantId, connectionId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, userId, "mail.connection.updated", "MAIL_PROVIDER_CONNECTION",
                connectionId.toString(), correlationId,
                connectionState(before), connectionState(after));
        return after;
    }

    @Transactional
    public MailDtos.SharedInboxSummary updateSharedInbox(
            Long tenantId,
            Long userId,
            UUID sharedInboxId,
            String correlationId,
            MailDtos.SharedInboxUpdateRequest request) {
        MailDtos.SharedInboxSummary before = queries.sharedInbox(tenantId, sharedInboxId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (commands.updateSharedInbox(tenantId, userId, sharedInboxId, request) == 0) {
            conflict();
        }
        MailDtos.SharedInboxSummary after = queries.sharedInbox(tenantId, sharedInboxId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, userId, "mail.shared.inbox.updated", "MAIL_SHARED_INBOX",
                sharedInboxId.toString(), correlationId,
                sharedInboxState(before), sharedInboxState(after));
        return after;
    }

    private MailDtos.ThreadDetail detail(
            Long tenantId, Long userId, MailDtos.ThreadSummary thread) {
        return new MailDtos.ThreadDetail(
                thread,
                queries.messages(tenantId, thread.threadId()),
                queries.comments(tenantId, thread.threadId()),
                queries.proposals(tenantId, userId, thread.threadId(), 20),
                thread.sharedInboxId() == null
                        ? List.of()
                        : queries.sharedInboxMembers(tenantId, thread.sharedInboxId()));
    }

    private Map<String, Object> sharedInboxState(MailDtos.SharedInboxSummary value) {
        return Map.of(
                "displayName", value.displayName(),
                "purpose", value.purpose() == null ? "" : value.purpose(),
                "serviceTargetMinutes", value.serviceTargetMinutes(),
                "lifecycleState", value.lifecycleState(),
                "version", value.version());
    }

    private MailDtos.ThreadSummary visibleThread(
            Long tenantId, Long userId, UUID threadId) {
        return queries.thread(tenantId, userId, threadId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireMailbox(List<MailDtos.AccountSummary> accounts) {
        if (accounts.isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No governed mail account is assigned to this user.");
        }
    }

    private <T extends Enum<T>> String enumValue(String value, Class<T> type) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Unsupported mail filter.");
        }
    }

    private String eventSegment(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private String normalizeSearch(String search) {
        if (search == null) return "";
        String normalized = search.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 200) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Search is too long.");
        }
        return normalized;
    }

    private String folderValue(String folder) {
        if (folder == null || folder.isBlank()) return "";
        String normalized = folder.trim().toUpperCase(Locale.ROOT);
        if (!FOLDERS.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Unsupported mail folder.");
        }
        return normalized;
    }

    private String displayName(String value, Long userId) {
        if (value == null || value.isBlank()) return "User " + userId;
        String normalized = value.trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private boolean hasAuthority(
            String permissions, String resourceKey, String permissionCode) {
        if (permissions == null || resourceKey == null || permissionCode == null) return false;
        String expected = (resourceKey + ":" + permissionCode).toUpperCase(Locale.ROOT);
        return Arrays.stream(permissions.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    private void recordThreadChange(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            String eventType,
            MailDtos.ThreadSummary before,
            MailDtos.ThreadSummary after) {
        commands.audit(
                tenantId, userId, eventType, "MAIL_THREAD", threadId.toString(),
                correlationId, state(before), state(after));
        commands.domainEvent(
                tenantId, "MAIL_THREAD", threadId, eventType,
                Map.of(
                        "threadId", threadId,
                        "workflowState", after.workflowState().name(),
                        "triageLane", after.triageLane().name(),
                        "classification", after.classification().name()),
                correlationId);
    }

    private Map<String, Object> state(MailDtos.ThreadSummary value) {
        return Map.of(
                "unread", value.unread(),
                "starred", value.starred(),
                "workflowState", value.workflowState().name(),
                "triageLane", value.triageLane().name(),
                "version", value.version());
    }

    private Map<String, Object> policyState(MailDtos.TenantPolicy value) {
        return Map.of(
                "externalSenderBanner", value.externalSenderBanner(),
                "blockRemoteImages", value.blockRemoteImages(),
                "allowSharedInboxes", value.allowSharedInboxes(),
                "aiAssistanceEnabled", value.aiAssistanceEnabled(),
                "aiCrossAppActionsEnabled", value.aiCrossAppActionsEnabled(),
                "retentionDays", value.retentionDays(),
                "maximumAttachmentMb", value.maximumAttachmentMb(),
                "version", value.version());
    }

    private Map<String, Object> connectionState(MailDtos.ConnectionSummary value) {
        return Map.of(
                "providerType", value.providerType().name(),
                "state", value.state().name(),
                "credentialConfigured", value.credentialConfigured(),
                "version", value.version());
    }

    private void conflict() {
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The mail resource changed. Refresh and try again.");
    }
}
