package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository;

import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailDraftCommandReceiptRepository.CommandType.CREATE;
import static com.dwp.services.platform.mail.MailDraftCommandReceiptRepository.CommandType.SAVE;
import static com.dwp.services.platform.mail.MailTypes.WorkflowState;

@Service
public class MailDraftService {

    private final MailService mail;
    private final MailDraftRepository drafts;
    private final MailDraftCommandReceiptRepository receipts;
    private final MailDraftCommandFingerprint fingerprints;
    private final MailCommandRepository evidence;
    private final MailWorkspaceService workspace;
    private PlatformDwaionHandoffOutboxRepository dwaionHandoffs;

    @Autowired(required = false)
    void setDwaionHandoffs(PlatformDwaionHandoffOutboxRepository dwaionHandoffs) {
        this.dwaionHandoffs = dwaionHandoffs;
    }

    @Autowired
    public MailDraftService(
            MailService mail,
            MailDraftRepository drafts,
            MailDraftCommandReceiptRepository receipts,
            MailCommandRepository evidence,
            MailWorkspaceService workspace) {
        this.mail = mail;
        this.drafts = drafts;
        this.receipts = receipts;
        this.fingerprints = new MailDraftCommandFingerprint();
        this.evidence = evidence;
        this.workspace = workspace;
    }

    MailDraftService(
            MailService mail,
            MailDraftRepository drafts,
            MailDraftCommandReceiptRepository receipts,
            MailCommandRepository evidence) {
        this(mail, drafts, receipts, evidence, null);
    }

    @Transactional
    public MailDtos.ThreadDetail create(
            Long tenantId,
            Long userId,
            String correlationId,
            MailDtos.DraftSaveRequest request) {
        return create(tenantId, userId, correlationId, request, null, null);
    }

    @Transactional
    public MailDtos.ThreadDetail create(
            Long tenantId,
            Long userId,
            String correlationId,
            MailDtos.DraftSaveRequest request,
            PlatformDwaionHandoff.Binding dwaionBinding,
            PlatformDwaionHandoff.Identity dwaionIdentity) {
        requireContent(request);
        if (request.version() != null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A new draft must not include a version.");
        }
        MailDtos.DraftSaveRequest canonical = canonicalRequest(
                tenantId, userId, null, request);
        String fingerprint = fingerprints.create(canonical);
        MailDraftCommandReceiptRepository.Receipt receipt = receipts.reserve(
                tenantId, userId, CREATE, canonical.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) {
            MailDtos.ThreadDetail replay = enrich(tenantId, userId,
                    mail.thread(tenantId, userId, requireThreadId(receipt)),
                    canonical.composeOptions());
            completeDwaion(tenantId, userId, correlationId, replay,
                    dwaionBinding, dwaionIdentity);
            return replay;
        }
        requireNewReservation(receipt);
        MailDraftRepository.CreateResult result = canonical.composeOptions() == null
                ? drafts.create(tenantId, userId, canonical)
                : drafts.create(tenantId, userId, canonical,
                        canonical.composeOptions().accountId());
        if (result == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No active default personal mail account is available.");
        }
        MailDtos.ThreadDetail detail = mail.thread(tenantId, userId, result.threadId());
        if (canonical.composeOptions() != null) {
            workspace.saveValidatedDraftOptions(
                    tenantId, userId, result.threadId(), canonical.composeOptions());
            detail = workspace.enrichDraft(tenantId, userId, detail);
        }
        if (result.created()) {
            record(
                    tenantId, userId, result.threadId(), correlationId,
                    Map.of(), detail.thread(), canonical.idempotencyKey());
        }
        receipts.complete(
                tenantId, userId, CREATE, canonical.idempotencyKey(), fingerprint,
                result.threadId(), detail.thread().version());
        completeDwaion(tenantId, userId, correlationId, detail,
                dwaionBinding, dwaionIdentity);
        return detail;
    }

    private void completeDwaion(
            Long tenantId,
            Long userId,
            String correlationId,
            MailDtos.ThreadDetail detail,
            PlatformDwaionHandoff.Binding binding,
            PlatformDwaionHandoff.Identity identity) {
        if (binding == null) return;
        if (dwaionHandoffs == null || detail == null || detail.thread() == null
                || detail.thread().workflowState() != WorkflowState.DRAFT) {
            throw PlatformDwaionHandoff.unavailable();
        }
        dwaionHandoffs.committed(
                tenantId, userId, binding, identity,
                PlatformDwaionHandoff.Effect.forBinding(
                        binding, detail.thread().threadId(), detail.thread().version(), "DRAFT"),
                correlationId);
    }

    @Transactional
    public MailDtos.ThreadDetail save(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailDtos.DraftSaveRequest request) {
        requireContent(request);
        if (request.version() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A draft version is required when saving changes.");
        }
        MailDtos.ThreadDetail before = mail.thread(tenantId, userId, threadId);
        requireEditable(before.thread());
        MailDtos.DraftSaveRequest canonical = canonicalRequest(
                tenantId, userId, threadId, request);
        String fingerprint = fingerprints.save(threadId, canonical);
        MailDraftCommandReceiptRepository.Receipt receipt = receipts.reserve(
                tenantId, userId, SAVE, canonical.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) return enrich(
                tenantId, userId, mail.thread(tenantId, userId, requireThreadId(receipt)),
                canonical.composeOptions());
        requireNewReservation(receipt);
        int updated = canonical.composeOptions() == null
                ? drafts.save(tenantId, userId, threadId, canonical)
                : drafts.save(tenantId, userId, threadId, canonical,
                        canonical.composeOptions().accountId());
        if (updated == 0) {
            throw conflict();
        }
        MailDtos.ThreadDetail after = mail.thread(tenantId, userId, threadId);
        if (canonical.composeOptions() != null) {
            workspace.saveValidatedDraftOptions(
                    tenantId, userId, threadId, canonical.composeOptions());
            after = workspace.enrichDraft(tenantId, userId, after);
        }
        record(
                tenantId, userId, threadId, correlationId,
                state(before.thread()), after.thread(), canonical.idempotencyKey());
        receipts.complete(
                tenantId, userId, SAVE, canonical.idempotencyKey(), fingerprint,
                threadId, after.thread().version());
        return after;
    }

    private void requireMatchingReceipt(
            MailDraftCommandReceiptRepository.Receipt receipt,
            String requestFingerprint) {
        if (!requestFingerprint.equals(receipt.requestFingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different draft command.");
        }
    }

    private void requireNewReservation(MailDraftCommandReceiptRepository.Receipt receipt) {
        if (!receipt.inserted()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The draft command has not reached a replayable terminal state.");
        }
    }

    private UUID requireThreadId(MailDraftCommandReceiptRepository.Receipt receipt) {
        if (receipt.threadId() == null) {
            throw new IllegalStateException("Completed mail draft command receipt has no thread.");
        }
        return receipt.threadId();
    }

    private void requireContent(MailDtos.DraftSaveRequest request) {
        if (value(request.toEmail()).isBlank()
                && value(request.subject()).isBlank()
                && value(request.body()).isBlank()
                && !meaningfulOptions(request.composeOptions())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A draft must contain message content or compose options.");
        }
    }

    private boolean meaningfulOptions(MailWorkspaceDtos.ComposeOptions options) {
        if (options == null) return false;
        return options.accountId() != null
                || options.recipients() != null && !options.recipients().isEmpty()
                || options.bodyFormat() == MailWorkspaceDtos.BodyFormat.HTML
                || options.attachmentIds() != null && !options.attachmentIds().isEmpty()
                || options.scheduledAt() != null
                || !value(options.timeZone()).isBlank()
                || options.templateId() != null
                || options.signatureId() != null;
    }

    private void requireEditable(MailDtos.ThreadSummary thread) {
        if (!"DRAFTS".equals(thread.folderType())
                || thread.workflowState() != WorkflowState.DRAFT) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only a personal draft can be saved.");
        }
    }

    private MailDtos.DraftSaveRequest canonicalRequest(
            long tenantId,
            long userId,
            UUID threadId,
            MailDtos.DraftSaveRequest request) {
        if (workspace == null || request.composeOptions() == null) return request;
        MailWorkspaceDtos.ComposeOptions options = workspace.prepareDraftOptions(
                tenantId, userId, threadId, request.composeOptions());
        return new MailDtos.DraftSaveRequest(
                request.toEmail(), request.toName(), request.subject(), request.body(),
                request.classification(), request.externalRecipientConfirmed(),
                request.idempotencyKey(), request.version(), options);
    }

    private MailDtos.ThreadDetail enrich(
            long tenantId,
            long userId,
            MailDtos.ThreadDetail detail,
            MailWorkspaceDtos.ComposeOptions options) {
        return workspace != null && options != null
                ? workspace.enrichDraft(tenantId, userId, detail) : detail;
    }

    private void record(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            Map<String, Object> before,
            MailDtos.ThreadSummary after,
            UUID idempotencyKey) {
        Map<String, Object> next = Map.of(
                "workflowState", after.workflowState().name(),
                "folderType", after.folderType(),
                "version", after.version(),
                "idempotencyKey", idempotencyKey);
        evidence.audit(
                tenantId, userId, "mail.draft.saved", "MAIL_THREAD",
                threadId.toString(), correlationId, before, next);
        evidence.domainEvent(
                tenantId, "MAIL_THREAD", threadId, "mail.draft.saved",
                Map.of(
                        "threadId", threadId,
                        "accountId", after.accountId(),
                        "classification", after.classification().name(),
                        "version", after.version()),
                correlationId);
    }

    private Map<String, Object> state(MailDtos.ThreadSummary thread) {
        return Map.of(
                "workflowState", thread.workflowState().name(),
                "folderType", thread.folderType(),
                "version", thread.version());
    }

    private String value(String input) {
        return input == null ? "" : input.trim();
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The draft changed. Refresh it before saving again.");
    }
}
