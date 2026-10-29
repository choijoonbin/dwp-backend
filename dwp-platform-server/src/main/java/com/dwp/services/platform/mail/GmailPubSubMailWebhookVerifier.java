package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fail-closed Gmail Pub/Sub push verifier backed by OIDC owner and hash-only ledger. */
@Component
class GmailPubSubMailWebhookVerifier implements MailWebhookVerificationPort {
    private static final String SUBSCRIPTION =
            "projects/[A-Za-z0-9._-]{1,200}/subscriptions/[A-Za-z0-9._-]{1,200}";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<GmailPushAuthenticationOwner> authenticationOwners;
    private final String expectedAudience;
    private final String topicName;

    GmailPubSubMailWebhookVerifier(
            JdbcTemplate jdbc, ObjectMapper mapper,
            List<GmailPushAuthenticationOwner> authenticationOwners,
            @Value("${dwp.platform.mail.providers.google-gmail.push-audience:}")
            String expectedAudience,
            @Value("${dwp.platform.mail.providers.google-gmail.topic-name:}")
            String topicName) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.authenticationOwners = authenticationOwners == null
                ? List.of() : List.copyOf(authenticationOwners);
        this.expectedAudience = expectedAudience == null ? "" : expectedAudience.trim();
        this.topicName = topicName == null ? "" : topicName.trim();
    }

    @Override public boolean supports(String providerType) {
        return "GOOGLE_GMAIL".equals(providerType);
    }

    @Override public Verification verify(Callback callback) {
        try {
            if (expectedAudience.isBlank() || topicName.isBlank())
                return rejected("GMAIL_PUSH_BINDING_UNAVAILABLE");
            List<GmailPushAuthenticationOwner> owners = authenticationOwners.stream()
                    .filter(owner -> owner.supports(callback.headers())).toList();
            if (owners.size() != 1) return rejected(
                    owners.isEmpty() ? "GMAIL_PUSH_AUTHENTICATOR_UNAVAILABLE"
                            : "GMAIL_PUSH_AUTHENTICATOR_AMBIGUOUS");
            GmailPushAuthenticationOwner.Authentication authentication = owners.get(0)
                    .verify(callback.headers(), expectedAudience);
            if (authentication == null || !authentication.verified()
                    || !expectedAudience.equals(authentication.audience()))
                return rejected("GMAIL_PUSH_AUTHENTICATION_FAILED");

            JsonNode envelope = mapper.readTree(callback.body());
            String subscription = text(envelope, "subscription");
            JsonNode message = envelope.path("message");
            String data = text(message, "data");
            String messageId = text(message, "messageId");
            if (subscription == null || !subscription.matches(SUBSCRIPTION)
                    || data == null || data.length() > 16_384
                    || messageId == null || messageId.length() > 500)
                return rejected("GMAIL_PUSH_ENVELOPE_INVALID");
            byte[] decoded = Base64.getUrlDecoder().decode(data);
            if (decoded.length == 0 || decoded.length > 8_192)
                return rejected("GMAIL_PUSH_DATA_LIMIT_EXCEEDED");
            JsonNode notification;
            try { notification = mapper.readTree(decoded); }
            finally { java.util.Arrays.fill(decoded, (byte) 0); }
            String email = text(notification, "emailAddress");
            String historyId = text(notification, "historyId");
            if (email == null || email.length() > 255
                    || historyId == null || !historyId.matches("[0-9]{1,40}"))
                return rejected("GMAIL_PUSH_DATA_INVALID");

            String subscriptionHash = sha256(subscription);
            String bindingHash = sha256(subscription + ':' + topicName);
            List<Ledger> ledgers = jdbc.query("""
                    SELECT subscription.tenant_id, subscription.connection_id
                      FROM mail_provider_subscriptions subscription
                      JOIN mail_provider_connections connection
                        ON connection.tenant_id = subscription.tenant_id
                       AND connection.connection_id = subscription.connection_id
                      JOIN mail_accounts account
                        ON account.tenant_id = subscription.tenant_id
                       AND account.connection_id = subscription.connection_id
                       AND account.account_id = subscription.account_id
                     WHERE subscription.provider_subscription_reference_hash = ?
                       AND subscription.client_state_hash = ?
                       AND subscription.subscription_state IN ('ACTIVE', 'RENEWAL_DUE')
                       AND subscription.expires_at > CURRENT_TIMESTAMP
                       AND connection.provider_type = 'GOOGLE_GMAIL'
                       AND connection.connection_state IN ('ACTIVE', 'DEGRADED')
                       AND account.connection_state = 'ACTIVE'
                       AND LOWER(account.email_address) = LOWER(?)
                    """, (result, ignored) -> new Ledger(result.getLong("tenant_id"),
                    result.getObject("connection_id", UUID.class)),
                    subscriptionHash, bindingHash, email);
            if (ledgers.size() != 1) return rejected(ledgers.isEmpty()
                    ? "GMAIL_PUSH_SUBSCRIPTION_REJECTED"
                    : "GMAIL_PUSH_SUBSCRIPTION_AMBIGUOUS");
            Ledger ledger = ledgers.getFirst();
            String eventHash = sha256(subscription + ':' + messageId + ':'
                    + email.toLowerCase() + ':' + historyId);
            return new Verification(true, "GOOGLE_GMAIL", ledger.tenantId(),
                    ledger.connectionId(), eventHash, bindingHash,
                    OffsetDateTime.now(ZoneOffset.UTC), "gmail-pubsub:" + bounded(messageId), null);
        } catch (RuntimeException | java.io.IOException invalid) {
            return rejected("GMAIL_PUSH_VERIFICATION_FAILED");
        }
    }

    private Verification rejected(String code) {
        return new Verification(false, "GOOGLE_GMAIL", 0, null,
                null, null, null, "", code);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isValueNode() || value.asText().isBlank()
                ? null : value.asText().trim();
    }

    private String bounded(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Ledger(long tenantId, UUID connectionId) { }
}
