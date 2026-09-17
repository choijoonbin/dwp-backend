package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
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
public class MailWorkspaceService {

    private static final Set<String> ALLOWED_TEMPLATE_VARIABLES = Set.of(
            "displayName", "department", "recipientName");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}", Pattern.UNICODE_CASE);
    private static final Safelist MAIL_HTML = new Safelist()
            .addTags(
                    "p", "div", "br", "strong", "b", "em", "i", "u", "s",
                    "ul", "ol", "li", "blockquote", "pre", "code", "a",
                    "h1", "h2", "h3", "h4", "h5", "h6")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto");

    private final MailWorkspaceRepository repository;
    private final MailQueryRepository queries;
    private final MailService mail;
    private final TenantMediaStorage storage;

    public MailWorkspaceService(
            MailWorkspaceRepository repository,
            MailQueryRepository queries,
            MailService mail,
            TenantMediaStorage storage) {
        this.repository = repository;
        this.queries = queries;
        this.mail = mail;
        this.storage = storage;
    }

    @Transactional
    public ComposeContext composeContext(long tenantId, long userId) {
        List<MailDtos.AccountSummary> accounts = queries.accounts(tenantId, userId);
        if (accounts.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND, "No mail account is available.");
        Preferences preferences = effectivePreferences(tenantId, userId);
        int maximumMb = repository.maximumAttachmentMb(tenantId);
        String displayName = accounts.stream().filter(MailDtos.AccountSummary::defaultAccount)
                .findFirst().orElse(accounts.get(0)).displayName();
        return new ComposeContext(
                accounts,
                new ComposeCapabilities(true, true, true, true, true, true,
                        maximumMb * 1024L * 1024L),
                repository.templates(tenantId, userId, false),
                repository.signatures(tenantId, userId, false),
                preferences,
                Map.of("displayName", displayName, "department", ""),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public Attachment uploadAttachment(
            long tenantId, long userId, MultipartFile file) {
        ValidatedAttachment validated = validateAttachment(tenantId, file);
        UUID attachmentId = UUID.randomUUID();
        String storageReference = storage.store(
                tenantId, "mail/compose/" + userId,
                validated.extension(), validated.content());
        try {
            return repository.createAttachment(
                    tenantId, userId, attachmentId, storageReference,
                    validated.fileName(), validated.contentType(), validated.content().length,
                    validated.checksum(), "DWP_CONTENT_POLICY:" + validated.contentType());
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

    @Transactional
    public AdvancedComposeResult compose(
            long tenantId, long userId, String correlationId, AdvancedComposeRequest request) {
        List<Recipient> recipients = normalizedRecipients(request.recipients());
        if (recipients.stream().noneMatch(recipient -> recipient.type() == RecipientType.TO)) {
            throw invalid("At least one To recipient is required.");
        }
        UUID accountId = repository.composeAccount(tenantId, userId, request.accountId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "The selected sending account is not available."));
        validateAssetSelection(
                tenantId, userId, accountId, request.templateId(), request.signatureId());
        List<UUID> attachmentIds = distinctIds(request.attachmentIds());
        if (!repository.attachmentsReady(tenantId, userId, attachmentIds)) {
            throw conflict("Every attachment must be owned by the sender and ready before sending.");
        }
        OffsetDateTime requestedSchedule = normalizedSchedule(
                request.scheduleAt(), request.timeZone());
        OffsetDateTime scheduledAt = requestedSchedule == null
                ? preferenceDelay(tenantId, userId) : requestedSchedule;
        String body = request.bodyFormat() == BodyFormat.HTML
                ? sanitizedHtml(request.body()) : request.body().trim();
        validateTemplateVariables(request.subject(), body);
        String fingerprint = sha256(String.join("\u001f",
                String.valueOf(userId), accountId.toString(), jsonRecipients(recipients),
                request.subject().trim(), body, request.bodyFormat().name(),
                attachmentIds.toString(), String.valueOf(requestedSchedule),
                requestedSchedule == null ? "" : value(request.timeZone()),
                String.valueOf(request.templateId()), String.valueOf(request.signatureId())));
        repository.lockAdvancedComposeCommand(tenantId, userId, request.idempotencyKey());
        var replay = repository.advancedComposeReplay(
                tenantId, userId, request.idempotencyKey(), fingerprint).orElse(null);
        if (replay != null) {
            if (replay.threadId() == null) {
                throw conflict("The idempotency key was already used for a different message.");
            }
            return new AdvancedComposeResult(
                    mail.thread(tenantId, userId, replay.threadId()),
                    repository.delivery(tenantId, userId, replay.deliveryId()).orElseThrow());
        }
        var created = repository.createAdvancedCompose(
                tenantId, userId, accountId, recipients, request.subject(), body,
                request.bodyFormat(), attachmentIds, scheduledAt, request.idempotencyKey(),
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
                        "attachmentCount", attachmentIds.size()));
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
                options == null ? null : options.signatureId(), request.idempotencyKey());
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
        UUID accountId = repository.composeAccount(tenantId, userId, options.accountId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "The selected sending account is not available."));
        validateAssetSelection(
                tenantId, userId, accountId, options.templateId(), options.signatureId());
        List<UUID> attachmentIds = distinctIds(options.attachmentIds());
        if (!repository.attachmentsReadyForThread(
                tenantId, userId, attachmentIds, threadId)) {
            throw conflict("Every attachment must be ready before sending.");
        }
        OffsetDateTime requestedSchedule = normalizedSchedule(
                options.scheduledAt(), options.timeZone());
        OffsetDateTime scheduledAt = requestedSchedule == null
                ? preferenceDelay(tenantId, userId) : requestedSchedule;
        String body = options.bodyFormat() == BodyFormat.HTML
                ? sanitizedHtml(request.body()) : request.body().trim();
        validateTemplateVariables(request.subject(), body);
        String fingerprint = sha256(String.join("\u001f",
                String.valueOf(userId), threadId.toString(), accountId.toString(),
                jsonRecipients(recipients), request.subject().trim(), body,
                options.bodyFormat().name(), attachmentIds.toString(),
                String.valueOf(requestedSchedule),
                requestedSchedule == null ? "" : value(options.timeZone()),
                String.valueOf(options.templateId()),
                String.valueOf(options.signatureId()), String.valueOf(request.version())));
        repository.lockAdvancedComposeCommand(tenantId, userId, request.idempotencyKey());
        var replay = repository.advancedComposeReplay(
                tenantId, userId, request.idempotencyKey(), fingerprint).orElse(null);
        if (replay != null) {
            if (replay.threadId() == null || !threadId.equals(replay.threadId())) {
                throw conflict("The idempotency key was already used for a different draft send.");
            }
            return mail.thread(tenantId, userId, threadId);
        }
        var created = repository.sendAdvancedDraft(
                tenantId, userId, threadId, request.version(), accountId, recipients,
                request.subject(), body, options.bodyFormat(), attachmentIds, scheduledAt,
                request.idempotencyKey(), fingerprint, correlation(correlationId));
        if (created == null) {
            throw conflict("The draft changed. Refresh before sending it.");
        }
        repository.audit(tenantId, userId, "mail.draft.sent", "MAIL_THREAD",
                threadId.toString(), correlation(correlationId),
                Map.of("version", request.version()), Map.of(
                        "deliveryId", created.deliveryId(),
                        "scheduled", scheduledAt != null,
                        "recipientCount", recipients.size(),
                        "attachmentCount", attachmentIds.size()));
        return mail.thread(tenantId, userId, threadId);
    }

    @Transactional(readOnly = true)
    public WritingAssets writingAssets(long tenantId, long userId) {
        return new WritingAssets(
                repository.templates(tenantId, userId, false),
                repository.signatures(tenantId, userId, false));
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
                && !repository.accountAccessible(tenantId, userId, request.defaultAccountId())) {
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
        if (!repository.touchDeliveryEvidence(tenantId, userId, deliveryId, request.version())) {
            throw conflict("The delivery evidence changed. Refresh before reconciling.");
        }
        repository.audit(tenantId, userId, "mail.delivery.reconciled", "MAIL_DELIVERY",
                deliveryId.toString(), UUID.randomUUID().toString(), Map.of(
                        "state", before.state(), "version", before.version()), Map.of(
                        "outcome", "CURRENT_OUTBOX_EVIDENCE_RELOADED"));
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
        validateComposeOptions(tenantId, userId, threadId, options);
        repository.saveDraftOptions(tenantId, userId, threadId, options);
    }

    @Transactional(readOnly = true)
    public MailDtos.ThreadDetail enrichDraft(
            long tenantId, long userId, MailDtos.ThreadDetail detail) {
        ComposeOptions options = repository.draftOptions(
                tenantId, userId, detail.thread().threadId()).orElse(null);
        return new MailDtos.ThreadDetail(
                detail.thread(), detail.messages(), detail.internalComments(), detail.proposals(),
                detail.sharedInboxMembers(), options,
                repository.draftAttachments(tenantId, userId, detail.thread().threadId()));
    }

    private Preferences effectivePreferences(long tenantId, long userId) {
        return effective(repository.preferences(tenantId, userId), tenantId);
    }

    private Preferences effective(Preferences stored, long tenantId) {
        if (!repository.blockRemoteImages(tenantId)) return stored;
        Map<String, String> locks = Map.of(
                "remoteImages", "Organization policy blocks remote images.");
        return new Preferences(
                stored.density(), "BLOCK", stored.sendDelaySeconds(), stored.keyboardShortcuts(),
                stored.notifyNewMail(), stored.notifySharedAssignment(), stored.notifyFollowUpDue(),
                stored.defaultAccountId(), stored.defaultSignatureId(), locks, stored.version());
    }

    private void validateComposeOptions(
            long tenantId, long userId, UUID threadId, ComposeOptions options) {
        UUID accountId = repository.composeAccount(tenantId, userId, options.accountId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "The selected sending account is not available."));
        normalizedRecipients(options.recipients());
        List<UUID> attachments = distinctIds(options.attachmentIds());
        if (!repository.attachmentsReadyForThread(tenantId, userId, attachments, threadId)) {
            throw conflict("Every attachment must be ready before it can be added to a draft.");
        }
        validateAssetSelection(
                tenantId, userId, accountId, options.templateId(), options.signatureId());
        normalizedSchedule(options.scheduledAt(), options.timeZone());
    }

    private void validateAssetSelection(
            long tenantId, long userId, UUID accountId, UUID templateId, UUID signatureId) {
        if (templateId != null) {
            Template template = repository.template(tenantId, userId, templateId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.NOT_FOUND, "The selected template was not found."));
            requireAssetAccount(accountId, template.accountId());
        }
        if (signatureId != null) {
            Signature signature = repository.signature(tenantId, userId, signatureId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.NOT_FOUND, "The selected signature was not found."));
            requireAssetAccount(accountId, signature.accountId());
        }
    }

    private void requireAssetAccount(UUID composeAccountId, UUID assetAccountId) {
        if (assetAccountId != null && !assetAccountId.equals(composeAccountId)) {
            throw invalid("The writing asset belongs to a different sending account.");
        }
    }

    private void validateAssetScope(
            long tenantId, long userId, AssetScope scope, UUID accountId) {
        if (scope == AssetScope.PERSONAL && accountId != null
                || scope == AssetScope.ACCOUNT && accountId == null
                || scope == AssetScope.ORGANIZATION && accountId != null) {
            throw invalid("The writing asset scope and account do not match.");
        }
        if (accountId != null && !repository.accountAccessible(tenantId, userId, accountId)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The selected account is not available for this writing asset.");
        }
    }

    private void requireEditableScope(AssetScope scope) {
        if (scope == AssetScope.ORGANIZATION) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Organization writing assets are managed by an administrator.");
        }
    }

    private TemplateRequest sanitized(TemplateRequest request) {
        return new TemplateRequest(
                request.name(), request.subject(),
                request.bodyFormat() == BodyFormat.HTML
                        ? sanitizedHtml(request.body()) : request.body().trim(),
                request.bodyFormat(), request.scope(), request.accountId(), request.version());
    }

    private SignatureRequest sanitized(SignatureRequest request) {
        return new SignatureRequest(
                request.name(), request.bodyFormat() == BodyFormat.HTML
                        ? sanitizedHtml(request.body()) : request.body().trim(),
                request.bodyFormat(), request.scope(), request.accountId(),
                request.defaultForNew(), request.defaultForReply(), request.version());
    }

    private void validateTemplateVariables(String subject, String body) {
        for (String value : List.of(subject == null ? "" : subject, body == null ? "" : body)) {
            Matcher matcher = TEMPLATE_VARIABLE.matcher(value);
            while (matcher.find()) {
                if (!ALLOWED_TEMPLATE_VARIABLES.contains(matcher.group(1))) {
                    throw invalid("Unsupported template variable: " + matcher.group(1));
                }
            }
        }
    }

    private void validateCriteria(long tenantId, long userId, SearchCriteria criteria) {
        if (criteria.accountId() != null
                && !repository.accountAccessible(tenantId, userId, criteria.accountId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The saved view account is not available.");
        }
        if (criteria.dateFrom() != null && criteria.dateTo() != null
                && criteria.dateFrom().isAfter(criteria.dateTo())) {
            throw invalid("The saved view date range is invalid.");
        }
        if (criteria.scope() != null
                && !Set.of("ALL", "PERSONAL", "SHARED").contains(criteria.scope())) {
            throw invalid("The saved view scope is invalid.");
        }
    }

    private void validateFollowUp(FollowUpRequest request) {
        try {
            java.time.ZoneId.of(request.timeZone());
        } catch (RuntimeException failure) {
            throw invalid("The follow-up time zone is invalid.");
        }
        if (!request.expectedReplyAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw invalid("The expected reply time must be in the future.");
        }
    }

    private List<Recipient> normalizedRecipients(List<Recipient> recipients) {
        LinkedHashMap<String, Recipient> unique = new LinkedHashMap<>();
        for (Recipient recipient : recipients) {
            String email = recipient.email().trim().toLowerCase(Locale.ROOT);
            String key = recipient.type().name() + ":" + email;
            unique.putIfAbsent(key, new Recipient(
                    recipient.type(), nullable(recipient.name()), email));
        }
        if (unique.isEmpty()) throw invalid("At least one recipient is required.");
        return List.copyOf(unique.values());
    }

    private List<UUID> distinctIds(List<UUID> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<UUID> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID value : values) if (value != null && seen.add(value)) result.add(value);
        return List.copyOf(result);
    }

    private OffsetDateTime normalizedSchedule(OffsetDateTime value, String timeZone) {
        if (value == null) return null;
        if (timeZone == null || timeZone.isBlank()) {
            throw invalid("A time zone is required for scheduled delivery.");
        }
        try {
            java.time.ZoneId.of(timeZone);
        } catch (RuntimeException failure) {
            throw invalid("The scheduled delivery time zone is invalid.");
        }
        if (!value.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30))) {
            throw invalid("Scheduled delivery must be in the future.");
        }
        if (value.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusYears(1))) {
            throw invalid("Scheduled delivery cannot be more than one year in the future.");
        }
        return value;
    }

    private OffsetDateTime preferenceDelay(long tenantId, long userId) {
        int delay = effectivePreferences(tenantId, userId).sendDelaySeconds();
        return delay > 0 ? OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delay) : null;
    }

    private String sanitizedHtml(String html) {
        String sanitized = Jsoup.clean(
                html.trim(), "", MAIL_HTML,
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        if (Jsoup.parseBodyFragment(sanitized).text().isBlank()) {
            throw invalid("The HTML message has no safe content.");
        }
        return sanitized;
    }

    private ValidatedAttachment validateAttachment(long tenantId, MultipartFile file) {
        long maximumBytes = repository.maximumAttachmentMb(tenantId) * 1024L * 1024L;
        if (file == null || file.isEmpty() || file.getSize() < 1 || file.getSize() > maximumBytes) {
            throw invalid("The attachment exceeds the organization size policy.");
        }
        try {
            byte[] content = file.getBytes();
            DetectedMedia media = detect(content, file.getContentType(), file.getOriginalFilename());
            return new ValidatedAttachment(safeFileName(file.getOriginalFilename()),
                    media.contentType(), media.extension(), content, sha256(content));
        } catch (IOException failure) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The attachment could not be read.", failure);
        }
    }

    private DetectedMedia detect(byte[] content, String declared, String name) {
        if (starts(content, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return exactDeclared(declared, "image/png", "png");
        }
        if (starts(content, new int[]{0xff, 0xd8, 0xff})) {
            return exactDeclared(declared, "image/jpeg", "jpg");
        }
        if (starts(content, new int[]{'%', 'P', 'D', 'F', '-'})) {
            return exactDeclared(declared, "application/pdf", "pdf");
        }
        if (starts(content, new int[]{'P', 'K', 0x03, 0x04})) {
            String lower = safeFileName(name).toLowerCase(Locale.ROOT);
            if (lower.endsWith(".docx")) return declaredOneOf(declared,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            if (lower.endsWith(".xlsx")) return declaredOneOf(declared,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
            if (lower.endsWith(".pptx")) return declaredOneOf(declared,
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
            return declaredOneOf(declared, "application/zip", "zip");
        }
        if ("text/plain".equals(declared)) {
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));
                return new DetectedMedia("text/plain", "txt");
            } catch (CharacterCodingException failure) {
                throw invalid("The text attachment is not valid UTF-8.");
            }
        }
        throw invalid("This attachment type is not allowed by the mail content policy.");
    }

    private DetectedMedia exactDeclared(String declared, String expected, String extension) {
        if (!expected.equals(declared)) throw invalid("The attachment content type does not match its bytes.");
        return new DetectedMedia(expected, extension);
    }

    private DetectedMedia declaredOneOf(String declared, String expected, String extension) {
        if (declared == null || !(declared.equals(expected)
                || declared.equals("application/octet-stream")
                || declared.equals("application/zip"))) {
            throw invalid("The attachment content type does not match its file format.");
        }
        return new DetectedMedia(expected, extension);
    }

    private boolean starts(byte[] content, int[] signature) {
        if (content.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((content[index] & 0xff) != signature[index]) return false;
        }
        return true;
    }

    private String safeFileName(String value) {
        if (value == null) throw invalid("The attachment file name is required.");
        String candidate = value.replace('\\', '/');
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1).trim();
        if (candidate.isBlank() || candidate.length() > 255
                || candidate.chars().anyMatch(Character::isISOControl)) {
            throw invalid("The attachment file name is invalid.");
        }
        return candidate;
    }

    private String jsonRecipients(List<Recipient> recipients) {
        return recipients.stream().map(recipient -> recipient.type() + ":"
                + recipient.email() + ":" + value(recipient.name())).reduce("", (a, b) -> a + "|" + b);
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private void requireVersion(Long version) {
        if (version == null || version < 0) throw invalid("A current version is required.");
    }

    private String correlation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (normalized.length() > 160) throw invalid("The correlation id is too long.");
        return normalized;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record ValidatedAttachment(
            String fileName, String contentType, String extension,
            byte[] content, String checksum) {
    }

    private record DetectedMedia(String contentType, String extension) {
    }
}
