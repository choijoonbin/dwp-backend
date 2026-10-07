package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;

@Service
public class MailService extends MailServiceGovernanceSupport {

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
        super(queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                workspaceRepository, proposalOutcomes, adminReceipts);
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
                        null, null, null, "", 0, 6),
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
        return threadsAdvanced(
                tenantId, userId, lane, state, folder, folderId, sharedOnly, search,
                accountId, scope, sharedInboxId, assignment, sender, recipient,
                dateFrom, dateTo, unread, needsReply, hasAttachment, "", page, pageSize);
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
            String importance,
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
                assignment, Set.of("ALL", "MINE", "UNASSIGNED", "OVERDUE"));
        if ("ALL".equals(resolvedAssignment)) resolvedAssignment = "";
        String resolvedSender = normalizeSearch(sender);
        String resolvedRecipient = normalizeSearch(recipient);
        String resolvedImportance = enumValue(importance, Importance.class);
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
                        unread, needsReply, hasAttachment, resolvedImportance,
                        resolvedPage, resolvedPageSize),
                queries.threadCountAdvanced(
                        tenantId, userId, resolvedLane, resolvedState, resolvedFolder, folderId,
                        sharedOnly, resolvedSearch, accountId, resolvedScope, sharedInboxId,
                        resolvedAssignment, resolvedSender, resolvedRecipient, dateFrom, dateTo,
                        unread, needsReply, hasAttachment, resolvedImportance),
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
        validateNewProposalExecution(tenantId, userId, threadId, proposalBinding);
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

}
