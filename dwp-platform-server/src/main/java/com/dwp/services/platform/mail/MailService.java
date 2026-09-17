package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final MailNotificationEvents notificationEvents;
    private final MailWorkspaceRepository workspaceRepository;
    private final MailProposalOutcomePort proposalOutcomes;
    private final MailAdminMutationReceipts adminReceipts;
    private final MailSendCommandFingerprint sendFingerprints;

    @Autowired
    public MailService(
            MailQueryRepository queries,
            MailCommandRepository commands,
            MailProviderCatalog providerCatalog,
            MailDeliveryCompletionService deliveryCompletion,
            MailNotificationEvents notificationEvents,
            MailWorkspaceRepository workspaceRepository,
            MailProposalOutcomePort proposalOutcomes,
            MailAdminMutationReceipts adminReceipts) {
        this.queries = queries;
        this.commands = commands;
        this.providerCatalog = providerCatalog;
        this.deliveryCompletion = deliveryCompletion;
        this.notificationEvents = notificationEvents;
        this.workspaceRepository = workspaceRepository;
        this.proposalOutcomes = proposalOutcomes;
        this.adminReceipts = adminReceipts;
        this.sendFingerprints = new MailSendCommandFingerprint();
    }

    MailService(
            MailQueryRepository queries,
            MailCommandRepository commands,
            MailProviderCatalog providerCatalog,
            MailDeliveryCompletionService deliveryCompletion,
            MailNotificationEvents notificationEvents,
            MailWorkspaceRepository workspaceRepository) {
        this(queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                workspaceRepository, null, null);
    }

    MailService(
            MailQueryRepository queries,
            MailCommandRepository commands,
            MailProviderCatalog providerCatalog,
            MailDeliveryCompletionService deliveryCompletion,
            MailNotificationEvents notificationEvents) {
        this(queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                null, null, null);
    }

    @Transactional(readOnly = true)
    public MailDtos.HomeResponse home(Long tenantId, Long userId) {
        return home(tenantId, userId, null);
    }

    @Transactional(readOnly = true)
    public MailDtos.HomeResponse home(Long tenantId, Long userId, UUID accountId) {
        List<MailDtos.AccountSummary> accounts = queries.accounts(tenantId, userId);
        requireMailbox(accounts);
        if (accountId != null && accounts.stream().noneMatch(account -> account.accountId().equals(accountId))) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The selected mail account is not available.");
        }
        return new MailDtos.HomeResponse(
                accounts,
                queries.metrics(tenantId, userId, accountId),
                queries.threadsAdvanced(
                        tenantId, userId, "", "", "INBOX", null, false, "",
                        accountId, "", null, "", "", "", null, null,
                        null, null, null, 0, 6),
                queries.proposalsFiltered(tenantId, userId, accountId, "PROPOSED", "", 4),
                queries.sharedInboxPulse(tenantId, userId, accountId),
                OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public MailDtos.ThreadPage threads(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
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
                        resolvedFolder, folderId, sharedOnly, resolvedSearch,
                        resolvedPage, resolvedPageSize),
                queries.threadCount(
                        tenantId, userId, resolvedLane, resolvedState,
                        resolvedFolder, folderId, sharedOnly, resolvedSearch),
                resolvedPage,
                resolvedPageSize);
    }

    @Transactional(readOnly = true)
    public MailDtos.ThreadPage threadsAdvanced(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
            boolean sharedOnly,
            String search,
            UUID accountId,
            String scope,
            UUID sharedInboxId,
            String assignment,
            String sender,
            String recipient,
            java.time.LocalDate dateFrom,
            java.time.LocalDate dateTo,
            Boolean unread,
            Boolean needsReply,
            Boolean hasAttachment,
            int page,
            int pageSize) {
        List<MailDtos.AccountSummary> accounts = queries.accounts(tenantId, userId);
        requireMailbox(accounts);
        if (accountId != null && accounts.stream().noneMatch(account -> account.accountId().equals(accountId))) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The selected mail account is not available.");
        }
        String resolvedLane = enumValue(lane, TriageLane.class);
        String resolvedState = enumValue(state, WorkflowState.class);
        String resolvedFolder = folderValue(folder);
        String resolvedSearch = normalizeSearch(search);
        String resolvedScope = normalizeChoice(scope, Set.of("ALL", "PERSONAL", "SHARED"));
        if ("ALL".equals(resolvedScope)) resolvedScope = "";
        String resolvedAssignment = normalizeChoice(
                assignment, Set.of("ALL", "MINE", "UNASSIGNED"));
        if ("ALL".equals(resolvedAssignment)) resolvedAssignment = "";
        String resolvedSender = normalizeSearch(sender);
        String resolvedRecipient = normalizeSearch(recipient);
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The mail date range is invalid.");
        }
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        return new MailDtos.ThreadPage(
                queries.threadsAdvanced(
                        tenantId, userId, resolvedLane, resolvedState, resolvedFolder, folderId,
                        sharedOnly, resolvedSearch, accountId, resolvedScope, sharedInboxId,
                        resolvedAssignment, resolvedSender, resolvedRecipient, dateFrom, dateTo,
                        unread, needsReply, hasAttachment, resolvedPage, resolvedPageSize),
                queries.threadCountAdvanced(
                        tenantId, userId, resolvedLane, resolvedState, resolvedFolder, folderId,
                        sharedOnly, resolvedSearch, accountId, resolvedScope, sharedInboxId,
                        resolvedAssignment, resolvedSender, resolvedRecipient, dateFrom, dateTo,
                        unread, needsReply, hasAttachment),
                resolvedPage, resolvedPageSize);
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
        requireSharedPermission(
                tenantId, userId, before, MailQueryRepository.SharedInboxPermission.MANAGE);
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
        requireSharedPermission(
                tenantId, userId, before, MailQueryRepository.SharedInboxPermission.MANAGE);
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
        requireSharedPermission(
                tenantId, userId, before, MailQueryRepository.SharedInboxPermission.ASSIGN);
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
        notificationEvents.sharedInboxAssigned(
                tenantId, userId, threadId, request.assignedUserId(),
                after.version(), correlationId);
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
        requireSharedPermission(
                tenantId, userId, thread, MailQueryRepository.SharedInboxPermission.MANAGE);
        UUID commentId = commands.insertComment(
                tenantId, userId, displayName(authorName, userId), threadId,
                request.body(), request.mentionedUserIds().stream().distinct().toList());
        if (commentId == null) {
            conflict();
        }
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
        return reply(tenantId, userId, threadId, correlationId, request, null);
    }

    @Transactional
    public MailDtos.ThreadDetail reply(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.ReplyRequest request,
            MailProposalHandoffBinding proposalBinding) {
        MailDtos.ThreadSummary before = visibleThread(tenantId, userId, threadId);
        requireSharedPermission(
                tenantId, userId, before, MailQueryRepository.SharedInboxPermission.SEND);
        validateProposalBinding(tenantId, userId, proposalBinding);
        List<MailWorkspaceDtos.Recipient> recipients = replyRecipients(request);
        String requestFingerprint = sendFingerprints.reply(userId, threadId, request);
        MailCommandRepository.DeliveryCommand deliveryCommand = existingSendCommand(
                tenantId, userId, threadId, request.idempotencyKey());
        if (deliveryCommand != null) {
            requireMatchingSendCommand(deliveryCommand, threadId, requestFingerprint);
            completeReplyProposal(
                    tenantId, userId, threadId, correlationId, proposalBinding);
            return detail(tenantId, userId, before);
        }
        validateReplyMandatoryContent(tenantId, userId, threadId, request.body());
        boolean inserted = recipients == null
                ? commands.insertReply(
                        tenantId, userId, threadId, request.body(), request.idempotencyKey())
                : commands.insertReply(
                        tenantId, userId, threadId, request.body(), request.idempotencyKey(),
                        recipients);
        if (!inserted) {
            requireMatchingSendCommand(existingSendCommand(
                    tenantId, userId, threadId, request.idempotencyKey()),
                    threadId, requestFingerprint);
            completeReplyProposal(
                    tenantId, userId, threadId, correlationId, proposalBinding);
            return detail(tenantId, userId, before);
        }
        requireMatchingSendCommand(commands.enqueueDelivery(
                tenantId, userId, threadId, request.idempotencyKey(), correlationId,
                requestFingerprint), threadId, requestFingerprint);
        MailDtos.ThreadSummary after = visibleThread(tenantId, userId, threadId);
        commands.audit(
                tenantId, userId, "mail.reply.sent", "MAIL_THREAD",
                threadId.toString(), correlationId,
                state(before), Map.of(
                        "messageCount", after.messageCount(),
                        "replyMode", replyMode(request),
                        "recipientCount", recipients == null ? 0 : recipients.size(),
                        "idempotencyKey", request.idempotencyKey()));
        commands.domainEvent(
                tenantId, "MAIL_THREAD", threadId, "mail.reply.sent",
                Map.of(
                        "threadId", threadId,
                        "accountId", after.accountId(),
                        "classification", after.classification().name()),
                correlationId);
        completeReplyProposal(
                tenantId, userId, threadId, correlationId, proposalBinding);
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
        requireSharedPermission(
                tenantId, userId, thread, MailQueryRepository.SharedInboxPermission.SEND);
        boolean messageVisible = queries.messages(tenantId, userId, threadId).stream()
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
        String requestFingerprint = sendFingerprints.compose(userId, request);
        MailCommandRepository.DeliveryCommand delivered = commands.deliveryCommand(
                tenantId, userId, request.idempotencyKey());
        if (delivered != null) {
            requireMatchingSendCommand(
                    delivered, delivered.threadId(), requestFingerprint);
            return detail(
                    tenantId, userId,
                    visibleThread(tenantId, userId, delivered.threadId()));
        }
        MailCommandRepository.ComposeResult result = commands.compose(
                tenantId, userId, request, requestFingerprint);
        if (result == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No active default mail account is available.");
        }
        requireMatchingComposeCommand(result, requestFingerprint);
        UUID threadId = result.threadId();
        if (!result.created()) {
            MailDtos.ThreadSummary existing = visibleThread(tenantId, userId, threadId);
            return detail(tenantId, userId, existing);
        }
        if (request.deliveryMode() == DeliveryMode.SEND) {
            requireMatchingSendCommand(commands.enqueueDelivery(
                    tenantId, userId, threadId, request.idempotencyKey(), correlationId,
                    requestFingerprint), threadId, requestFingerprint);
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
        String requestFingerprint = request.deliveryMode() == DeliveryMode.SEND
                ? sendFingerprints.draftSend(userId, threadId, request)
                : null;
        if (request.deliveryMode() == DeliveryMode.SEND) {
            MailCommandRepository.DeliveryCommand deliveryCommand = existingSendCommand(
                    tenantId, userId, threadId, request.idempotencyKey());
            if (deliveryCommand != null) {
                requireMatchingSendCommand(deliveryCommand, threadId, requestFingerprint);
                return detail(tenantId, userId, before);
            }
        }
        if (!"DRAFTS".equals(before.folderType())
                || before.workflowState() != WorkflowState.DRAFT
                || before.sharedInboxId() != null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a personal draft can be edited.");
        }
        if (commands.updateDraft(tenantId, userId, threadId, request) == 0) {
            MailCommandRepository.DeliveryCommand deliveryCommand = request.deliveryMode()
                    == DeliveryMode.SEND
                    ? existingSendCommand(
                            tenantId, userId, threadId, request.idempotencyKey())
                    : null;
            if (deliveryCommand != null) {
                requireMatchingSendCommand(deliveryCommand, threadId, requestFingerprint);
                MailDtos.ThreadSummary existing = visibleThread(tenantId, userId, threadId);
                return detail(tenantId, userId, existing);
            }
            conflict();
        }
        if (request.deliveryMode() == DeliveryMode.SEND) {
            requireMatchingSendCommand(commands.enqueueDelivery(
                    tenantId, userId, threadId, request.idempotencyKey(), correlationId,
                    requestFingerprint), threadId, requestFingerprint);
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
        requireSharedPermission(
                tenantId, userId, visibleThread(tenantId, userId, before.threadId()),
                MailQueryRepository.SharedInboxPermission.MANAGE);
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
        if (request.decision() == ProposalDecision.ACCEPT
                && after.type() == ProposalType.ESCALATE_NOTIFICATION) {
            String resultRef = "unsupported-owner:notification";
            MailQueryRepository.OwnerProposalHandoffRow handoff = queries
                    .ownerProposalHandoff(tenantId, proposalId, true)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            if (commands.updateProposalOutcomeFromOwner(
                    tenantId, userId, proposalId, handoff.commandId(),
                    ProposalType.ESCALATE_NOTIFICATION, "UNKNOWN", resultRef,
                    handoff.version()) != 1) {
                conflict();
            }
            commands.audit(
                    tenantId, userId, "mail.action.owner-outcome",
                    "MAIL_ACTION_PROPOSAL", proposalId.toString(), correlationId,
                    Map.of("status", "ACCEPTED", "version", handoff.version()),
                    Map.of("status", "UNKNOWN", "resultRef", resultRef,
                            "version", handoff.version() + 1));
            commands.domainEvent(
                    tenantId, "MAIL_ACTION_PROPOSAL", proposalId,
                    "mail.action.owner-outcome", Map.of(
                            "proposalId", proposalId,
                            "commandId", handoff.commandId(),
                            "status", "UNKNOWN",
                            "resultRef", resultRef,
                            "version", handoff.version() + 1), correlationId);
        }
        return after;
    }

    @Transactional(readOnly = true)
    public List<MailDtos.ActionProposal> proposals(
            Long tenantId,
            Long userId,
            String status,
            String type) {
        requireMailbox(queries.accounts(tenantId, userId));
        String resolvedStatus = enumValue(status, ProposalStatus.class);
        String resolvedType = enumValue(type, ProposalType.class);
        return queries.proposalsFiltered(
                tenantId, userId, null, resolvedStatus, resolvedType, 200);
    }

    @Transactional
    public MailDtos.ActionProposal updateProposal(
            Long tenantId,
            Long userId,
            UUID proposalId,
            String correlationId,
            MailDtos.ProposalUpdateRequest request) {
        MailDtos.ActionProposal before = queries.proposal(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireSharedPermission(
                tenantId, userId, visibleThread(tenantId, userId, before.threadId()),
                MailQueryRepository.SharedInboxPermission.MANAGE);
        if (before.status() != ProposalStatus.PROPOSED
                || (before.expiresAt() != null && !before.expiresAt().isAfter(OffsetDateTime.now()))) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an active proposed action can be edited.");
        }
        validateProposalPayload(request.proposedPayload());
        if (queries.updateProposalPayload(
                tenantId, userId, proposalId, request.proposedPayload(), request.version()) != 1) {
            conflict();
        }
        MailDtos.ActionProposal after = queries.proposal(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, userId, "mail.proposal.updated", "MAIL_ACTION_PROPOSAL",
                proposalId.toString(), correlationId,
                Map.of("version", before.version(), "payloadKeys", before.proposedPayload().keySet()),
                Map.of("version", after.version(), "payloadKeys", after.proposedPayload().keySet()));
        commands.domainEvent(
                tenantId, "MAIL_ACTION_PROPOSAL", proposalId, "mail.proposal.updated",
                Map.of("proposalId", proposalId, "threadId", before.threadId(),
                        "actorUserId", userId, "version", after.version()),
                correlationId);
        return after;
    }

    @Transactional(readOnly = true)
    public MailDtos.ProposalHandoff proposalHandoff(
            Long tenantId, Long userId, UUID proposalId) {
        requireMailbox(queries.accounts(tenantId, userId));
        return handoff(queries.proposalHandoff(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND)));
    }

    @Transactional
    public MailDtos.ProposalHandoff recordProposalOutcome(
            Long tenantId,
            Long userId,
            UUID proposalId,
            String correlationId,
            MailDtos.ProposalOutcomeRequest request) {
        requireMailbox(queries.accounts(tenantId, userId));
        if (request.status() == MailDtos.ProposalHandoffStatus.ACCEPTED) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "An owner outcome must resolve, fail, cancel, or mark the result unknown.");
        }
        MailQueryRepository.ProposalHandoffRow before = queries
                .proposalHandoff(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!before.commandId().equals(request.commandId())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The proposal command does not match.");
        }
        String normalizedResultRef = request.resultRef().trim();
        if (before.ownerState().equals(request.status().name())
                && normalizedResultRef.equals(before.resultRef())) {
            return handoff(before);
        }
        if (!("ACCEPTED".equals(before.ownerState()) || "UNKNOWN".equals(before.ownerState()))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The proposal outcome is final.");
        }
        if (commands.updateProposalOutcome(
                tenantId, userId, proposalId, request.commandId(),
                request.status().name(), normalizedResultRef, request.version()) != 1) {
            conflict();
        }
        MailQueryRepository.ProposalHandoffRow after = queries
                .proposalHandoff(tenantId, userId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, userId, "mail.action.owner-outcome", "MAIL_ACTION_PROPOSAL",
                proposalId.toString(), correlationId,
                Map.of("status", before.ownerState(), "version", before.version()),
                Map.of("status", after.ownerState(), "version", after.version(),
                        "commandId", after.commandId(), "resultRef", normalizedResultRef));
        commands.domainEvent(
                tenantId, "MAIL_ACTION_PROPOSAL", proposalId,
                "mail.action.owner-outcome", Map.of(
                        "proposalId", proposalId,
                        "commandId", after.commandId(),
                        "status", after.ownerState(),
                        "resultRef", normalizedResultRef,
                        "version", after.version()), correlationId);
        return handoff(after);
    }

    private MailDtos.ProposalHandoff handoff(MailQueryRepository.ProposalHandoffRow row) {
        return new MailDtos.ProposalHandoff(
                row.proposalId(), row.commandId(), row.ownerRoute(),
                "/mail/actions?proposalId=" + row.proposalId(),
                "mail-proposal-" + row.proposalId(),
                MailDtos.ProposalHandoffStatus.valueOf(row.ownerState()),
                row.resultRef(), row.updatedAt(), row.version());
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
            UUID idempotencyKey,
            MailDtos.TenantPolicyRequest request) {
        String fingerprint = adminReceipts().fingerprint(
                "POLICY_UPDATE", tenantId, request);
        MailAdminMutationReceipts.Receipt replay = adminReceipts().claimOrReplay(
                tenantId, userId, "POLICY_UPDATE", idempotencyKey,
                fingerprint, correlationId);
        if (replay != null) {
            requireAdminReplay(replay, "MAIL_TENANT_POLICY",
                    MailAdminMutationReceipts.tenantPolicyId(tenantId));
            return queries.policy(tenantId);
        }
        MailDtos.TenantPolicy before = queries.policy(tenantId);
        if (commands.updatePolicy(tenantId, userId, request) == 0) conflict();
        MailDtos.TenantPolicy after = queries.policy(tenantId);
        commands.policyHistory(
                tenantId, userId, after.version(), correlationId,
                policyState(before), policyState(after));
        commands.audit(
                tenantId, userId, "mail.policy.updated", "MAIL_TENANT_POLICY",
                tenantId.toString(), correlationId,
                policyState(before), policyState(after));
        adminReceipts().complete(
                tenantId, userId, idempotencyKey, "MAIL_TENANT_POLICY",
                MailAdminMutationReceipts.tenantPolicyId(tenantId));
        return after;
    }

    @Transactional
    public MailDtos.ConnectionSummary updateConnection(
            Long tenantId,
            Long userId,
            UUID connectionId,
            String correlationId,
            UUID idempotencyKey,
            MailDtos.ConnectionUpdateRequest request) {
        MailDtos.ConnectionSummary before = queries.connection(tenantId, connectionId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String fingerprint = adminReceipts().fingerprint(
                "CONNECTION_UPDATE", connectionId, request);
        MailAdminMutationReceipts.Receipt replay = adminReceipts().claimOrReplay(
                tenantId, userId, "CONNECTION_UPDATE", idempotencyKey,
                fingerprint, correlationId);
        if (replay != null) {
            requireAdminReplay(replay, "MAIL_PROVIDER_CONNECTION", connectionId);
            return queries.connection(tenantId, connectionId)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        }
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
        adminReceipts().complete(
                tenantId, userId, idempotencyKey,
                "MAIL_PROVIDER_CONNECTION", connectionId);
        return after;
    }

    @Transactional
    public MailDtos.SharedInboxSummary updateSharedInbox(
            Long tenantId,
            Long userId,
            UUID sharedInboxId,
            String correlationId,
            UUID idempotencyKey,
            MailDtos.SharedInboxUpdateRequest request) {
        MailDtos.SharedInboxSummary before = queries.sharedInbox(tenantId, sharedInboxId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String fingerprint = adminReceipts().fingerprint(
                "SHARED_INBOX_UPDATE", sharedInboxId, request);
        MailAdminMutationReceipts.Receipt replay = adminReceipts().claimOrReplay(
                tenantId, userId, "SHARED_INBOX_UPDATE", idempotencyKey,
                fingerprint, correlationId);
        if (replay != null) {
            requireAdminReplay(replay, "MAIL_SHARED_INBOX", sharedInboxId);
            return queries.sharedInbox(tenantId, sharedInboxId)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        }
        if (commands.updateSharedInbox(tenantId, userId, sharedInboxId, request) == 0) {
            conflict();
        }
        MailDtos.SharedInboxSummary after = queries.sharedInbox(tenantId, sharedInboxId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, userId, "mail.shared.inbox.updated", "MAIL_SHARED_INBOX",
                sharedInboxId.toString(), correlationId,
                sharedInboxState(before), sharedInboxState(after));
        adminReceipts().complete(
                tenantId, userId, idempotencyKey,
                "MAIL_SHARED_INBOX", sharedInboxId);
        return after;
    }

    private MailDtos.ThreadDetail detail(
            Long tenantId, Long userId, MailDtos.ThreadSummary thread) {
        return new MailDtos.ThreadDetail(
                thread,
                queries.messages(tenantId, userId, thread.threadId()),
                queries.comments(tenantId, userId, thread.threadId()),
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

    private void validateProposalPayload(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("requiresConfirmation"))
                || payload.size() > 100
                || !validProposalValue(payload, 0)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Proposal details must be bounded JSON and require human confirmation.");
        }
    }

    private List<MailWorkspaceDtos.Recipient> replyRecipients(MailDtos.ReplyRequest request) {
        if (request.recipients() == null || request.recipients().isEmpty()) {
            if ("REPLY_ALL".equals(replyMode(request))) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Reply all requires an explicit reviewed recipient list.");
            }
            return null;
        }
        LinkedHashMap<String, MailWorkspaceDtos.Recipient> unique = new LinkedHashMap<>();
        for (MailWorkspaceDtos.Recipient recipient : request.recipients()) {
            if (recipient.type() == MailWorkspaceDtos.RecipientType.BCC) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Reply recipients cannot include hidden BCC recipients.");
            }
            String email = recipient.email().trim().toLowerCase(Locale.ROOT);
            String name = recipient.name() == null || recipient.name().isBlank()
                    ? null : recipient.name().trim();
            unique.putIfAbsent(
                    email,
                    new MailWorkspaceDtos.Recipient(recipient.type(), name, email));
        }
        if (unique.values().stream()
                .noneMatch(item -> item.type() == MailWorkspaceDtos.RecipientType.TO)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Reply recipients require at least one To address.");
        }
        return List.copyOf(unique.values());
    }

    private String replyMode(MailDtos.ReplyRequest request) {
        return request.mode() == null || request.mode().isBlank()
                ? "REPLY" : request.mode().trim().toUpperCase(Locale.ROOT);
    }

    private boolean validProposalValue(Object value, int depth) {
        if (depth > 6) return false;
        if (value == null || value instanceof Boolean) return true;
        if (value instanceof String text) return text.length() <= 10_000;
        if (value instanceof Number number) {
            if (number instanceof Double item) return Double.isFinite(item);
            if (number instanceof Float item) return Float.isFinite(item);
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() <= 100
                    && map.entrySet().stream().allMatch(entry ->
                            entry.getKey() instanceof String key
                                    && key.length() <= 160
                                    && validProposalValue(entry.getValue(), depth + 1));
        }
        if (value instanceof Collection<?> collection) {
            return collection.size() <= 100
                    && collection.stream().allMatch(item -> validProposalValue(item, depth + 1));
        }
        return false;
    }

    private void requireSharedPermission(
            Long tenantId, Long userId, MailDtos.ThreadSummary thread,
            MailQueryRepository.SharedInboxPermission permission) {
        if (thread.sharedInboxId() == null) return;
        if (!queries.hasSharedInboxPermission(
                tenantId, thread.sharedInboxId(), userId, permission)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The shared inbox grant does not permit this action.");
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

    private String normalizeChoice(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Unsupported mail filter.");
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

    private void requireMatchingSendCommand(
            MailCommandRepository.DeliveryCommand command,
            UUID threadId,
            String requestFingerprint) {
        if (command == null
                || !threadId.equals(command.threadId())
                || !requestFingerprint.equals(command.requestFingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different mail send command.");
        }
    }

    private void requireMatchingComposeCommand(
            MailCommandRepository.ComposeResult result,
            String requestFingerprint) {
        if (!requestFingerprint.equals(result.requestFingerprint())) {
            throw sendCommandConflict();
        }
    }

    private MailCommandRepository.DeliveryCommand existingSendCommand(
            Long tenantId,
            Long userId,
            UUID threadId,
            UUID idempotencyKey) {
        MailCommandRepository.DeliveryCommand own = commands.deliveryCommand(
                tenantId, userId, idempotencyKey);
        return own != null
                ? own
                : commands.deliveryCommandForThread(
                        tenantId, userId, threadId, idempotencyKey);
    }

    private BaseException sendCommandConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The idempotency key was already used for a different mail send command.");
    }

    private void validateReplyMandatoryContent(
            Long tenantId, Long userId, UUID threadId, String body) {
        // The package-private constructor is retained only for focused unit tests. Production
        // wiring always supplies the repository through the explicitly autowired constructor.
        if (workspaceRepository == null) return;
        UUID accountId = workspaceRepository.replyAccount(tenantId, userId, threadId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The sending account is no longer available for this reply."));
        MailWorkspaceDtos.Signature signature = workspaceRepository.defaultSignatureForReply(
                tenantId, userId, accountId).orElse(null);
        if (signature == null || signature.mandatoryContent() == null
                || signature.mandatoryContent().isBlank()) {
            return;
        }
        String required = signature.bodyFormat() == MailWorkspaceDtos.BodyFormat.HTML
                ? Jsoup.parseBodyFragment(signature.mandatoryContent()).text()
                : signature.mandatoryContent();
        if (!normalizedText(body).contains(normalizedText(required))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The reply must include the organization signature mandatory content.");
        }
    }

    private void validateProposalBinding(
            long tenantId,
            long actorId,
            MailProposalHandoffBinding binding) {
        if (binding == null) return;
        if (proposalOutcomes == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Mail proposal owner service is unavailable.");
        }
        proposalOutcomes.validate(
                tenantId, actorId, MailProposalOutcomePort.Owner.MAIL, binding);
    }

    private void completeReplyProposal(
            long tenantId,
            long actorId,
            UUID threadId,
            String correlationId,
            MailProposalHandoffBinding binding) {
        if (binding == null) return;
        proposalOutcomes.executed(
                tenantId, actorId, MailProposalOutcomePort.Owner.MAIL, binding,
                "mail-thread:" + threadId, correlationId);
    }

    private MailAdminMutationReceipts adminReceipts() {
        if (adminReceipts == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Mail administrator command custody is unavailable.");
        }
        return adminReceipts;
    }

    private void requireAdminReplay(
            MailAdminMutationReceipts.Receipt receipt,
            String aggregateType,
            UUID aggregateId) {
        if (!aggregateType.equals(receipt.aggregateType())
                || !aggregateId.equals(receipt.aggregateId())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Mail administrator command replay target does not match.");
        }
    }

    private String normalizedText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
