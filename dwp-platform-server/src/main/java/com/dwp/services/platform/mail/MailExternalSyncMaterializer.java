package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Materializes one provider account batch and advances its cursor as one database commit. */
@Component
class MailExternalSyncMaterializer {

    private static final long MAX_STORED_ATTACHMENT_BYTES = 150L * 1024L * 1024L;
    private static final Set<String> CLASSIFICATIONS =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final AdminMailCompletionRepository repository;
    private final TenantMediaStorage storage;
    private final List<MailAttachmentScanner> scanners;
    private final MailInboundMessageService inboundMessages;
    private final MailMetrics metrics;
    private final MailExternalSyncTransactions transactions;
    private final MailProviderPayloadPolicy payloadPolicy;

    @Autowired
    MailExternalSyncMaterializer(
            AdminMailCompletionRepository repository,
            TenantMediaStorage storage,
            List<MailAttachmentScanner> scanners,
            MailInboundMessageService inboundMessages,
            MailMetrics metrics,
            MailExternalSyncTransactions transactions,
            MailProviderPayloadPolicy payloadPolicy) {
        this.repository = repository;
        this.storage = storage;
        this.scanners = scanners == null ? List.of() : List.copyOf(scanners);
        this.inboundMessages = inboundMessages;
        this.metrics = metrics;
        this.transactions = transactions;
        this.payloadPolicy = payloadPolicy;
    }

    MailExternalSyncMaterializer(
            AdminMailCompletionRepository repository,
            TenantMediaStorage storage,
            List<MailAttachmentScanner> scanners,
            MailInboundMessageService inboundMessages) {
        this(repository, storage, scanners, inboundMessages, null, null,
                MailProviderPayloadPolicy.defaults());
    }

    MailSyncResult materialize(
            long tenantId,
            long actorId,
            AdminMailCompletionRepository.AccountRow expectedAccount,
            MailConnectorPort.SyncBatch batch) {
        long startedAt = System.nanoTime();
        try {
            payloadPolicy.validate(batch);
            MailSyncResult result = materializeBatch(
                    tenantId, actorId, expectedAccount, batch);
            if (metrics != null) {
                metrics.sync(result, Duration.ofNanos(System.nanoTime() - startedAt));
            }
            return result;
        } catch (RuntimeException failure) {
            if (metrics != null) {
                metrics.syncFailure(Duration.ofNanos(System.nanoTime() - startedAt));
            }
            throw failure;
        }
    }

    private MailSyncResult materializeBatch(
            long tenantId,
            long actorId,
            AdminMailCompletionRepository.AccountRow expectedAccount,
            MailConnectorPort.SyncBatch batch) {
        if (batch.cursorResetRequired()) {
            throw failure("SYNC_CURSOR_RESET_REQUIRED");
        }
        AdminMailCompletionRepository.SyncAccountRow snapshot = repository
                .syncAccount(tenantId, expectedAccount.id())
                .orElseThrow(() -> failure("SYNC_ACCOUNT_UNAVAILABLE"));
        if (!Objects.equals(snapshot.cursor(), expectedAccount.cursor())
                || snapshot.cursorGeneration() != expectedAccount.cursorGeneration()) {
            throw failure("SYNC_CURSOR_CONFLICT");
        }

        List<String> storedReferences = new ArrayList<>();
        List<PreparedBatchMessage> preparedMessages = new ArrayList<>();
        int knownDuplicates = 0;
        try {
            for (MailConnectorPort.ProviderMessage providerMessage : batch.messages()) {
                if (repository.inboundMessage(
                        tenantId, snapshot.id(), providerMessage.providerThreadReference(),
                        providerMessage.providerMessageReference()).isPresent()) {
                    knownDuplicates++;
                    continue;
                }
                PreparedMessage prepared = prepareMessage(
                        tenantId, actorId, snapshot, providerMessage, storedReferences);
                preparedMessages.add(new PreparedBatchMessage(
                        providerMessage.providerFolderReference(), prepared));
            }
            int duplicatesBeforeCommit = knownDuplicates;
            CommitResult committed = inTransaction(() -> commitBatch(
                    tenantId, actorId, expectedAccount, batch,
                    preparedMessages, duplicatesBeforeCommit));
            cleanup(tenantId, committed.orphanedReferences());
            return new MailSyncResult(
                    committed.inserted(), committed.duplicates(),
                    committed.blockedAttachments(), batch.partial());
        } catch (RuntimeException failure) {
            cleanup(tenantId, storedReferences);
            throw failure;
        }
    }

    private CommitResult commitBatch(
            long tenantId,
            long actorId,
            AdminMailCompletionRepository.AccountRow expectedAccount,
            MailConnectorPort.SyncBatch batch,
            List<PreparedBatchMessage> preparedMessages,
            int knownDuplicates) {
        AdminMailCompletionRepository.SyncAccountRow account = repository
                .lockSyncAccount(tenantId, expectedAccount.id())
                .orElseThrow(() -> failure("SYNC_ACCOUNT_UNAVAILABLE"));
        if (!Objects.equals(account.cursor(), expectedAccount.cursor())
                || account.cursorGeneration() != expectedAccount.cursorGeneration()) {
            throw failure("SYNC_CURSOR_CONFLICT");
        }
        int inserted = 0;
        int duplicate = knownDuplicates;
        int blocked = 0;
        List<String> orphanedReferences = new ArrayList<>();
        for (PreparedBatchMessage candidate : preparedMessages) {
            PreparedMessage prepared = candidate.prepared();
            AdminMailCompletionRepository.InboundMessageRow message = prepared.message();
            if (repository.inboundMessage(
                    tenantId, account.id(), message.providerThreadReference(),
                    message.providerMessageReference()).isPresent()) {
                orphanedReferences.addAll(prepared.storedReferences());
                duplicate++;
                continue;
            }
            UUID folderId = repository.synchronizationFolder(
                    tenantId, account.id(), candidate.providerFolderReference())
                    .orElseThrow(() -> failure("SYNC_FOLDER_UNAVAILABLE"));
            AdminMailCompletionRepository.InboundMaterialized materialized =
                    repository.materializeInboundMessage(
                            tenantId, actorId, account, folderId,
                            message, prepared.attachments());
            if (!materialized.inserted()) {
                orphanedReferences.addAll(prepared.storedReferences());
                duplicate++;
                continue;
            }
            inserted++;
            blocked += prepared.blockedAttachments();
            inboundMessages.inboundMessageMaterialized(
                    tenantId, materialized.threadId(), materialized.messageId(),
                    message.occurredAt());
        }
        if (repository.updateAccountSyncCursor(
                tenantId, account.id(), expectedAccount.cursor(),
                expectedAccount.cursorGeneration(), batch.nextCursor(), actorId) != 1) {
            throw failure("SYNC_CURSOR_CONFLICT");
        }
        return new CommitResult(inserted, duplicate, blocked, List.copyOf(orphanedReferences));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions == null ? work.get() : transactions.commit(work);
    }

    private PreparedMessage prepareMessage(
            long tenantId,
            long actorId,
            AdminMailCompletionRepository.SyncAccountRow account,
            MailConnectorPort.ProviderMessage providerMessage,
            List<String> transactionStoredReferences) {
        LinkedHashMap<String, PreparedAttachment> uniqueAttachments = new LinkedHashMap<>();
        for (MailConnectorPort.ProviderAttachment attachment : providerMessage.attachments()) {
            uniqueAttachments.computeIfAbsent(
                    attachment.providerAttachmentReference(), ignored -> prepareAttachment(
                            tenantId, actorId, account.id(),
                            providerMessage.providerMessageReference(), attachment,
                            transactionStoredReferences));
        }
        List<AdminMailCompletionRepository.InboundAttachmentRow> rows = uniqueAttachments.values()
                .stream().map(PreparedAttachment::row).toList();
        List<Map<String, Object>> projection = uniqueAttachments.values().stream()
                .map(PreparedAttachment::projection).toList();
        Sender sender = sender(providerMessage);
        List<Map<String, Object>> recipients = recipients(providerMessage.recipients());
        List<Map<String, Object>> participants = new ArrayList<>();
        participants.add(Map.of("name", sender.name(), "email", sender.email()));
        participants.addAll(recipients);
        String subject = bounded(
                blankTo(providerMessage.subject(), "(no subject)"), 500);
        String body = providerMessage.body() == null ? "" : providerMessage.body();
        String preview = preview(body);
        String classification = providerMessage.metadata()
                .getOrDefault("classification", "INTERNAL")
                .trim().toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(classification)) classification = "INTERNAL";
        AdminMailCompletionRepository.InboundMessageRow message =
                new AdminMailCompletionRepository.InboundMessageRow(
                        providerMessage.providerMessageReference(),
                        providerMessage.providerThreadReference(),
                        providerTime(providerMessage), sender.email(), sender.name(),
                        recipients, subject, body, providerMessage.bodyFormat().name(),
                        preview, List.copyOf(participants),
                        externalSender(sender.email(), account.email()),
                        classification, projection);
        List<String> storedForMessage = uniqueAttachments.values().stream()
                .map(PreparedAttachment::storedReference)
                .filter(Objects::nonNull).toList();
        int blocked = (int) uniqueAttachments.values().stream()
                .filter(attachment -> !"READY".equals(attachment.row().scanState()))
                .count();
        return new PreparedMessage(message, rows, storedForMessage, blocked);
    }

    private PreparedAttachment prepareAttachment(
            long tenantId,
            long actorId,
            UUID accountId,
            String providerMessageReference,
            MailConnectorPort.ProviderAttachment attachment,
            List<String> transactionStoredReferences) {
        UUID attachmentId = UUID.nameUUIDFromBytes((tenantId + ":" + accountId + ":"
                + providerMessageReference + ":" + attachment.providerAttachmentReference())
                .getBytes(StandardCharsets.UTF_8));
        String declaredChecksum = attachment.checksumSha256();
        boolean declaredChecksumValid = declaredChecksum == null
                || declaredChecksum.matches("[0-9a-fA-F]{64}");
        if (declaredChecksumValid && declaredChecksum != null) {
            declaredChecksum = declaredChecksum.toLowerCase(Locale.ROOT);
        }
        String scanState = "BLOCKED";
        String scanEvidence;
        String storedReference = null;
        byte[] content = attachment.content();
        String checksum = content == null
                ? declaredChecksumValid && declaredChecksum != null
                    ? declaredChecksum
                    : sha256((attachment.providerAttachmentReference() + ':'
                            + blankTo(attachment.contentReference(), "unavailable"))
                            .getBytes(StandardCharsets.UTF_8))
                : sha256(content);
        String storageReference = "provider-blocked:" + checksum;
        if (!declaredChecksumValid) {
            scanEvidence = "PROVIDER_ATTACHMENT_CHECKSUM_INVALID";
        } else if (content != null && declaredChecksum != null
                && !declaredChecksum.equals(checksum)) {
            scanEvidence = "PROVIDER_ATTACHMENT_CHECKSUM_MISMATCH";
        } else if (attachment.sizeBytes() > MAX_STORED_ATTACHMENT_BYTES) {
            scanEvidence = "PROVIDER_ATTACHMENT_EXCEEDS_STORAGE_LIMIT";
        } else if (content == null) {
            scanEvidence = attachment.contentReference() == null
                    ? "PROVIDER_CONTENT_UNAVAILABLE"
                    : "PROVIDER_CONTENT_NOT_FETCHED";
        } else if (scanners.isEmpty()) {
            scanEvidence = "TRUSTED_ATTACHMENT_SCANNER_UNAVAILABLE";
        } else {
            ScanEvidence scan = scan(
                    tenantId, actorId, attachmentId, attachment, checksum, content);
            if (!scan.clean()) {
                scanEvidence = scan.evidence();
            } else {
                try {
                    storedReference = storage.store(
                            tenantId, "mail/provider-sync/" + accountId,
                            extension(attachment.fileName()), content);
                    transactionStoredReferences.add(storedReference);
                    storageReference = storedReference;
                    scanState = "READY";
                    scanEvidence = scan.evidence();
                } catch (RuntimeException storageFailure) {
                    scanEvidence = "PROVIDER_ATTACHMENT_STORAGE_FAILED";
                }
            }
        }
        scanEvidence = bounded(scanEvidence, 320);
        String fileName = bounded(attachment.fileName(), 255);
        String contentType = bounded(attachment.contentType(), 160);
        AdminMailCompletionRepository.InboundAttachmentRow row =
                new AdminMailCompletionRepository.InboundAttachmentRow(
                        attachmentId, bounded(storageReference, 1000), fileName,
                        contentType, Math.min(attachment.sizeBytes(),
                        MAX_STORED_ATTACHMENT_BYTES), checksum, scanState, scanEvidence);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("attachmentId", attachmentId);
        projection.put("providerAttachmentReferenceHash", sha256(
                attachment.providerAttachmentReference().getBytes(StandardCharsets.UTF_8)));
        projection.put("providerContentReferenceAvailable",
                attachment.contentReference() != null);
        if (attachment.contentReference() != null) {
            projection.put("contentReferenceHash", sha256(
                    attachment.contentReference().getBytes(StandardCharsets.UTF_8)));
        }
        projection.put("fileName", fileName);
        projection.put("contentType", contentType);
        projection.put("sizeBytes", attachment.sizeBytes());
        projection.put("checksumSha256", checksum);
        projection.put("contentAvailable", "READY".equals(scanState));
        projection.put("scanState", scanState);
        projection.put("scanEvidence", scanEvidence);
        return new PreparedAttachment(row, Map.copyOf(projection), storedReference);
    }

    private ScanEvidence scan(
            long tenantId,
            long actorId,
            UUID attachmentId,
            MailConnectorPort.ProviderAttachment attachment,
            String checksum,
            byte[] content) {
        List<String> evidence = new ArrayList<>();
        for (MailAttachmentScanner scanner : scanners) {
            MailAttachmentScanner.ScanResult result;
            try {
                result = scanner.scan(new MailAttachmentScanner.ScanRequest(
                        tenantId, actorId, attachmentId, attachment.fileName(),
                        attachment.contentType(), checksum, content));
            } catch (RuntimeException scannerFailure) {
                return new ScanEvidence(false, "TRUSTED_ATTACHMENT_SCAN_FAILED");
            }
            if (result == null) {
                return new ScanEvidence(false, "TRUSTED_ATTACHMENT_SCAN_FAILED");
            }
            if (result.verdict() != MailAttachmentScanner.Verdict.CLEAN) {
                return new ScanEvidence(false, "TRUSTED_ATTACHMENT_SCAN_REJECTED:"
                        + blankTo(result.evidence(), "NO_EVIDENCE"));
            }
            evidence.add(result.evidence());
        }
        return new ScanEvidence(true, "CLEAN:" + String.join(";", evidence));
    }

    private Sender sender(MailConnectorPort.ProviderMessage message) {
        String raw = blankTo(message.sender(), "unknown@invalid.local").trim();
        String metadataEmail = message.metadata().get("senderEmail");
        String email = metadataEmail == null ? raw : metadataEmail.trim();
        String name = message.metadata().get("senderName");
        int open = raw.lastIndexOf('<');
        int close = raw.lastIndexOf('>');
        if (open >= 0 && close > open) {
            if (metadataEmail == null) email = raw.substring(open + 1, close).trim();
            if (name == null) name = raw.substring(0, open).trim().replace("\"", "");
        }
        email = bounded(blankTo(email, "unknown@invalid.local").toLowerCase(Locale.ROOT), 255);
        name = bounded(blankTo(name, email), 160);
        return new Sender(email, name);
    }

    private List<Map<String, Object>> recipients(List<String> rawRecipients) {
        List<Map<String, Object>> recipients = new ArrayList<>();
        for (String rawValue : rawRecipients) {
            if (rawValue == null || rawValue.isBlank()) continue;
            String raw = rawValue.trim();
            String email = raw;
            String name = raw;
            int open = raw.lastIndexOf('<');
            int close = raw.lastIndexOf('>');
            if (open >= 0 && close > open) {
                email = raw.substring(open + 1, close).trim();
                name = raw.substring(0, open).trim().replace("\"", "");
            }
            recipients.add(Map.of(
                    "type", "TO",
                    "name", bounded(blankTo(name, email), 160),
                    "email", bounded(email.toLowerCase(Locale.ROOT), 255)));
        }
        return List.copyOf(recipients);
    }

    private boolean externalSender(String senderEmail, String accountEmail) {
        return !domain(senderEmail).equals(domain(accountEmail));
    }

    private String domain(String email) {
        int separator = email.lastIndexOf('@');
        return separator < 0 ? "" : email.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private OffsetDateTime providerTime(MailConnectorPort.ProviderMessage message) {
        return OffsetDateTime.ofInstant(message.occurredAt(), ZoneOffset.UTC);
    }

    private String preview(String body) {
        String value = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return bounded(value, 1200);
    }

    private String extension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        if (separator < 0 || separator == fileName.length() - 1) return "bin";
        String extension = fileName.substring(separator + 1)
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return extension.isEmpty() ? "bin" : bounded(extension, 8);
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maximum ? trimmed : trimmed.substring(0, maximum);
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void cleanup(long tenantId, List<String> references) {
        for (String reference : List.copyOf(references)) {
            try {
                storage.delete(tenantId, reference);
            } catch (RuntimeException ignored) {
                // Orphan cleanup is handled by tenant-media retention.
            }
        }
    }

    private MailSyncMaterializationFailure failure(String code) {
        return new MailSyncMaterializationFailure(code);
    }

    private record Sender(String email, String name) { }
    private record ScanEvidence(boolean clean, String evidence) { }
    private record PreparedAttachment(
            AdminMailCompletionRepository.InboundAttachmentRow row,
            Map<String, Object> projection,
            String storedReference) { }
    private record PreparedMessage(
            AdminMailCompletionRepository.InboundMessageRow message,
            List<AdminMailCompletionRepository.InboundAttachmentRow> attachments,
            List<String> storedReferences,
            int blockedAttachments) { }
    private record PreparedBatchMessage(
            String providerFolderReference,
            PreparedMessage prepared) { }
    private record CommitResult(
            int inserted,
            int duplicates,
            int blockedAttachments,
            List<String> orphanedReferences) { }
}
