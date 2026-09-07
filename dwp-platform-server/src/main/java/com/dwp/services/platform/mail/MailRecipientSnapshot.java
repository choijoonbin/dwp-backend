package com.dwp.services.platform.mail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class MailRecipientSnapshot {

    private MailRecipientSnapshot() {
    }

    static String fingerprint(List<Map<String, Object>> recipients) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream canonical = new DataOutputStream(bytes)) {
                write(canonical, "MAIL_GROUP_RECIPIENT_SNAPSHOT_V1");
                canonical.writeInt(recipients.size());
                for (Map<String, Object> recipient : recipients) {
                    write(canonical, value(recipient, "name"));
                    write(canonical, value(recipient, "email").toLowerCase(java.util.Locale.ROOT));
                    write(canonical, value(recipient, "type"));
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint group recipients.", exception);
        }
    }

    private static String value(Map<String, Object> recipient, String key) {
        Object value = recipient.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Group recipient snapshot is missing " + key + ".");
        }
        return text.trim();
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
