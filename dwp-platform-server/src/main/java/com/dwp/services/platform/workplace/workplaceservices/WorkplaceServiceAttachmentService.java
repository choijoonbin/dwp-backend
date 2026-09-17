package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

@Service
public class WorkplaceServiceAttachmentService {
    private static final long MAX_BYTES = 26_214_400L;

    private final WorkplaceServicesService services;
    private final WorkplaceServicesRepository repository;
    private final TenantMediaStorage storage;
    private final Clock clock;

    @Autowired
    public WorkplaceServiceAttachmentService(
            WorkplaceServicesService services,
            WorkplaceServicesRepository repository,
            TenantMediaStorage storage) {
        this(services, repository, storage, Clock.systemUTC());
    }

    WorkplaceServiceAttachmentService(
            WorkplaceServicesService services,
            WorkplaceServicesRepository repository,
            TenantMediaStorage storage,
            Clock clock) {
        this.services = services;
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public AttachmentContent download(
            long tenantId, long actorUserId, UUID orderId, UUID attachmentId,
            boolean administrator, String correlationId) {
        if (tenantId < 1 || actorUserId < 1) {
            throw invalid("A valid attachment viewer is required.");
        }
        if (administrator) {
            services.adminOrder(tenantId, orderId);
        } else {
            services.ownOrder(tenantId, actorUserId, orderId);
        }
        var attachment = repository.attachment(tenantId, orderId, attachmentId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The service attachment was not found."));
        if (attachment.scanState() != AttachmentScanState.CLEAN) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The service attachment is quarantined until a clean scan is recorded.");
        }
        Resource resource = storage.load(tenantId, attachment.storageReference());
        repository.appendAttachmentAccessAudit(tenantId, actorUserId, orderId, attachmentId,
                administrator, normalizedCorrelation(correlationId));
        return new AttachmentContent(resource,
                attachment.fileName(), attachment.contentType(), attachment.byteSize(),
                attachment.checksumSha256());
    }

    private static String normalizedCorrelation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (normalized.length() > 160) throw invalid("The correlation id is too long.");
        return normalized;
    }

    public ServiceOrderCommandResult upload(
            long tenantId, long actorUserId, UUID orderId, boolean administrator,
            String idempotencyKey, long expectedVersion, String reason,
            MultipartFile file, String correlationId) {
        ValidatedAttachment validated = validate(file);
        AtomicReference<String> stored = new AtomicReference<>();
        try {
            return services.linkAttachment(tenantId, actorUserId, orderId, administrator,
                    idempotencyKey, expectedVersion, reason,
                    new AttachmentUpload(validated.fileName(), validated.contentType(),
                            validated.content().length, validated.checksumSha256()),
                    () -> {
                        String key = storage.store(tenantId,
                                "workplace/service-orders/" + orderId,
                                validated.extension(), validated.content());
                        stored.set(key);
                        return key;
                    }, correlationId);
        } catch (RuntimeException failure) {
            if (stored.get() != null) {
                try {
                    storage.delete(tenantId, stored.get());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Transactional
    public AttachmentScanCommandResult recordScan(
            long tenantId, long actorUserId, UUID orderId, UUID attachmentId,
            String idempotencyKey, AttachmentScanRequest request, String correlationId) {
        if (tenantId < 1 || actorUserId < 1) throw invalid("A valid scan actor is required.");
        services.adminOrder(tenantId, orderId);
        validateScanRequest(request);
        String key = idempotencyKey(idempotencyKey);
        String scope = "SERVICE_ATTACHMENT_SCAN:" + attachmentId;
        repository.lockIdempotencyCommand(tenantId, actorUserId, scope, key);
        String fingerprint = scanFingerprint(orderId, attachmentId, request);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return scanReplay(
                tenantId, orderId, attachmentId, fingerprint, existing);

        AttachmentRow current = repository.attachmentForUpdate(tenantId, orderId, attachmentId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The service attachment was not found."));
        existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return scanReplay(
                tenantId, orderId, attachmentId, fingerprint, existing);
        if (current.scanVersion() != request.expectedVersion()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The attachment scan state changed. Refresh before recording the verdict.");
        }
        AttachmentScanState state = switch (request.verdict()) {
            case CLEAN -> AttachmentScanState.CLEAN;
            case INFECTED -> AttachmentScanState.INFECTED;
            case ERROR -> AttachmentScanState.ERROR;
        };
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!repository.updateAttachmentScan(tenantId, orderId, attachmentId,
                request.expectedVersion(), state, request.scannerEvidenceReference().trim(),
                normalize(request.detail()), now)) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The attachment scan state changed. Refresh before recording the verdict.");
        }
        AttachmentRow updated = repository.attachment(tenantId, orderId, attachmentId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The service attachment was not found."));
        String correlation = normalizedCorrelation(correlationId);
        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("attachmentId", attachmentId.toString())
                .put("scanState", state.name())
                .put("scanVersion", updated.scanVersion())
                .put("scannerEvidenceReference", request.scannerEvidenceReference().trim())
                .put("reason", request.reason().trim());
        repository.appendEvent(tenantId, orderId, "ATTACHMENT_SCAN_UPDATED",
                actorUserId, detail, now);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.attachment.scan.updated",
                "ServiceOrderAttachmentScanUpdated", correlation, detail, now);
        String href = "/v1/admin/workplace/service-orders/" + orderId
                + "/attachments/" + attachmentId + "/scan-status";
        CommandRow command = new CommandRow(UUID.randomUUID(), tenantId, actorUserId,
                scope, key, fingerprint, orderId, CommandState.SUCCEEDED, href,
                correlation, now, now);
        repository.createCommand(command);
        return new AttachmentScanCommandResult(attachment(updated), receipt(command, false));
    }

    @Transactional(readOnly = true)
    public ServiceOrderAttachment scanStatus(
            long tenantId, UUID orderId, UUID attachmentId) {
        if (tenantId < 1) throw invalid("A valid tenant is required.");
        services.adminOrder(tenantId, orderId);
        return repository.attachment(tenantId, orderId, attachmentId)
                .map(WorkplaceServiceAttachmentService::attachment)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The service attachment was not found."));
    }

    private ValidatedAttachment validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() < 1 || file.getSize() > MAX_BYTES) {
            throw invalid("A service attachment of at most 25 MiB is required.");
        }
        try {
            byte[] content = file.getBytes();
            MediaType media = detect(content);
            if (!media.contentType().equals(file.getContentType())) {
                throw invalid("The declared attachment type does not match its content.");
            }
            String fileName = safeFileName(file.getOriginalFilename());
            return new ValidatedAttachment(fileName, media.contentType(), media.extension(),
                    content, sha256(content));
        } catch (IOException failure) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The service attachment could not be read.", failure);
        }
    }

    private static MediaType detect(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xff) == 0x89
                && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47
                && content[4] == 0x0d && content[5] == 0x0a
                && content[6] == 0x1a && content[7] == 0x0a) {
            requirePngStructure(content);
            return new MediaType("image/png", "png");
        }
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            requireJpegStructure(content);
            return new MediaType("image/jpeg", "jpg");
        }
        if (content.length >= 5
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D'
                && content[3] == 'F' && content[4] == '-') {
            requirePdfStructure(content);
            return new MediaType("application/pdf", "pdf");
        }
        throw invalid("Only verified PDF, PNG, and JPEG service attachments are supported.");
    }

    private static String safeFileName(String candidate) {
        if (candidate == null) throw invalid("The attachment file name is required.");
        String normalized = candidate.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isEmpty() || normalized.length() > 255
                || normalized.chars().anyMatch(value -> Character.isISOControl(value))) {
            throw invalid("The attachment file name is invalid.");
        }
        return normalized;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private AttachmentScanCommandResult scanReplay(
            long tenantId, UUID orderId, UUID attachmentId,
            String fingerprint, CommandRow command) {
        if (!command.fingerprint().equals(fingerprint)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different scan result.");
        }
        AttachmentRow current = repository.attachment(tenantId, orderId, attachmentId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The service attachment was not found."));
        return new AttachmentScanCommandResult(attachment(current), receipt(command, true));
    }

    private static CommandReceipt receipt(CommandRow command, boolean replayed) {
        return new CommandReceipt(command.commandId(), command.orderId(), command.state(),
                command.statusHref(), replayed, command.correlationId(), command.createdAt());
    }

    private static ServiceOrderAttachment attachment(AttachmentRow row) {
        return new ServiceOrderAttachment(row.attachmentId(), row.fileName(), row.contentType(),
                row.byteSize(), row.scanState(), row.scanVersion(),
                row.scannerEvidenceReference(), row.scanDetail(), row.scannedAt(), row.createdAt());
    }

    private static void validateScanRequest(AttachmentScanRequest request) {
        if (request == null || request.expectedVersion() < 1 || request.verdict() == null
                || !request.explicitConfirmation() || blank(request.scannerEvidenceReference())
                || blank(request.reason())) {
            throw invalid("Expected version, scanner evidence, confirmation, and reason are required.");
        }
        if (request.scannerEvidenceReference().trim().length() > 320
                || (normalize(request.detail()) != null
                    && normalize(request.detail()).length() > 1_000)
                || request.reason().trim().length() > 500) {
            throw invalid("Scanner evidence, detail, or reason exceeds the allowed length.");
        }
    }

    private static String idempotencyKey(String value) {
        if (blank(value) || value.trim().length() > 160) {
            throw invalid("A valid idempotency key is required.");
        }
        return value.trim();
    }

    private static String scanFingerprint(
            UUID orderId, UUID attachmentId, AttachmentScanRequest request) {
        String value = String.join("\u0000", orderId.toString(), attachmentId.toString(),
                Long.toString(request.expectedVersion()), request.verdict().name(),
                request.scannerEvidenceReference().trim(),
                normalize(request.detail()) == null ? "" : normalize(request.detail()),
                Boolean.toString(request.explicitConfirmation()), request.reason().trim());
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requirePngStructure(byte[] content) {
        int offset = 8;
        boolean header = false;
        boolean end = false;
        while (offset + 12 <= content.length) {
            long length = uint32(content, offset);
            if (length > Integer.MAX_VALUE || offset + 12L + length > content.length) {
                throw invalid("The PNG attachment structure is invalid.");
            }
            String type = new String(content, offset + 4, 4, StandardCharsets.US_ASCII);
            if (!header) {
                if (!"IHDR".equals(type) || length != 13 || offset != 8
                        || uint32(content, offset + 8) == 0
                        || uint32(content, offset + 12) == 0) {
                    throw invalid("The PNG attachment header is invalid.");
                }
                header = true;
            }
            offset += 12 + (int) length;
            if ("IEND".equals(type)) {
                end = length == 0 && offset == content.length;
                break;
            }
        }
        if (!header || !end) throw invalid("The PNG attachment is incomplete.");
    }

    private static void requireJpegStructure(byte[] content) {
        if (content.length < 12 || (content[content.length - 2] & 0xff) != 0xff
                || (content[content.length - 1] & 0xff) != 0xd9) {
            throw invalid("The JPEG attachment is incomplete.");
        }
        boolean frame = false;
        for (int index = 2; index + 8 < content.length - 2; index++) {
            if ((content[index] & 0xff) == 0xff
                    && ((content[index + 1] & 0xff) == 0xc0
                        || (content[index + 1] & 0xff) == 0xc2)) {
                int length = ushort(content, index + 2);
                int height = ushort(content, index + 5);
                int width = ushort(content, index + 7);
                frame = length >= 8 && width > 0 && height > 0;
                break;
            }
        }
        if (!frame) throw invalid("The JPEG attachment has no valid image frame.");
    }

    private static void requirePdfStructure(byte[] content) {
        String text = new String(content, StandardCharsets.ISO_8859_1);
        int tail = Math.max(0, text.length() - 2048);
        boolean catalog = text.contains("/Type /Catalog") || text.contains("/Type/Catalog");
        if (!catalog || text.indexOf("%%EOF", tail) < 0) {
            throw invalid("The PDF attachment structure is incomplete.");
        }
    }

    private static long uint32(byte[] value, int offset) {
        return ((long) value[offset] & 0xff) << 24
                | ((long) value[offset + 1] & 0xff) << 16
                | ((long) value[offset + 2] & 0xff) << 8
                | ((long) value[offset + 3] & 0xff);
    }

    private static int ushort(byte[] value, int offset) {
        return (value[offset] & 0xff) << 8 | (value[offset + 1] & 0xff);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record MediaType(String contentType, String extension) { }

    private record ValidatedAttachment(
            String fileName,
            String contentType,
            String extension,
            byte[] content,
            String checksumSha256) { }

    public record AttachmentContent(
            Resource resource,
            String fileName,
            String contentType,
            long byteSize,
            String checksumSha256) { }
}
