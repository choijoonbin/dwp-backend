package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.jsoup.Jsoup;
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

class MailServiceGovernanceSupport extends MailServiceSupport {

    MailServiceGovernanceSupport(
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
        MailCommandRepository.ComposeResult composed = commands.composeCommand(
                tenantId, userId, request.idempotencyKey());
        if (composed != null) {
            requireMatchingComposeCommand(composed, requestFingerprint);
            return detail(
                    tenantId, userId,
                    visibleThread(tenantId, userId, composed.threadId()));
        }
        if (request.deliveryMode() == DeliveryMode.SEND) {
            validateExternalRecipientPolicy(
                    commands.defaultComposeSenderEmail(tenantId, userId)
                            .orElseThrow(() -> new BaseException(
                                    ErrorCode.INVALID_STATE,
                                    "No active default mail account is available.")),
                    List.of(request.toEmail()), request.classification(),
                    request.externalRecipientConfirmed());
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
            String senderEmail = queries.accounts(tenantId, userId).stream()
                    .filter(account -> account.accountId().equals(before.accountId()))
                    .map(MailDtos.AccountSummary::emailAddress)
                    .findFirst()
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.FORBIDDEN,
                            "The draft sending account is no longer available."));
            validateExternalRecipientPolicy(
                    senderEmail, List.of(request.toEmail()), request.classification(),
                    request.externalRecipientConfirmed());
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
            Long tenantId, Long userId, String status, String type) {
        return proposals(
                tenantId, userId, status, type, null, null, null, 0, 100).items();
    }

    @Transactional(readOnly = true)
    public MailDtos.ActionProposalPage proposals(
            Long tenantId,
            Long userId,
            String status,
            String type,
            UUID accountId,
            java.time.LocalDate dateFrom,
            java.time.LocalDate dateTo,
            int page,
            int pageSize) {
        List<MailDtos.AccountSummary> accounts = queries.accounts(tenantId, userId);
        requireMailbox(accounts);
        if (accountId != null && accounts.stream()
                .noneMatch(account -> account.accountId().equals(accountId))) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The selected mail account is not available.");
        }
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The proposal date range is invalid.");
        }
        String resolvedStatus = enumValue(status, ProposalStatus.class);
        String resolvedType = enumValue(type, ProposalType.class);
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        return new MailDtos.ActionProposalPage(
                queries.proposalsFiltered(
                        tenantId, userId, accountId, resolvedStatus, resolvedType,
                        dateFrom, dateTo, resolvedPage, resolvedPageSize),
                queries.proposalCountFiltered(
                        tenantId, userId, accountId, resolvedStatus, resolvedType,
                        dateFrom, dateTo),
                resolvedPage, resolvedPageSize);
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

    MailDtos.ProposalHandoff handoff(MailQueryRepository.ProposalHandoffRow row) {
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

}
