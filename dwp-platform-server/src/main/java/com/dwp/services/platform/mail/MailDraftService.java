package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public MailDraftService(
            MailService mail,
            MailDraftRepository drafts,
            MailDraftCommandReceiptRepository receipts,
            MailCommandRepository evidence) {
        this.mail = mail;
        this.drafts = drafts;
        this.receipts = receipts;
        this.fingerprints = new MailDraftCommandFingerprint();
        this.evidence = evidence;
    }

    @Transactional
    public MailDtos.ThreadDetail create(
            Long tenantId,
            Long userId,
            String correlationId,
            MailDtos.DraftSaveRequest request) {
        requireContent(request);
        if (request.version() != null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A new draft must not include a version.");
        }
        String fingerprint = fingerprints.create(request);
        MailDraftCommandReceiptRepository.Receipt receipt = receipts.reserve(
                tenantId, userId, CREATE, request.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) {
            return mail.thread(tenantId, userId, requireThreadId(receipt));
        }
        requireNewReservation(receipt);
        MailDraftRepository.CreateResult result = drafts.create(tenantId, userId, request);
        if (result == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No active default personal mail account is available.");
        }
        MailDtos.ThreadDetail detail = mail.thread(tenantId, userId, result.threadId());
        if (result.created()) {
            record(
                    tenantId, userId, result.threadId(), correlationId,
                    Map.of(), detail.thread(), request.idempotencyKey());
        }
        receipts.complete(
                tenantId, userId, CREATE, request.idempotencyKey(), fingerprint,
                result.threadId(), detail.thread().version());
        return detail;
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
        String fingerprint = fingerprints.save(threadId, request);
        MailDraftCommandReceiptRepository.Receipt receipt = receipts.reserve(
                tenantId, userId, SAVE, request.idempotencyKey(), fingerprint);
        requireMatchingReceipt(receipt, fingerprint);
        if (receipt.completed()) return mail.thread(tenantId, userId, requireThreadId(receipt));
        requireNewReservation(receipt);
        if (drafts.save(tenantId, userId, threadId, request) == 0) {
            throw conflict();
        }
        MailDtos.ThreadDetail after = mail.thread(tenantId, userId, threadId);
        record(
                tenantId, userId, threadId, correlationId,
                state(before.thread()), after.thread(), request.idempotencyKey());
        receipts.complete(
                tenantId, userId, SAVE, request.idempotencyKey(), fingerprint,
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
                && value(request.body()).isBlank()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A draft must contain a recipient, subject, or message body.");
        }
    }

    private void requireEditable(MailDtos.ThreadSummary thread) {
        if (!"DRAFTS".equals(thread.folderType())
                || thread.workflowState() != WorkflowState.DRAFT
                || thread.sharedInboxId() != null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only a personal draft can be saved.");
        }
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
