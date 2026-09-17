package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailAddressBookCommandReceiptRepository.CommandType.CONTACT_CREATE;
import static com.dwp.services.platform.mail.MailAddressBookCommandReceiptRepository.CommandType.GROUP_CREATE;
import static com.dwp.services.platform.mail.MailAddressBookCommandReceiptRepository.CommandType.GROUP_MEMBERS_REPLACE;
import static com.dwp.services.platform.mail.MailAddressBookCommandReceiptRepository.CommandType.GROUP_MESSAGE_SEND;

@Service
public class MailAddressBookService {

    private static final int MAX_GROUP_RECIPIENTS = 100;

    private final MailAddressBookRepository addressBook;
    private final MailAddressBookCommandReceiptRepository receipts;
    private final MailAddressBookCommandFingerprint fingerprints;
    private final MailGroupComposeRepository groupCompose;
    private final MailService mail;
    private final MailCommandRepository evidence;
    private final MailWorkspaceRepository workspace;
    private final MailConnectorRegistry connectors;

    @Autowired
    public MailAddressBookService(
            MailAddressBookRepository addressBook,
            MailAddressBookCommandReceiptRepository receipts,
            MailGroupComposeRepository groupCompose,
            MailService mail,
            MailCommandRepository evidence,
            MailWorkspaceRepository workspace,
            MailConnectorRegistry connectors) {
        this.addressBook = addressBook;
        this.receipts = receipts;
        this.fingerprints = new MailAddressBookCommandFingerprint();
        this.groupCompose = groupCompose;
        this.mail = mail;
        this.evidence = evidence;
        this.workspace = workspace;
        this.connectors = connectors;
    }

    MailAddressBookService(
            MailAddressBookRepository addressBook,
            MailAddressBookCommandReceiptRepository receipts,
            MailGroupComposeRepository groupCompose,
            MailService mail,
            MailCommandRepository evidence) {
        this(addressBook, receipts, groupCompose, mail, evidence, null, null);
    }

    @Transactional(readOnly = true)
    public MailAddressBookDtos.AddressBook addressBook(
            Long tenantId,
            Long userId,
            String query,
            int page,
            int pageSize) {
        if (query.length() > 200) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Search is too long.");
        }
        int safePage = Math.max(0, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        return new MailAddressBookDtos.AddressBook(
                addressBook.contacts(tenantId, userId, query, safePage, safePageSize),
                addressBook.groups(tenantId, userId),
                addressBook.summary(tenantId, userId),
                OffsetDateTime.now());
    }

    @Transactional
    public MailAddressBookDtos.Contact createContact(
            Long tenantId,
            Long userId,
            String correlationId,
            MailAddressBookDtos.ContactCreateRequest request) {
        requireSource(request.sourceKind(), request.sourcePersonPublicId());
        String fingerprint = fingerprints.contactCreate(request);
        var receipt = receipts.reserve(
                tenantId, userId, CONTACT_CREATE, request.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) return contact(tenantId, userId, target(receipt));
        requireNewReservation(receipt);
        UUID contactId;
        try {
            contactId = addressBook.createContact(tenantId, userId, request);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An active contact already uses this email address.");
        }
        MailAddressBookDtos.Contact created = contact(tenantId, userId, contactId);
        receipts.complete(
                tenantId, userId, CONTACT_CREATE, request.idempotencyKey(), fingerprint,
                contactId, created.version());
        record(
                tenantId, userId, "mail.contact.created", "MAIL_CONTACT", contactId,
                correlationId, Map.of(), contactState(created));
        return created;
    }

    @Transactional
    public MailAddressBookDtos.Contact updateContact(
            Long tenantId,
            Long userId,
            UUID contactId,
            String correlationId,
            MailAddressBookDtos.ContactUpdateRequest request) {
        MailAddressBookDtos.Contact before = contact(tenantId, userId, contactId);
        try {
            if (addressBook.updateContact(tenantId, userId, contactId, request) != 1) {
                throw conflict("The contact changed. Refresh it before saving again.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An active contact already uses this email address.");
        }
        addressBook.advanceContainingGroupVersions(tenantId, userId, contactId);
        MailAddressBookDtos.Contact after = contact(tenantId, userId, contactId);
        record(
                tenantId, userId, "mail.contact.updated", "MAIL_CONTACT", contactId,
                correlationId, contactState(before), contactState(after));
        return after;
    }

    @Transactional
    public void deleteContact(
            Long tenantId,
            Long userId,
            UUID contactId,
            long version,
            String correlationId) {
        MailAddressBookDtos.Contact before = contact(tenantId, userId, contactId);
        addressBook.advanceContainingGroupVersions(tenantId, userId, contactId);
        if (addressBook.deleteContact(tenantId, userId, contactId, version) != 1) {
            throw conflict("The contact changed. Refresh it before deleting.");
        }
        record(
                tenantId, userId, "mail.contact.deleted", "MAIL_CONTACT", contactId,
                correlationId, contactState(before), Map.of("deleted", true));
    }

    @Transactional
    public MailAddressBookDtos.ContactGroup createGroup(
            Long tenantId,
            Long userId,
            String correlationId,
            MailAddressBookDtos.ContactGroupCreateRequest request) {
        String fingerprint = fingerprints.groupCreate(request);
        var receipt = receipts.reserve(
                tenantId, userId, GROUP_CREATE, request.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) return group(tenantId, userId, target(receipt));
        requireNewReservation(receipt);
        UUID groupId;
        try {
            groupId = addressBook.createGroup(tenantId, userId, request);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An active group already uses this name.");
        }
        MailAddressBookDtos.ContactGroup created = group(tenantId, userId, groupId);
        receipts.complete(
                tenantId, userId, GROUP_CREATE, request.idempotencyKey(), fingerprint,
                groupId, created.version());
        record(
                tenantId, userId, "mail.contact.group.created", "MAIL_CONTACT_GROUP", groupId,
                correlationId, Map.of(), groupState(created));
        return created;
    }

    @Transactional
    public MailAddressBookDtos.ContactGroup updateGroup(
            Long tenantId,
            Long userId,
            UUID groupId,
            String correlationId,
            MailAddressBookDtos.ContactGroupUpdateRequest request) {
        MailAddressBookDtos.ContactGroup before = group(tenantId, userId, groupId);
        try {
            if (addressBook.updateGroup(tenantId, userId, groupId, request) != 1) {
                throw conflict("The group changed. Refresh it before saving again.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An active group already uses this name.");
        }
        MailAddressBookDtos.ContactGroup after = group(tenantId, userId, groupId);
        record(
                tenantId, userId, "mail.contact.group.updated", "MAIL_CONTACT_GROUP", groupId,
                correlationId, groupState(before), groupState(after));
        return after;
    }

    @Transactional
    public MailAddressBookDtos.ContactGroup replaceMembers(
            Long tenantId,
            Long userId,
            UUID groupId,
            String correlationId,
            MailAddressBookDtos.GroupMembersReplaceRequest request) {
        if (request.contactIds().size() != request.contactIds().stream().distinct().count()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Group members must be unique.");
        }
        String fingerprint = fingerprints.membersReplace(groupId, request);
        var receipt = receipts.reserve(
                tenantId, userId, GROUP_MEMBERS_REPLACE,
                request.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) return group(tenantId, userId, target(receipt));
        requireNewReservation(receipt);
        if (!addressBook.lockGroup(tenantId, userId, groupId, request.version())) {
            throw conflict("The group changed. Refresh it before changing members.");
        }
        MailAddressBookDtos.ContactGroup before = group(tenantId, userId, groupId);
        int updated = addressBook.replaceMembers(
                tenantId, userId, groupId, request.contactIds(), request.version());
        if (updated < 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Every group member must be an active contact owned by the current user.");
        }
        if (updated != 1) throw conflict("The group changed while members were being saved.");
        MailAddressBookDtos.ContactGroup after = group(tenantId, userId, groupId);
        receipts.complete(
                tenantId, userId, GROUP_MEMBERS_REPLACE,
                request.idempotencyKey(), fingerprint, groupId, after.version());
        record(
                tenantId, userId, "mail.contact.group.members.replaced",
                "MAIL_CONTACT_GROUP", groupId, correlationId,
                groupState(before), groupState(after));
        return after;
    }

    @Transactional
    public void deleteGroup(
            Long tenantId,
            Long userId,
            UUID groupId,
            long version,
            String correlationId) {
        MailAddressBookDtos.ContactGroup before = group(tenantId, userId, groupId);
        if (addressBook.deleteGroup(tenantId, userId, groupId, version) != 1) {
            throw conflict("The group changed. Refresh it before deleting.");
        }
        record(
                tenantId, userId, "mail.contact.group.deleted", "MAIL_CONTACT_GROUP", groupId,
                correlationId, groupState(before), Map.of("deleted", true));
    }

    @Transactional
    public MailAddressBookDtos.GroupSendResult sendGroupMessage(
            Long tenantId,
            Long userId,
            UUID groupId,
            String correlationId,
            MailAddressBookDtos.GroupMessageRequest request) {
        boolean bcc = request.recipientMode() == MailAddressBookDtos.GroupRecipientMode.BCC;
        UUID requiredAccountId = requireReadyAccount(
                tenantId, userId, request.accountId(), bcc, correlationId);
        MailAddressBookDtos.GroupMessageRequest effective = requiredAccountId == null
                ? request
                : new MailAddressBookDtos.GroupMessageRequest(
                        request.subject(), request.body(), request.classification(),
                        request.recipientMode(), requiredAccountId,
                        request.idempotencyKey(), request.groupVersion());
        String fingerprint = fingerprints.groupMessage(groupId, effective);
        var receipt = receipts.reserve(
                tenantId, userId, GROUP_MESSAGE_SEND,
                effective.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) {
            UUID threadId = target(receipt);
            return new MailAddressBookDtos.GroupSendResult(
                    mail.thread(tenantId, userId, threadId),
                    groupCompose.receipt(tenantId, userId, groupId, threadId)
                            .orElseThrow(() -> conflict(
                                    "The completed group send receipt is unavailable.")));
        }
        requireNewReservation(receipt);
        if (!addressBook.lockGroup(tenantId, userId, groupId, effective.groupVersion())) {
            throw conflict("The group changed. Review its recipients before sending.");
        }
        List<MailAddressBookRepository.Recipient> recipients =
                addressBook.recipients(tenantId, userId, groupId);
        if (recipients.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The group has no active recipients.");
        }
        if (recipients.size() > MAX_GROUP_RECIPIENTS) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The group exceeds the current 100-recipient safety limit.");
        }
        MailGroupComposeRepository.ComposeResult result = requiredAccountId == null
                ? groupCompose.compose(
                        tenantId, userId, groupId, effective, recipients,
                        correlationId, fingerprint)
                : groupCompose.compose(
                        tenantId, userId, groupId, effective, recipients,
                        correlationId, fingerprint, requiredAccountId);
        if (result == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No active default personal mail account is available.");
        }
        MailDtos.ThreadDetail detail = mail.thread(tenantId, userId, result.threadId());
        receipts.complete(
                tenantId, userId, GROUP_MESSAGE_SEND,
                effective.idempotencyKey(), fingerprint, result.threadId(), result.version());
        MailAddressBookDtos.GroupSendReceipt sendReceipt = result.receipt() != null
                ? result.receipt()
                : groupCompose.receipt(tenantId, userId, groupId, result.threadId())
                        .orElseThrow(() -> conflict("The group send receipt is unavailable."));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("groupId", groupId);
        state.put("groupVersion", effective.groupVersion());
        state.put("recipientMode", effective.recipientMode().name());
        if (sendReceipt.accountId() != null) state.put("accountId", sendReceipt.accountId());
        state.put("recipientCount", result.recipientCount());
        state.put("receiptId", sendReceipt.receiptId());
        state.put("recipientSnapshotSha256", result.recipientSnapshotSha256());
        state.put("classification", effective.classification().name());
        state.put("queued", true);
        record(
                tenantId, userId, "mail.contact.group.message.queued", "MAIL_THREAD",
                result.threadId(), correlationId, Map.of(), state);
        return new MailAddressBookDtos.GroupSendResult(detail, sendReceipt);
    }

    @Transactional(readOnly = true)
    public List<MailAddressBookDtos.GroupSendReceipt> groupMessageHistory(
            Long tenantId, Long userId, UUID groupId) {
        group(tenantId, userId, groupId);
        return groupCompose.history(tenantId, userId, groupId, 100);
    }

    private MailAddressBookDtos.Contact contact(Long tenantId, Long userId, UUID contactId) {
        return addressBook.contact(tenantId, userId, contactId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private UUID requireReadyAccount(
            long tenantId,
            long userId,
            UUID requestedAccountId,
            boolean requireBcc,
            String correlationId) {
        if (workspace == null || connectors == null) {
            if (requireBcc || requestedAccountId != null) {
                throw accountUnavailable(requireBcc);
            }
            return null;
        }
        try {
            UUID accountId = workspace.composeAccount(
                            tenantId, userId, requestedAccountId)
                    .orElseThrow(() -> accountUnavailable(requireBcc));
            MailWorkspaceRepository.ComposeProviderContext provider = workspace
                    .composeProviderContext(tenantId, userId, accountId)
                    .orElseThrow(() -> accountUnavailable(requireBcc));
            MailConnectorPort connector = connectors.connector(provider.providerType())
                    .orElseThrow(() -> accountUnavailable(requireBcc));
            Set<MailConnectorPort.Capability> capabilities =
                    connector.manifest().capabilities();
            if (!capabilities.contains(MailConnectorPort.Capability.SEND)
                    || requireBcc
                    && !capabilities.contains(MailConnectorPort.Capability.BCC)) {
                throw accountUnavailable(requireBcc);
            }
            MailConnectorPort.ConnectionContext context =
                    new MailConnectorPort.ConnectionContext(
                            new ExecutionContext(
                                    Long.toString(tenantId), Long.toString(userId), Set.of(),
                                    correlationId == null || correlationId.isBlank()
                                            ? "mail-group-send" : correlationId.strip()),
                            provider.connectionId(), provider.credentialReference(),
                            provider.mailDomain());
            if (connector.readiness(context).state()
                    != MailConnectorPort.ReadinessState.READY) {
                throw accountUnavailable(requireBcc);
            }
            return provider.accountId();
        } catch (BaseException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw accountUnavailable(requireBcc);
        }
    }

    private BaseException accountUnavailable(boolean requireBcc) {
        return new BaseException(
                ErrorCode.INVALID_STATE,
                requireBcc
                        ? "Group BCC delivery is unavailable for the selected account."
                        : "The selected account provider is not ready for group delivery.");
    }

    private MailAddressBookDtos.ContactGroup group(Long tenantId, Long userId, UUID groupId) {
        return addressBook.group(tenantId, userId, groupId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireSource(String sourceKind, UUID sourcePersonPublicId) {
        if (!"MANUAL".equals(sourceKind) || sourcePersonPublicId != null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Directory provenance can only be assigned by a trusted People import.");
        }
    }

    private void requireMatchingReceipt(
            MailAddressBookCommandReceiptRepository.Receipt receipt,
            String fingerprint) {
        if (!fingerprint.equals(receipt.requestFingerprint())) {
            throw conflict("The idempotency key was already used for different content.");
        }
    }

    private void requireNewReservation(MailAddressBookCommandReceiptRepository.Receipt receipt) {
        if (!receipt.inserted()) {
            throw conflict("The command has not reached a replayable terminal state.");
        }
    }

    private UUID target(MailAddressBookCommandReceiptRepository.Receipt receipt) {
        if (receipt.targetId() == null) {
            throw new IllegalStateException("Completed address-book command has no target.");
        }
        return receipt.targetId();
    }

    private Map<String, Object> contactState(MailAddressBookDtos.Contact contact) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sourceKind", contact.sourceKind());
        state.put("favorite", contact.favorite());
        state.put("version", contact.version());
        return state;
    }

    private Map<String, Object> groupState(MailAddressBookDtos.ContactGroup group) {
        return Map.of(
                "memberCount", group.members().size(),
                "version", group.version());
    }

    private void record(
            Long tenantId,
            Long userId,
            String event,
            String targetType,
            UUID targetId,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after) {
        evidence.audit(
                tenantId, userId, event, targetType, targetId.toString(),
                correlationId, before, after);
        evidence.domainEvent(
                tenantId, targetType, targetId, event, after, correlationId);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
