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

class MailServiceSupport {

    static final Set<String> FOLDERS = Set.of(
            "INBOX", "SENT", "DRAFTS", "ARCHIVE", "SPAM", "TRASH", "CUSTOM");

    final MailQueryRepository queries;
    final MailCommandRepository commands;
    final MailProviderCatalog providerCatalog;
    final MailDeliveryCompletionService deliveryCompletion;
    final MailNotificationEvents notificationEvents;
    final MailWorkspaceRepository workspaceRepository;
    final MailProposalOutcomePort proposalOutcomes;
    final MailAdminMutationReceipts adminReceipts;
    final MailSendCommandFingerprint sendFingerprints;

    MailServiceSupport(
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

    MailDtos.ThreadDetail detail(
            Long tenantId, Long userId, MailDtos.ThreadSummary thread) {
        MailDtos.SharedInboxReplyIdentity replyIdentity = thread.sharedInboxId() == null
                ? null
                : queries.sharedInboxReplyIdentity(
                        tenantId, userId, thread.threadId()).orElse(null);
        return new MailDtos.ThreadDetail(
                thread,
                queries.messages(tenantId, userId, thread.threadId()),
                queries.comments(tenantId, userId, thread.threadId()),
                queries.proposals(tenantId, userId, thread.threadId(), 20),
                thread.sharedInboxId() == null
                        ? List.of()
                        : queries.sharedInboxMembers(tenantId, thread.sharedInboxId()),
                sharedInboxActions(
                        tenantId, userId, thread.sharedInboxId(), replyIdentity != null),
                replyIdentity,
                null,
                List.of());
    }

    List<String> sharedInboxActions(
            Long tenantId, Long userId, UUID sharedInboxId, boolean replyIdentityAvailable) {
        if (sharedInboxId == null) return List.of();
        List<String> actions = new ArrayList<>();
        if (queries.hasSharedInboxPermission(
                tenantId, sharedInboxId, userId,
                MailQueryRepository.SharedInboxPermission.ASSIGN)) {
            actions.add("ASSIGN");
        }
        if (queries.hasSharedInboxPermission(
                tenantId, sharedInboxId, userId,
                MailQueryRepository.SharedInboxPermission.MANAGE)) {
            actions.add("COMMENT");
        }
        if (replyIdentityAvailable && queries.hasSharedInboxPermission(
                tenantId, sharedInboxId, userId,
                MailQueryRepository.SharedInboxPermission.SEND)) {
            actions.add("REPLY");
            actions.add("SEND_AS");
        }
        return List.copyOf(actions);
    }

    Map<String, Object> sharedInboxState(MailDtos.SharedInboxSummary value) {
        return Map.of(
                "displayName", value.displayName(),
                "purpose", value.purpose() == null ? "" : value.purpose(),
                "serviceTargetMinutes", value.serviceTargetMinutes(),
                "lifecycleState", value.lifecycleState(),
                "version", value.version());
    }

    MailDtos.ThreadSummary visibleThread(
            Long tenantId, Long userId, UUID threadId) {
        return queries.thread(tenantId, userId, threadId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    void requireMailbox(List<MailDtos.AccountSummary> accounts) {
        if (accounts.isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No governed mail account is assigned to this user.");
        }
    }

    void validateProposalPayload(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("requiresConfirmation"))
                || payload.size() > 100
                || !validProposalValue(payload, 0)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Proposal details must be bounded JSON and require human confirmation.");
        }
    }

    List<MailWorkspaceDtos.Recipient> replyRecipients(MailDtos.ReplyRequest request) {
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

    String replyMode(MailDtos.ReplyRequest request) {
        return request.mode() == null || request.mode().isBlank()
                ? "REPLY" : request.mode().trim().toUpperCase(Locale.ROOT);
    }

    void validateExternalRecipientPolicy(
            String senderEmail,
            List<String> recipientEmails,
            Classification classification,
            Boolean externalRecipientConfirmed) {
        if (classification == null || externalRecipientConfirmed == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Message classification and external-recipient confirmation are required.");
        }
        String senderDomain = emailDomain(senderEmail);
        boolean external = recipientEmails.stream()
                .map(this::emailDomain)
                .anyMatch(domain -> !senderDomain.equals(domain));
        if (external && classification != Classification.PUBLIC
                && !externalRecipientConfirmed) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Confirm the external recipients before sending non-public content.");
        }
    }

    String emailDomain(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf('@');
        if (separator < 1 || separator == normalized.length() - 1) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A recipient email address is invalid.");
        }
        return normalized.substring(separator + 1);
    }

    boolean validProposalValue(Object value, int depth) {
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

    void requireSharedPermission(
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

    <T extends Enum<T>> String enumValue(String value, Class<T> type) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Unsupported mail filter.");
        }
    }

    String eventSegment(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    String normalizeSearch(String search) {
        if (search == null) return "";
        String normalized = search.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 200) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Search is too long.");
        }
        return normalized;
    }

    String normalizeChoice(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Unsupported mail filter.");
        }
        return normalized;
    }

    String folderValue(String folder) {
        if (folder == null || folder.isBlank()) return "";
        String normalized = folder.trim().toUpperCase(Locale.ROOT);
        if (!FOLDERS.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Unsupported mail folder.");
        }
        return normalized;
    }

    String displayName(String value, Long userId) {
        if (value == null || value.isBlank()) return "User " + userId;
        String normalized = value.trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    boolean hasAuthority(
            String permissions, String resourceKey, String permissionCode) {
        if (permissions == null || resourceKey == null || permissionCode == null) return false;
        String expected = (resourceKey + ":" + permissionCode).toUpperCase(Locale.ROOT);
        return Arrays.stream(permissions.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    void recordThreadChange(
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

    Map<String, Object> state(MailDtos.ThreadSummary value) {
        return Map.of(
                "unread", value.unread(),
                "starred", value.starred(),
                "workflowState", value.workflowState().name(),
                "triageLane", value.triageLane().name(),
                "version", value.version());
    }

    Map<String, Object> policyState(MailDtos.TenantPolicy value) {
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

    Map<String, Object> connectionState(MailDtos.ConnectionSummary value) {
        return Map.of(
                "providerType", value.providerType().name(),
                "state", value.state().name(),
                "credentialConfigured", value.credentialConfigured(),
                "version", value.version());
    }

    void conflict() {
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The mail resource changed. Refresh and try again.");
    }

    void requireMatchingSendCommand(
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

    void requireMatchingComposeCommand(
            MailCommandRepository.ComposeResult result,
            String requestFingerprint) {
        if (!requestFingerprint.equals(result.requestFingerprint())) {
            throw sendCommandConflict();
        }
    }

    MailCommandRepository.DeliveryCommand existingSendCommand(
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

    BaseException sendCommandConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The idempotency key was already used for a different mail send command.");
    }

    void validateReplyMandatoryContent(
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

    void validateProposalBinding(
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

    void validateNewProposalExecution(
            long tenantId,
            long actorId,
            UUID threadId,
            MailProposalHandoffBinding binding) {
        if (binding == null) return;
        proposalOutcomes.validateNewExecution(
                tenantId, actorId, MailProposalOutcomePort.Owner.MAIL, binding,
                new MailProposalOutcomePort.OwnerMutation(threadId, Map.of()));
    }

    void completeReplyProposal(
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

    MailAdminMutationReceipts adminReceipts() {
        if (adminReceipts == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Mail administrator command custody is unavailable.");
        }
        return adminReceipts;
    }

    void requireAdminReplay(
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

    String normalizedText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
