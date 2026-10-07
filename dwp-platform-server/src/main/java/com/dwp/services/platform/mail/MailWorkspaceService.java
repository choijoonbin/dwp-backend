package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

@Service
public class MailWorkspaceService extends MailWorkspacePolicySupport {

    @Autowired
    public MailWorkspaceService(
            MailWorkspaceRepository repository,
            MailQueryRepository queries,
            MailService mail,
            TenantMediaStorage storage,
            List<MailAttachmentScanner> attachmentScanners,
            MailConnectorRegistry connectors) {
        super(repository, queries, mail, storage, attachmentScanners, connectors);
    }

    MailWorkspaceService(
            MailWorkspaceRepository repository,
            MailQueryRepository queries,
            MailService mail,
            TenantMediaStorage storage,
            List<MailAttachmentScanner> attachmentScanners) {
        this(repository, queries, mail, storage, attachmentScanners,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())));
    }

    MailWorkspaceService(
            MailWorkspaceRepository repository,
            MailQueryRepository queries,
            MailService mail,
            TenantMediaStorage storage) {
        this(repository, queries, mail, storage, List.of());
    }

    @Transactional
    public ComposeContext composeContext(long tenantId, long userId) {
        List<MailDtos.AccountSummary> storedAccounts = queries.accounts(tenantId, userId);
        if (storedAccounts.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND, "No mail account is available.");
        Preferences preferences = effectivePreferences(tenantId, userId);
        int maximumMb = repository.maximumAttachmentMb(tenantId);
        MailDtos.AccountSummary selected = storedAccounts.stream()
                .filter(MailDtos.AccountSummary::defaultAccount)
                .findFirst().orElse(storedAccounts.get(0));
        String displayName = selected.displayName();
        Map<UUID, ComposeCapabilities> accountCapabilities = new LinkedHashMap<>();
        Map<UUID, MailDtos.AccountReadiness> accountReadiness = new LinkedHashMap<>();
        List<MailDtos.AccountSummary> accounts = new ArrayList<>();
        for (MailDtos.AccountSummary account : storedAccounts) {
            AccountRuntime runtime = accountRuntime(
                    tenantId, userId, account.accountId(), maximumMb, account.readiness());
            runtime = withAuthorizationAndFeatureEvidence(account.providerType(), runtime);
            accountCapabilities.put(account.accountId(), runtime.capabilities());
            accountReadiness.put(account.accountId(), runtime.readiness());
            accounts.add(account.withReadiness(runtime.readiness()));
        }
        return new ComposeContext(
                accounts,
                accountCapabilities.get(selected.accountId()),
                Map.copyOf(accountCapabilities),
                Map.copyOf(accountReadiness),
                repository.templates(tenantId, userId, false),
                repository.signatures(tenantId, userId, false),
                preferences,
                Map.of("displayName", displayName, "department", ""),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public Attachment uploadAttachment(
            long tenantId, long userId, MultipartFile file) {
        requireAttachmentScanningAvailable();
        ValidatedAttachment validated = validateAttachment(tenantId, file);
        UUID attachmentId = UUID.randomUUID();
        String scanEvidence = scanAttachment(tenantId, userId, attachmentId, validated);
        String storageReference = storage.store(
                tenantId, "mail/compose/" + userId,
                validated.extension(), validated.content());
        try {
            return repository.createAttachment(
                    tenantId, userId, attachmentId, storageReference,
                    validated.fileName(), validated.contentType(), validated.content().length,
                    validated.checksum(), scanEvidence);
        } catch (RuntimeException failure) {
            try {
                storage.delete(tenantId, storageReference);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Transactional
    public void deleteAttachment(long tenantId, long userId, UUID attachmentId) {
        String reference = repository.deleteOwnedDraftAttachment(tenantId, userId, attachmentId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The removable mail upload was not found."));
        storage.delete(tenantId, reference);
    }

    @Transactional(readOnly = true)
    public AttachmentDownload downloadAttachment(
            long tenantId, long userId, UUID threadId, UUID messageId, UUID attachmentId) {
        MailWorkspaceRepository.AttachmentContentReference attachment = repository
                .visibleAttachment(tenantId, userId, threadId, messageId, attachmentId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The mail attachment was not found."));
        Resource resource = storage.load(tenantId, attachment.storageReference());
        return new AttachmentDownload(
                resource, attachment.fileName(), attachment.contentType(),
                attachment.sizeBytes(), attachment.checksumSha256());
    }

    @Transactional
    public AdvancedComposeResult compose(
            long tenantId, long userId, String correlationId, AdvancedComposeRequest request) {
        List<Recipient> recipients = normalizedRecipients(request.recipients());
        if (recipients.stream().noneMatch(recipient -> recipient.type() == RecipientType.TO)) {
            throw invalid("At least one To recipient is required.");
        }
        List<UUID> attachmentIds = distinctIds(request.attachmentIds());
        OffsetDateTime requestedSchedule = normalizedSchedule(
                request.scheduleAt(), request.timeZone());
        String body = request.bodyFormat() == BodyFormat.HTML
                ? sanitizedHtml(request.body()) : request.body().trim();
        validateTemplateVariables(request.subject(), body);
        repository.lockAdvancedComposeCommand(tenantId, userId, request.idempotencyKey());
        var command = repository.advancedComposeCommand(
                tenantId, userId, request.idempotencyKey()).orElse(null);
        UUID accountId = command == null
                ? repository.composeAccount(tenantId, userId, request.accountId())
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.FORBIDDEN,
                                "The selected sending account is not available."))
                : command.accountId();
        String fingerprint = sha256(String.join("\u001f",
                String.valueOf(userId), accountId.toString(), jsonRecipients(recipients),
                request.subject().trim(), body, request.bodyFormat().name(),
                attachmentIds.toString(), String.valueOf(requestedSchedule),
                requestedSchedule == null ? "" : value(request.timeZone()),
                String.valueOf(request.templateId()), String.valueOf(request.signatureId()),
                request.classification().name(),
                String.valueOf(request.externalRecipientConfirmed())));
        if (command != null) {
            if (!fingerprint.equals(command.requestFingerprint())) {
                throw conflict("The idempotency key was already used for a different message.");
            }
            return new AdvancedComposeResult(
                    mail.thread(tenantId, userId, command.threadId()),
                    repository.delivery(tenantId, userId, command.deliveryId()).orElseThrow());
        }
        validateProviderCapabilities(
                tenantId, userId, accountId, recipients, request.bodyFormat(),
                attachmentIds, requestedSchedule);
        requireAttachmentScanningAvailable(attachmentIds);
        validateSendAssetSelection(
                tenantId, userId, accountId, request.templateId(), request.signatureId(),
                body, request.bodyFormat());
        if (!repository.attachmentsReady(tenantId, userId, attachmentIds)) {
            throw conflict("Every attachment must be owned by the sender and ready before sending.");
        }
        validateAttachmentTotalSize(tenantId, userId, attachmentIds, null);
        boolean externalRecipient = validateExternalRecipientPolicy(
                tenantId, userId, accountId, recipients, request.classification(),
                request.externalRecipientConfirmed());
        OffsetDateTime scheduledAt = requestedSchedule == null
                ? preferenceDelay(tenantId, userId) : requestedSchedule;
        var created = repository.createAdvancedCompose(
                tenantId, userId, accountId, recipients, request.subject(), body,
                request.bodyFormat(), attachmentIds, scheduledAt,
                request.classification(), externalRecipient, request.idempotencyKey(),
                fingerprint, correlation(correlationId));
        if (created == null) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The selected account has no active Sent folder.");
        }
        repository.audit(tenantId, userId, "mail.message.composed", "MAIL_THREAD",
                created.threadId().toString(), correlation(correlationId), Map.of(), Map.of(
                        "deliveryId", created.deliveryId(),
                        "scheduled", scheduledAt != null,
                        "recipientCount", recipients.size(),
                        "attachmentCount", attachmentIds.size(),
                        "classification", request.classification().name(),
                        "externalRecipient", externalRecipient));
        return new AdvancedComposeResult(
                mail.thread(tenantId, userId, created.threadId()),
                repository.delivery(tenantId, userId, created.deliveryId()).orElseThrow());
    }

    @Transactional
    public MailDtos.ThreadDetail compose(
            long tenantId, long userId, String correlationId, MailDtos.ComposeRequest request) {
        ComposeOptions options = request.composeOptions();
        List<Recipient> recipients = options == null || options.recipients().isEmpty()
                ? List.of(new Recipient(RecipientType.TO, request.toName(), request.toEmail()))
                : options.recipients();
        AdvancedComposeRequest advanced = new AdvancedComposeRequest(
                options == null ? null : options.accountId(), recipients,
                request.subject(), request.body(),
                options == null ? BodyFormat.TEXT : options.bodyFormat(),
                options == null ? List.of() : options.attachmentIds(),
                options == null ? null : options.scheduledAt(),
                options == null ? null : options.timeZone(),
                options == null ? null : options.templateId(),
                options == null ? null : options.signatureId(), request.classification(),
                request.externalRecipientConfirmed(), request.idempotencyKey());
        return compose(tenantId, userId, correlationId, advanced).thread();
    }

    @Transactional
    public MailDtos.ThreadDetail sendDraft(
            long tenantId, long userId, UUID threadId, String correlationId,
            MailDtos.DraftUpdateRequest request) {
        ComposeOptions options = request.composeOptions();
        if (options == null) {
            throw invalid("Advanced draft options are required for this send path.");
        }
        List<Recipient> recipients = normalizedRecipients(options.recipients());
        if (recipients.stream().noneMatch(recipient -> recipient.type() == RecipientType.TO)) {
            throw invalid("At least one To recipient is required.");
        }
        List<UUID> attachmentIds = distinctIds(options.attachmentIds());
        OffsetDateTime requestedSchedule = normalizedSchedule(
                options.scheduledAt(), options.timeZone());
        String body = options.bodyFormat() == BodyFormat.HTML
                ? sanitizedHtml(request.body()) : request.body().trim();
        validateTemplateVariables(request.subject(), body);
        repository.lockAdvancedComposeCommand(tenantId, userId, request.idempotencyKey());
        var command = repository.advancedComposeCommand(
                tenantId, userId, request.idempotencyKey()).orElse(null);
        UUID accountId = command == null
                ? repository.composeAccount(tenantId, userId, options.accountId())
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.FORBIDDEN,
                                "The selected sending account is not available."))
                : command.accountId();
        String fingerprint = sha256(String.join("\u001f",
                String.valueOf(userId), threadId.toString(), accountId.toString(),
                jsonRecipients(recipients), request.subject().trim(), body,
                options.bodyFormat().name(), attachmentIds.toString(),
                String.valueOf(requestedSchedule),
                requestedSchedule == null ? "" : value(options.timeZone()),
                String.valueOf(options.templateId()),
                String.valueOf(options.signatureId()), String.valueOf(request.version()),
                request.classification().name(),
                String.valueOf(request.externalRecipientConfirmed())));
        if (command != null) {
            if (!fingerprint.equals(command.requestFingerprint())
                    || !threadId.equals(command.threadId())) {
                throw conflict("The idempotency key was already used for a different draft send.");
            }
            return mail.thread(tenantId, userId, threadId);
        }
        validateProviderCapabilities(
                tenantId, userId, accountId, recipients, options.bodyFormat(),
                attachmentIds, requestedSchedule);
        requireAttachmentScanningAvailable(attachmentIds);
        validateSendAssetSelection(
                tenantId, userId, accountId, options.templateId(), options.signatureId(),
                body, options.bodyFormat());
        if (!repository.attachmentsReadyForThread(
                tenantId, userId, attachmentIds, threadId)) {
            throw conflict("Every attachment must be ready before sending.");
        }
        validateAttachmentTotalSize(tenantId, userId, attachmentIds, threadId);
        boolean externalRecipient = validateExternalRecipientPolicy(
                tenantId, userId, accountId, recipients, request.classification(),
                request.externalRecipientConfirmed());
        OffsetDateTime scheduledAt = requestedSchedule == null
                ? preferenceDelay(tenantId, userId) : requestedSchedule;
        var created = repository.sendAdvancedDraft(
                tenantId, userId, threadId, request.version(), accountId, recipients,
                request.subject(), body, options.bodyFormat(), attachmentIds, scheduledAt,
                request.classification(), externalRecipient, request.idempotencyKey(),
                fingerprint, correlation(correlationId));
        if (created == null) {
            throw conflict("The draft changed. Refresh before sending it.");
        }
        repository.audit(tenantId, userId, "mail.draft.sent", "MAIL_THREAD",
                threadId.toString(), correlation(correlationId),
                Map.of("version", request.version()), Map.of(
                        "deliveryId", created.deliveryId(),
                        "scheduled", scheduledAt != null,
                        "recipientCount", recipients.size(),
                        "attachmentCount", attachmentIds.size(),
                        "classification", request.classification().name(),
                        "externalRecipient", externalRecipient));
        return mail.thread(tenantId, userId, threadId);
    }

    @Transactional(readOnly = true)
    public WritingAssets writingAssets(long tenantId, long userId) {
        return writingAssets(tenantId, userId, false);
    }

    @Transactional(readOnly = true)
    public WritingAssets writingAssets(long tenantId, long userId, boolean includeArchived) {
        return new WritingAssets(
                repository.templates(tenantId, userId, includeArchived),
                repository.signatures(tenantId, userId, includeArchived));
    }

    @Transactional
    public Template createTemplate(long tenantId, long userId, TemplateRequest request) {
        validateAssetScope(tenantId, userId, request.scope(), request.accountId());
        validateTemplateVariables(request.subject(), request.body());
        requireEditableScope(request.scope());
        return repository.createTemplate(tenantId, userId, sanitized(request));
    }

    @Transactional
    public Template updateTemplate(
            long tenantId, long userId, UUID templateId, TemplateRequest request) {
        requireVersion(request.version());
        validateAssetScope(tenantId, userId, request.scope(), request.accountId());
        validateTemplateVariables(request.subject(), request.body());
        requireEditableScope(request.scope());
        return repository.updateTemplate(tenantId, userId, templateId, sanitized(request))
                .orElseThrow(() -> conflict("The template changed. Refresh before saving."));
    }

    @Transactional
    public void archiveTemplate(long tenantId, long userId, UUID templateId, long version) {
        if (!repository.archiveTemplate(tenantId, userId, templateId, version)) {
            throw conflict("The template changed. Refresh before archiving it.");
        }
    }

    @Transactional
    public Signature createSignature(long tenantId, long userId, SignatureRequest request) {
        validateAssetScope(tenantId, userId, request.scope(), request.accountId());
        validateTemplateVariables(null, request.body());
        requireEditableScope(request.scope());
        return repository.createSignature(tenantId, userId, sanitized(request));
    }

    @Transactional
    public Signature updateSignature(
            long tenantId, long userId, UUID signatureId, SignatureRequest request) {
        requireVersion(request.version());
        validateAssetScope(tenantId, userId, request.scope(), request.accountId());
        validateTemplateVariables(null, request.body());
        requireEditableScope(request.scope());
        return repository.updateSignature(tenantId, userId, signatureId, sanitized(request))
                .orElseThrow(() -> conflict("The signature changed. Refresh before saving."));
    }

    @Transactional
    public void archiveSignature(long tenantId, long userId, UUID signatureId, long version) {
        if (!repository.archiveSignature(tenantId, userId, signatureId, version)) {
            throw conflict("The signature changed. Refresh before archiving it.");
        }
    }

    @Transactional
    public Preferences preferences(long tenantId, long userId) {
        return effectivePreferences(tenantId, userId);
    }

    @Transactional
    public Preferences updatePreferences(
            long tenantId, long userId, PreferencesRequest request) {
        Preferences current = effectivePreferences(tenantId, userId);
        if (current.orgLocks().containsKey("remoteImages")
                && !"BLOCK".equals(request.remoteImages())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Organization policy requires remote images to remain blocked.");
        }
        if (!Set.of("COMFORTABLE", "COMPACT").contains(request.density())
                || !Set.of("BLOCK", "ASK", "ALLOW").contains(request.remoteImages())) {
            throw invalid("The selected mail preference is not supported.");
        }
        if (request.defaultAccountId() != null
                && !repository.accountSendAccessible(
                        tenantId, userId, request.defaultAccountId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The selected default account is not available.");
        }
        if (request.defaultSignatureId() != null
                && repository.signature(tenantId, userId, request.defaultSignatureId()).isEmpty()) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The selected signature was not found.");
        }
        return effective(repository.updatePreferences(tenantId, userId, request)
                .orElseThrow(() -> conflict("Mail preferences changed. Refresh before saving.")),
                tenantId);
    }

    @Transactional(readOnly = true)
    public List<SavedView> savedViews(long tenantId, long userId) {
        return repository.savedViews(tenantId, userId);
    }

    @Transactional
    public SavedView createSavedView(long tenantId, long userId, SavedViewRequest request) {
        validateCriteria(tenantId, userId, request.criteria());
        return repository.createSavedView(tenantId, userId, request);
    }

    @Transactional
    public SavedView updateSavedView(
            long tenantId, long userId, UUID savedViewId, SavedViewRequest request) {
        requireVersion(request.version());
        validateCriteria(tenantId, userId, request.criteria());
        return repository.updateSavedView(tenantId, userId, savedViewId, request)
                .orElseThrow(() -> conflict("The saved view changed. Refresh before saving."));
    }

    @Transactional
    public void deleteSavedView(long tenantId, long userId, UUID savedViewId, long version) {
        if (!repository.deleteSavedView(tenantId, userId, savedViewId, version)) {
            throw conflict("The saved view changed. Refresh before deleting it.");
        }
    }

    @Transactional(readOnly = true)
    public List<FollowUp> followUps(long tenantId, long userId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty()
                && !Set.of("WAITING", "OVERDUE", "REPLIED", "CANCELLED").contains(normalized)) {
            throw invalid("The follow-up status is invalid.");
        }
        return repository.followUps(tenantId, userId, normalized);
    }

    @Transactional
    public FollowUp createFollowUp(
            long tenantId, long userId, UUID threadId, FollowUpRequest request) {
        validateFollowUp(request);
        return repository.createFollowUp(tenantId, userId, threadId, request)
                .orElseThrow(() -> conflict("A follow-up already exists for this thread."));
    }

    @Transactional
    public FollowUp updateFollowUp(
            long tenantId, long userId, UUID followUpId, FollowUpRequest request) {
        requireVersion(request.version());
        validateFollowUp(request);
        return repository.updateFollowUp(tenantId, userId, followUpId, request)
                .orElseThrow(() -> conflict("The follow-up changed. Refresh before saving."));
    }

    @Transactional
    public void deleteFollowUp(long tenantId, long userId, UUID followUpId, long version) {
        if (!repository.deleteFollowUp(tenantId, userId, followUpId, version)) {
            throw conflict("The follow-up changed. Refresh before removing it.");
        }
    }

    @Transactional(readOnly = true)
    public DeliveryPage deliveries(
            long tenantId, long userId, String bucket, int page, int pageSize) {
        String resolvedBucket = bucket == null ? "PROCESSING" : bucket.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SCHEDULED", "PROCESSING", "COMPLETED", "ATTENTION").contains(resolvedBucket)) {
            throw invalid("The delivery bucket is invalid.");
        }
        int resolvedPage = Math.max(0, page);
        int resolvedSize = Math.max(1, Math.min(100, pageSize));
        return new DeliveryPage(
                repository.deliveries(tenantId, userId, resolvedBucket, resolvedPage, resolvedSize),
                repository.deliveryCount(tenantId, userId, resolvedBucket),
                resolvedPage, resolvedSize, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public DeliveryReceipt delivery(long tenantId, long userId, UUID deliveryId) {
        return repository.delivery(tenantId, userId, deliveryId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public DeliveryReceipt reschedule(
            long tenantId, long userId, UUID deliveryId, RescheduleRequest request) {
        OffsetDateTime scheduledAt = normalizedSchedule(
                request.scheduledAt(), request.timeZone());
        if (!repository.rescheduleDelivery(
                tenantId, userId, deliveryId, scheduledAt, request.version())) {
            throw conflict("The delivery is no longer eligible for rescheduling.");
        }
        return delivery(tenantId, userId, deliveryId);
    }

    @Transactional
    public DeliveryReceipt cancel(
            long tenantId, long userId, UUID deliveryId, VersionRequest request) {
        if (!repository.cancelDelivery(tenantId, userId, deliveryId, request.version())) {
            throw conflict("The delivery is no longer eligible for cancellation.");
        }
        return delivery(tenantId, userId, deliveryId);
    }

    @Transactional
    public DeliveryReceipt reconcile(
            long tenantId, long userId, UUID deliveryId, VersionRequest request) {
        DeliveryReceipt before = delivery(tenantId, userId, deliveryId);
        if (!before.canReconcile()) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "This delivery has current evidence and does not need reconciliation.");
        }
        if (!repository.reconcileSandboxDelivery(
                tenantId, userId, deliveryId, request.version())) {
            throw conflict("The delivery evidence changed. Refresh before reconciling.");
        }
        repository.audit(tenantId, userId, "mail.delivery.reconciled", "MAIL_DELIVERY",
                deliveryId.toString(), UUID.randomUUID().toString(), Map.of(
                        "state", before.state(), "version", before.version()), Map.of(
                        "outcome", "SANDBOX_DELIVERY_REQUEUED"));
        return delivery(tenantId, userId, deliveryId);
    }

    @Transactional
    public DeliveryReceipt retry(
            long tenantId, long userId, UUID deliveryId, VersionRequest request) {
        DeliveryReceipt before = delivery(tenantId, userId, deliveryId);
        if (!"ELIGIBLE".equals(before.retryEligibility())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "This delivery is not eligible for a safe retry.");
        }
        if (!repository.retryDelivery(
                tenantId, userId, deliveryId, request.version())) {
            throw conflict("The delivery changed before it could be retried.");
        }
        repository.audit(tenantId, userId, "mail.delivery.retried", "MAIL_DELIVERY",
                deliveryId.toString(), UUID.randomUUID().toString(), Map.of(
                        "state", before.state(), "version", before.version()), Map.of(
                        "state", "QUEUED", "retryKind", "SAFE_MANUAL_RETRY"));
        return delivery(tenantId, userId, deliveryId);
    }

    @Transactional
    public void saveDraftOptions(
            long tenantId, long userId, UUID threadId, ComposeOptions options) {
        if (options == null) return;
        ComposeOptions normalized = prepareDraftOptions(
                tenantId, userId, threadId, options);
        saveValidatedDraftOptions(tenantId, userId, threadId, normalized);
    }

    ComposeOptions prepareDraftOptions(
            long tenantId, long userId, UUID threadId, ComposeOptions options) {
        if (options == null) return null;
        UUID accountId = repository.composeAccount(tenantId, userId, options.accountId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "The selected sending account is not available."));
        List<Recipient> recipients = normalizedRecipients(options.recipients());
        List<UUID> attachments = distinctIds(options.attachmentIds());
        if (!repository.attachmentsReadyForThread(
                tenantId, userId, attachments, threadId)) {
            throw conflict("Every attachment must be ready before it can be added to a draft.");
        }
        validateAssetSelection(
                tenantId, userId, accountId, options.templateId(), options.signatureId());
        OffsetDateTime scheduledAt = normalizedSchedule(
                options.scheduledAt(), options.timeZone());
        return new ComposeOptions(
                accountId, recipients, options.bodyFormat(), attachments,
                scheduledAt, scheduledAt == null ? null : options.timeZone().strip(),
                options.templateId(), options.signatureId());
    }

    void saveValidatedDraftOptions(
            long tenantId, long userId, UUID threadId, ComposeOptions options) {
        if (options != null) repository.saveDraftOptions(tenantId, userId, threadId, options);
    }

    @Transactional(readOnly = true)
    public MailDtos.ThreadDetail enrichDraft(
            long tenantId, long userId, MailDtos.ThreadDetail detail) {
        ComposeOptions options = repository.draftOptions(
                tenantId, userId, detail.thread().threadId()).orElse(null);
        return new MailDtos.ThreadDetail(
                detail.thread(), detail.messages(), detail.internalComments(), detail.proposals(),
                detail.sharedInboxMembers(), detail.sharedInboxActions(),
                detail.sharedInboxReplyIdentity(), options,
                repository.draftAttachments(tenantId, userId, detail.thread().threadId()));
    }

    /** Published download contract; declared here to preserve its stable JVM binary name. */
    public record AttachmentDownload(
            Resource resource,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256) {
    }

}
