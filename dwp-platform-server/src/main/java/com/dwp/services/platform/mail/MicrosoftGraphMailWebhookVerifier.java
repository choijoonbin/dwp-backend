package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates normal Graph notifications against hash-only subscription ledger evidence. */
@Component
class MicrosoftGraphMailWebhookVerifier implements MailWebhookVerificationPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    MicrosoftGraphMailWebhookVerifier(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public boolean supports(String providerType) {
        return "MICROSOFT_GRAPH".equals(providerType);
    }

    @Override public Verification verify(Callback callback) {
        try {
            JsonNode root = mapper.readTree(callback.body());
            JsonNode values = root.path("value");
            if (!values.isArray() || values.isEmpty() || values.size() > 500)
                return rejected("GRAPH_NOTIFICATION_INVALID");
            String subscriptionId = null;
            String clientState = null;
            List<String> eventParts = new ArrayList<>();
            for (JsonNode value : values) {
                String nextSubscription = text(value, "subscriptionId");
                String nextState = text(value, "clientState");
                String change = text(value, "changeType");
                String resource = text(value, "resource");
                if (nextSubscription == null || nextState == null || change == null
                        || resource == null || nextSubscription.length() > 500
                        || nextState.length() > 500 || resource.length() > 2_000)
                    return rejected("GRAPH_NOTIFICATION_INVALID");
                if (subscriptionId == null) {
                    subscriptionId = nextSubscription;
                    clientState = nextState;
                } else if (!subscriptionId.equals(nextSubscription)
                        || !clientState.equals(nextState)) {
                    return rejected("GRAPH_NOTIFICATION_MIXED_SUBSCRIPTIONS");
                }
                eventParts.add(change + ':' + resource + ':'
                        + value.path("resourceData").path("id").asText(""));
            }
            String subscriptionHash = sha256(subscriptionId);
            String stateHash = sha256(clientState);
            List<Ledger> ledgers = jdbc.query("""
                    SELECT subscription.tenant_id, subscription.connection_id
                      FROM mail_provider_subscriptions subscription
                      JOIN mail_provider_connections connection
                        ON connection.tenant_id = subscription.tenant_id
                       AND connection.connection_id = subscription.connection_id
                     WHERE subscription.provider_subscription_reference_hash = ?
                       AND subscription.client_state_hash = ?
                       AND subscription.subscription_state IN ('ACTIVE', 'RENEWAL_DUE')
                       AND subscription.expires_at > CURRENT_TIMESTAMP
                       AND connection.provider_type = 'MICROSOFT_GRAPH'
                       AND connection.connection_state IN ('ACTIVE', 'DEGRADED')
                    """, (result, ignored) -> new Ledger(result.getLong("tenant_id"),
                    result.getObject("connection_id", UUID.class)),
                    subscriptionHash, stateHash);
            if (ledgers.size() != 1) return rejected(ledgers.isEmpty()
                    ? "GRAPH_CLIENT_STATE_REJECTED"
                    : "GRAPH_SUBSCRIPTION_AMBIGUOUS");
            Ledger ledger = ledgers.getFirst();
            eventParts.sort(Comparator.naturalOrder());
            String eventHash = sha256(subscriptionId + ':' + String.join("|", eventParts));
            return new Verification(true, "MICROSOFT_GRAPH", ledger.tenantId(),
                    ledger.connectionId(), eventHash, stateHash,
                    OffsetDateTime.now(ZoneOffset.UTC), correlation(callback.headers()), null);
        } catch (RuntimeException | java.io.IOException invalid) {
            return rejected("GRAPH_NOTIFICATION_INVALID");
        }
    }

    private Verification rejected(String code) {
        return new Verification(false, "MICROSOFT_GRAPH", 0, null,
                null, null, null, "", code);
    }

    private String correlation(Map<String, List<String>> headers) {
        for (String name : List.of("client-request-id", "request-id", "x-correlation-id")) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    String value = entry.getValue().get(0);
                    if (value != null && value.matches("[A-Za-z0-9._:-]{1,160}")) return value;
                }
            }
        }
        return "graph-webhook";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.textValue().isBlank()
                ? null : value.textValue().trim();
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
