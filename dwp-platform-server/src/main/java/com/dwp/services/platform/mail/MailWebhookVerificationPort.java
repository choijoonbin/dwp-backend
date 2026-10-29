package com.dwp.services.platform.mail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Provider-owned authenticity boundary. No verifier means fail closed. */
public interface MailWebhookVerificationPort {

    boolean supports(String providerType);

    Verification verify(Callback callback);

    final class Callback {
        private final String providerType;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        public Callback(
                String providerType,
                Map<String, List<String>> headers,
                byte[] body) {
            this.providerType = providerType;
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
            this.body = body == null ? new byte[0] : body.clone();
        }

        public String providerType() {
            return providerType;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "MailWebhookCallback[providerType=" + providerType
                    + ",headers=REDACTED,body=REDACTED]";
        }
    }

    record Verification(
            boolean verified,
            String providerType,
            long tenantId,
            UUID connectionId,
            String eventKeyHash,
            String clientStateHash,
            OffsetDateTime verifiedAt,
            String correlationId,
            String errorCode) {

        public Verification {
            if (verified) {
                if (providerType == null || providerType.isBlank()
                        || tenantId <= 0 || connectionId == null
                        || verifiedAt == null
                        || !sha256(eventKeyHash) || !sha256(clientStateHash)) {
                    throw new IllegalArgumentException(
                            "Verified webhook receipts require hashed identity evidence");
                }
            }
            correlationId = correlationId == null ? "" : correlationId.trim();
            errorCode = errorCode == null ? null : errorCode.trim();
        }

        private static boolean sha256(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }
    }
}
