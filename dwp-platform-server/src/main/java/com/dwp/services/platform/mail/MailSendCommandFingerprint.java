package com.dwp.services.platform.mail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

final class MailSendCommandFingerprint {

    String compose(Long userId, MailDtos.ComposeRequest request) {
        return digest(
                "COMPOSE",
                Long.toString(userId),
                request.deliveryMode().name(),
                request.classification().name(),
                String.valueOf(request.externalRecipientConfirmed()),
                email(request.toEmail()),
                recipientName(request.toName(), request.toEmail()),
                value(request.subject()),
                value(request.body()));
    }

    String reply(Long userId, UUID threadId, MailDtos.ReplyRequest request) {
        ArrayList<String> values = new ArrayList<>();
        values.add("REPLY_SEND");
        values.add(Long.toString(userId));
        values.add(threadId.toString());
        values.add(request.mode() == null || request.mode().isBlank()
                ? "REPLY" : request.mode().trim().toUpperCase(Locale.ROOT));
        values.add(value(request.body()));
        if (request.recipients() != null) {
            for (MailWorkspaceDtos.Recipient recipient : request.recipients()) {
                values.add(recipient.type().name());
                values.add(recipientName(recipient.name(), recipient.email()));
                values.add(email(recipient.email()));
            }
        }
        return digest(values.toArray(String[]::new));
    }

    String draftSend(Long userId, UUID threadId, MailDtos.DraftUpdateRequest request) {
        return digest(
                "DRAFT_SEND",
                Long.toString(userId),
                threadId.toString(),
                Long.toString(request.version()),
                request.classification().name(),
                String.valueOf(request.externalRecipientConfirmed()),
                email(request.toEmail()),
                recipientName(request.toName(), request.toEmail()),
                value(request.subject()),
                value(request.body()));
    }

    private String digest(String... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream canonical = new DataOutputStream(bytes)) {
                for (String value : values) write(canonical, value);
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint the mail send command.", exception);
        }
    }

    private void write(DataOutputStream canonical, String value) throws IOException {
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
