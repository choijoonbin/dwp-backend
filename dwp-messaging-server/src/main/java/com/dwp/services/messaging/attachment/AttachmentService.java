package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 10;

    private final AttachmentRepository repository;
    private final AttachmentStorage storage;
    private final AttachmentScanner scanner;
    private final AttachmentProperties properties;

    public AttachmentService(
            AttachmentRepository repository,
            AttachmentStorage storage,
            AttachmentScanner scanner,
            AttachmentProperties properties) {
        this.repository = repository;
        this.storage = storage;
        this.scanner = scanner;
        this.properties = properties;
    }

    @Transactional
    public AttachmentDtos.UploadSession createUpload(
            UUID conversationId,
            AttachmentDtos.CreateUploadRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireMember(subject, conversationId);
        repository.expirePending(subject.tenantId(), conversationId, subject.userId());
        AttachmentSecurity.ValidatedMetadata metadata = AttachmentSecurity.validate(
                request.filename(), request.contentType(), request.sizeBytes(),
                Math.min(
                        repository.maximumAttachmentMb(subject.tenantId()),
                        properties.maximumTransferMb()));
        UUID attachmentId = UUID.randomUUID();
        String uploadToken = AttachmentSecurity.newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.uploadTtl());
        String requestHash = AttachmentSecurity.requestHash(conversationId, metadata);
        String objectKey = subject.tenantId() + "/" + conversationId + "/" + attachmentId;
        AttachmentRepository.AttachmentRow row = repository.createOrReplay(
                subject.tenantId(), conversationId, subject.userId(), attachmentId,
                metadata, request.idempotencyKey(), requestHash, objectKey,
                AttachmentSecurity.hash(uploadToken), expiresAt);
        repository.audit(
                subject.tenantId(), subject.userId(), "messaging.attachment.upload-session-created",
                row.attachmentId(), correlationId);
        String uploadUrl = "QUARANTINED".equals(row.status())
                ? contentPath(conversationId, row.attachmentId()) + "?token=" + uploadToken
                : null;
        return new AttachmentDtos.UploadSession(row.summary(), uploadUrl, row.uploadExpiresAt());
    }

    @Transactional
    public AttachmentDtos.AttachmentSummary upload(
            UUID conversationId,
            UUID attachmentId,
            String token,
            byte[] content,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireMember(subject, conversationId);
        repository.expirePending(subject.tenantId(), conversationId, subject.userId());
        AttachmentRepository.AttachmentRow pending = repository.findOwned(
                        subject.tenantId(), conversationId, subject.userId(), attachmentId)
                .orElseThrow(this::notFound);
        if (!"QUARANTINED".equals(pending.status())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The upload session is no longer writable.");
        }
        if (token == null || token.length() != 64 || content == null
                || !constantTimeEquals(AttachmentSecurity.hash(token), pending.uploadTokenHash())
                || content.length != pending.sizeBytes()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The upload token or content length does not match the upload session.");
        }
        AttachmentRepository.AttachmentRow scanning = repository.beginScan(
                        subject.tenantId(), conversationId, subject.userId(), attachmentId,
                        AttachmentSecurity.hash(token), AttachmentSecurity.contentHash(content),
                        content.length, pending.version())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The upload session expired or was already consumed."));
        try {
            storage.store(scanning.objectKey(), content);
            AttachmentScanner.ScanResult result = scanner.scan(
                    new AttachmentScanner.ScanRequest(
                            scanning.normalizedFilename(), scanning.extension(),
                            scanning.declaredContentType()),
                    content);
            AttachmentRepository.AttachmentRow completed = repository.completeScan(
                    subject.tenantId(), attachmentId, scanning.version(), result);
            if (!result.clean()) storage.delete(completed.objectKey());
            repository.audit(
                    subject.tenantId(), subject.userId(),
                    result.clean() ? "messaging.attachment.scan-clean" : "messaging.attachment.scan-rejected",
                    attachmentId, correlationId);
            return completed.summary();
        } catch (RuntimeException exception) {
            deleteAfterFailedUpload(scanning.objectKey(), exception);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public AttachmentDtos.AttachmentSummary metadata(UUID conversationId, UUID attachmentId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireMember(subject, conversationId);
        AttachmentRepository.AttachmentRow row = repository.findVisible(
                        subject.tenantId(), conversationId, subject.userId(), attachmentId)
                .orElseThrow(this::notFound);
        if (row.uploaderUserId() != subject.userId()
                && (!"CLEAN".equals(row.status()) || row.messageId() == null)) {
            throw notFound();
        }
        return row.summary();
    }

    public void discard(UUID conversationId, UUID attachmentId, String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireMember(subject, conversationId);
        AttachmentRepository.AttachmentRow expired = repository.expireOwnedUnattached(
                        subject.tenantId(), conversationId, subject.userId(), attachmentId)
                .orElseThrow(this::notFound);
        storage.delete(expired.objectKey());
        repository.deleteExpired(attachmentId);
        repository.audit(
                subject.tenantId(), subject.userId(), "messaging.attachment.discarded",
                attachmentId, correlationId);
    }

    @Transactional
    public AttachmentDtos.DownloadGrant createDownloadGrant(
            UUID conversationId, UUID attachmentId, String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireMember(subject, conversationId);
        AttachmentRepository.AttachmentRow row = repository.findVisible(
                        subject.tenantId(), conversationId, subject.userId(), attachmentId)
                .orElseThrow(this::notFound);
        if (!"CLEAN".equals(row.status()) || row.messageId() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The attachment is not available until scanning and message delivery complete.");
        }
        String token = AttachmentSecurity.newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.downloadTtl());
        repository.createDownloadGrant(
                UUID.randomUUID(), attachmentId, subject.tenantId(), subject.userId(),
                AttachmentSecurity.hash(token), expiresAt);
        repository.audit(
                subject.tenantId(), subject.userId(), "messaging.attachment.download-granted",
                attachmentId, correlationId);
        return new AttachmentDtos.DownloadGrant(
                attachmentId, row.normalizedFilename(), resolvedContentType(row), row.sizeBytes(),
                contentPath(conversationId, attachmentId) + "?downloadToken=" + token,
                expiresAt);
    }

    @Transactional
    public DownloadedAttachment download(
            UUID conversationId, UUID attachmentId, String downloadToken, String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        if (downloadToken == null || downloadToken.length() != 64) throw notFound();
        AttachmentRepository.AttachmentRow row = repository.consumeDownloadGrant(
                        subject.tenantId(), conversationId, subject.userId(), attachmentId,
                        AttachmentSecurity.hash(downloadToken))
                .orElseThrow(this::notFound);
        byte[] content = storage.load(row.objectKey());
        if (content.length != row.sizeBytes()
                || row.contentSha256() == null
                || !AttachmentSecurity.contentHash(content).equals(row.contentSha256())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE, "The attachment failed its integrity check.");
        }
        repository.audit(
                subject.tenantId(), subject.userId(), "messaging.attachment.downloaded",
                attachmentId, correlationId);
        return new DownloadedAttachment(
                row.normalizedFilename(), resolvedContentType(row), content);
    }

    @Transactional
    public void requireAttachable(UUID conversationId, long userId, List<UUID> attachmentIds) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        List<UUID> ids = normalizedIds(attachmentIds);
        repository.requireAttachable(subject.tenantId(), conversationId, userId, ids);
    }

    public void attachToMessage(
            long tenantId,
            UUID conversationId,
            long userId,
            UUID messageId,
            List<UUID> attachmentIds) {
        repository.attachToMessage(
                tenantId, conversationId, userId, messageId, normalizedIds(attachmentIds));
    }

    private List<UUID> normalizedIds(List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
        if (attachmentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE
                || attachmentIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A message can contain up to ten unique attachments.");
        }
        return List.copyOf(attachmentIds);
    }

    private void requireMember(MessagingRequestContext.Subject subject, UUID conversationId) {
        if (!repository.activeMember(subject.tenantId(), conversationId, subject.userId())) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found.");
        }
    }

    private String resolvedContentType(AttachmentRepository.AttachmentRow row) {
        return row.detectedContentType() == null
                ? row.declaredContentType() : row.detectedContentType();
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private void deleteAfterFailedUpload(String objectKey, RuntimeException original) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private String contentPath(UUID conversationId, UUID attachmentId) {
        return "/api/messaging/v1/conversations/" + conversationId
                + "/attachments/" + attachmentId + "/content";
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The attachment was not found.");
    }

    public record DownloadedAttachment(String filename, String contentType, byte[] content) {
    }
}
