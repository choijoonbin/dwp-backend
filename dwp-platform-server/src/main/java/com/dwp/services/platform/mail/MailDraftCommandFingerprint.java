package com.dwp.services.platform.mail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class MailDraftCommandFingerprint {

    String create(MailDtos.DraftSaveRequest request) {
        return fingerprint("CREATE", null, request);
    }

    String save(UUID threadId, MailDtos.DraftSaveRequest request) {
        return fingerprint("SAVE", threadId, request);
    }

    private String fingerprint(
            String commandType,
            UUID threadId,
            MailDtos.DraftSaveRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream canonical = new DataOutputStream(bytes)) {
                write(canonical, commandType);
                write(canonical, threadId == null ? null : threadId.toString());
                write(canonical, request.classification().name());
                write(canonical, String.valueOf(request.externalRecipientConfirmed()));
                write(canonical, email(request.toEmail()));
                write(canonical, recipientName(request.toName(), request.toEmail()));
                write(canonical, value(request.subject()));
                write(canonical, value(request.body()));
                writeComposeOptions(canonical, request.composeOptions());
                write(canonical, request.version() == null ? null : request.version().toString());
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint the mail draft command.", exception);
        }
    }

    private void writeComposeOptions(
            DataOutputStream canonical,
            MailWorkspaceDtos.ComposeOptions options) throws IOException {
        if (options == null) {
            canonical.writeBoolean(false);
            return;
        }
        canonical.writeBoolean(true);
        write(canonical, options.accountId() == null ? null : options.accountId().toString());
        List<MailWorkspaceDtos.Recipient> recipients = options.recipients().stream()
                .sorted(Comparator
                        .comparing((MailWorkspaceDtos.Recipient recipient) ->
                                recipient.type().name())
                        .thenComparing(recipient -> email(recipient.email()))
                        .thenComparing(recipient -> value(recipient.name())))
                .toList();
        canonical.writeInt(recipients.size());
        for (MailWorkspaceDtos.Recipient recipient : recipients) {
            write(canonical, recipient.type().name());
            write(canonical, email(recipient.email()));
            write(canonical, value(recipient.name()));
        }
        write(canonical, options.bodyFormat().name());
        List<UUID> attachments = options.attachmentIds().stream()
                .distinct().sorted().toList();
        canonical.writeInt(attachments.size());
        for (UUID attachment : attachments) write(canonical, attachment.toString());
        write(canonical, options.scheduledAt() == null
                ? null : options.scheduledAt().toInstant().toString());
        write(canonical, value(options.timeZone()));
        write(canonical, options.templateId() == null
                ? null : options.templateId().toString());
        write(canonical, options.signatureId() == null
                ? null : options.signatureId().toString());
    }

    private void write(DataOutputStream canonical, String value) throws IOException {
        if (value == null) {
            canonical.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        canonical.writeInt(encoded.length);
        canonical.write(encoded);
    }

    private String recipientName(String name, String email) {
        String normalizedName = value(name);
        return normalizedName.isBlank() ? email(email) : normalizedName;
    }

    private String email(String input) {
        return value(input).toLowerCase(Locale.ROOT);
    }

    private String value(String input) {
        return input == null ? "" : input.trim();
    }
}
