package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailProviderException;
import com.dwp.platform.contract.MailSyncProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real Microsoft Graph Mail adapter. */
@Component
@ConditionalOnProperty(name = "dwp.platform.mail.providers.microsoft-graph.enabled",
        havingValue = "true")
class MicrosoftGraphMailConnector implements MailConnectorPort {
    private static final ProviderFamily PROVIDER = ProviderFamily.MICROSOFT_GRAPH;
    private static final long MAXIMUM_FETCHED_ATTACHMENT_BYTES = 5L * 1024L * 1024L;
    private final MailProviderTokenBroker tokens;
    private final MailProviderHttpTransport http;
    private final Clock clock;
    private final SecureRandom random;

    MicrosoftGraphMailConnector(
            RestClient.Builder builder, ObjectMapper mapper, MailProviderTokenBroker tokens,
            @Value("${dwp.platform.mail.providers.microsoft-graph.base-url:https://graph.microsoft.com}")
            String baseUrl,
            @Value("${dwp.platform.mail.providers.microsoft-graph.allowed-hosts:graph.microsoft.com}")
            String allowedHosts,
            @Value("${dwp.platform.mail.providers.microsoft-graph.allow-insecure-localhost:false}")
            boolean allowLocal,
            @Value("${dwp.platform.mail.providers.microsoft-graph.maximum-response-bytes:8388608}")
            int responseLimit,
            @Value("${dwp.platform.mail.providers.microsoft-graph.maximum-request-bytes:33554432}")
            int requestLimit) {
        this(tokens, new MailProviderHttpTransport(builder, mapper, baseUrl, allowedHosts,
                allowLocal, responseLimit, requestLimit), Clock.systemUTC(), new SecureRandom());
    }

    MicrosoftGraphMailConnector(MailProviderTokenBroker tokens,
                                MailProviderHttpTransport http, Clock clock,
                                SecureRandom random) {
        this.tokens = tokens;
        this.http = http;
        this.clock = clock;
        this.random = random;
    }

    @Override public Manifest manifest() {
        return new Manifest(PROVIDER, "1.0", "Microsoft Graph v1.0", Set.of(
                Capability.READ, Capability.SEND, Capability.SEND_ON_BEHALF, Capability.BCC,
                Capability.HTML_BODY, Capability.ATTACHMENTS, Capability.THREADS,
                Capability.FOLDERS, Capability.DELTA_SYNC, Capability.PUSH,
                Capability.TENANT_WIDE_AUTHORIZATION));
    }

    @Override public Readiness readiness(ConnectionContext context) {
        Instant now = clock.instant();
        if (!tokens.ready(PROVIDER, context)) return new Readiness(
                ReadinessState.AUTHENTICATION_REQUIRED, now, "GRAPH_TOKEN_OWNER_UNAVAILABLE", null);
        URI uri = http.uri(value -> value.pathSegment("v1.0", "organization")
                .queryParam("$select", "id").queryParam("$top", 1));
        try (var lease = tokens.lease(PROVIDER, context)) {
            var response = http.get(uri, lease, Map.of());
            if (response.successful()) return new Readiness(ReadinessState.READY, now, null, null);
            if (response.status() == 401 || response.status() == 403) return new Readiness(
                    ReadinessState.AUTHENTICATION_REQUIRED, now,
                    "GRAPH_AUTHENTICATION_REQUIRED", null);
            Instant retry = response.status() == 429 ? retryAfter(response.headers()) : null;
            return new Readiness(response.status() >= 500
                    ? ReadinessState.UNAVAILABLE : ReadinessState.DEGRADED,
                    now, response.status() == 429 ? "GRAPH_RATE_LIMITED"
                    : "GRAPH_READINESS_REJECTED", retry);
        } catch (MailProviderException failure) {
            return new Readiness(failure.category() == MailProviderException.Category.AUTHENTICATION
                    ? ReadinessState.AUTHENTICATION_REQUIRED : ReadinessState.UNAVAILABLE,
                    now, failure.errorCode(), failure.retryAfter());
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            return new Readiness(ReadinessState.UNAVAILABLE, now,
                    "GRAPH_UNAVAILABLE", now.plusSeconds(60));
        }
    }

    @Override public SyncBatch synchronize(SyncRequest request) {
        URI seed = deltaUri(request.providerAccountReference(), request.limit());
        URI uri = request.opaqueCursor() == null || request.opaqueCursor().isBlank()
                ? seed : http.trusted(request.opaqueCursor());
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            var response = http.get(uri, lease,
                    Map.of("Prefer", "outlook.body-content-type=\"text\""));
            if (!response.successful()) throw syncFailure(response, seed.toString());
            if (!response.body().path("value").isArray()) throw syncPermanent(
                    "GRAPH_SYNC_RESPONSE_INVALID");
            List<ProviderMessage> messages = new ArrayList<>();
            for (JsonNode node : response.body().path("value")) {
                if (!node.has("@removed")) messages.add(providerMessage(
                        node, request.providerAccountReference(), lease));
            }
            String next = text(response.body(), "@odata.nextLink");
            String delta = text(response.body(), "@odata.deltaLink");
            String cursor = next == null ? delta : next;
            if (cursor == null) throw syncPermanent("GRAPH_SYNC_CURSOR_MISSING");
            http.trusted(cursor);
            return new SyncBatch(messages, cursor, false, next != null);
        } catch (MailSyncProviderException failure) {
            throw failure;
        } catch (MailProviderException failure) {
            throw syncProviderFailure(failure);
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            throw syncTransient("GRAPH_SYNC_UNAVAILABLE");
        }
    }

    @Override public DeliveryReceipt send(SendRequest request) {
        URI uri = http.uri(value -> value.pathSegment(
                "v1.0", "users", request.providerAccountReference(), "sendMail"));
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            var response = http.post(uri, lease, Map.of(
                    "client-request-id", request.idempotencyKey().toString(),
                    "return-client-request-id", "true"), Map.of(
                    "message", outboundMessage(request), "saveToSentItems", true));
            if (response.status() == 202) return new DeliveryReceipt(
                    null, null, clock.instant(), DeliveryOutcome.PROVIDER_ACCEPTED);
            throw providerHttpFailure(response, "GRAPH_SEND");
        } catch (MailProviderException failure) {
            throw failure;
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            MailProviderException.Category category = failure.requestMayHaveBeenSubmitted()
                    ? MailProviderException.Category.RESULT_UNKNOWN
                    : MailProviderException.Category.TRANSIENT;
            throw providerFailure(category, category == MailProviderException.Category.RESULT_UNKNOWN
                    ? "GRAPH_SEND_RESULT_UNKNOWN" : "GRAPH_SEND_UNAVAILABLE", null);
        }
    }

    @Override public SubscriptionReceipt renewSubscription(SubscriptionRequest request) {
        byte[] randomState = new byte[32];
        random.nextBytes(randomState);
        String clientState = Base64.getUrlEncoder().withoutPadding().encodeToString(randomState);
        java.util.Arrays.fill(randomState, (byte) 0);
        Instant until = request.requestedUntil().isBefore(clock.instant().plus(Duration.ofDays(6)))
                ? request.requestedUntil() : clock.instant().plus(Duration.ofDays(6));
        URI uri = http.uri(value -> value.pathSegment("v1.0", "subscriptions"));
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            var response = http.post(uri, lease, Map.of(), Map.of(
                    "changeType", "created,updated,deleted",
                    "notificationUrl", request.callback().toASCIIString(),
                    "resource", "/users/" + segment(request.providerAccountReference())
                            + "/mailFolders('Inbox')/messages",
                    "expirationDateTime", until.atOffset(ZoneOffset.UTC).toString(),
                    "clientState", clientState,
                    "latestSupportedTlsVersion", "v1_2"));
            if (response.status() != 201) throw providerHttpFailure(response, "GRAPH_SUBSCRIPTION");
            String id = required(response.body(), "id", "GRAPH_SUBSCRIPTION_ID_MISSING");
            Instant expiry = parseInstant(required(response.body(), "expirationDateTime",
                    "GRAPH_SUBSCRIPTION_EXPIRY_MISSING"));
            if (expiry == null) throw providerFailure(MailProviderException.Category.PERMANENT,
                    "GRAPH_SUBSCRIPTION_EXPIRY_INVALID", null);
            return new SubscriptionReceipt(id, expiry, sha256(clientState));
        }
    }

    @Override public PurgeReceipt purge(PurgeRequest request) {
        if (request.providerMessageReferences().isEmpty()) return new PurgeReceipt(
                PurgeOutcome.NOT_SUPPORTED, 0, null, clock.instant(),
                "GRAPH_THREAD_PURGE_NOT_SUPPORTED");
        int purged = 0;
        List<String> statuses = new ArrayList<>();
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            for (int offset = 0; offset < request.providerMessageReferences().size(); offset += 20) {
                List<String> slice = request.providerMessageReferences().subList(offset,
                        Math.min(offset + 20, request.providerMessageReferences().size()));
                List<Map<String, Object>> operations = new ArrayList<>();
                for (int index = 0; index < slice.size(); index++) operations.add(Map.of(
                        "id", Integer.toString(index + 1), "method", "DELETE",
                        "url", "/users/" + segment(request.providerAccountReference())
                                + "/messages/" + segment(slice.get(index))));
                URI uri = http.uri(value -> value.pathSegment("v1.0", "$batch"));
                var response = http.post(uri, lease, Map.of("Idempotency-Key",
                        request.idempotencyKey().toString()), Map.of("requests", operations));
                if (!response.successful() || !response.body().path("responses").isArray())
                    return purgeUnknown(purged, statuses, "GRAPH_PURGE_RESULT_UNKNOWN");
                for (JsonNode item : response.body().path("responses")) {
                    int status = item.path("status").asInt(0);
                    statuses.add(Integer.toString(status));
                    if (status == 204 || status == 404) purged++;
                    else return purgeUnknown(purged, statuses,
                            "GRAPH_PURGE_RECONCILIATION_REQUIRED");
                }
            }
            return new PurgeReceipt(PurgeOutcome.PURGED, purged,
                    sha256(String.join(",", statuses)), clock.instant(), null);
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            return purgeUnknown(purged, statuses, "GRAPH_PURGE_RESULT_UNKNOWN");
        } catch (MailProviderException failure) {
            return new PurgeReceipt(PurgeOutcome.FAILED, purged, null,
                    clock.instant(), failure.errorCode());
        }
    }

    private URI deltaUri(String account, int requestedLimit) {
        return http.uri(value -> value.pathSegment("v1.0", "users", account,
                        "mailFolders", "inbox", "messages", "delta")
                .queryParam("$select", String.join(",", "id", "conversationId",
                        "parentFolderId", "receivedDateTime", "sentDateTime", "from",
                        "toRecipients", "ccRecipients", "bccRecipients", "subject", "body",
                        "importance", "isRead", "attachments"))
                .queryParam("$expand", "attachments($select=id,name,contentType,size,isInline)")
                .queryParam("$top", Math.min(50, requestedLimit)));
    }

    private ProviderMessage providerMessage(
            JsonNode node, String account,
            MailProviderTokenOwner.AccessTokenLease lease) {
        String id = required(node, "id", "GRAPH_MESSAGE_ID_MISSING");
        String thread = required(node, "conversationId", "GRAPH_THREAD_ID_MISSING");
        JsonNode address = node.path("from").path("emailAddress");
        String email = value(text(address, "address"), "unknown@invalid.local");
        String name = value(text(address, "name"), email);
        List<String> recipients = new ArrayList<>();
        recipients.addAll(addresses(node.path("toRecipients")));
        recipients.addAll(addresses(node.path("ccRecipients")));
        // ProviderMessage cannot retain recipient roles yet. Never flatten BCC into the
        // participant list because materialization would index it as a visible TO address.
        JsonNode body = node.path("body");
        BodyFormat format = "html".equalsIgnoreCase(text(body, "contentType"))
                ? BodyFormat.HTML : BodyFormat.TEXT;
        Instant occurred = parseInstant(text(node, "receivedDateTime"));
        if (occurred == null) occurred = parseInstant(text(node, "sentDateTime"));
        if (occurred == null) occurred = clock.instant();
        Map<String, String> metadata = Map.of(
                "senderEmail", email,
                "senderName", name,
                "importance", value(text(node, "importance"), "normal"),
                "isRead", Boolean.toString(node.path("isRead").asBoolean(false)));
        return new ProviderMessage(id, thread, value(text(node, "parentFolderId"), "inbox"),
                occurred, name + " <" + email + ">", recipients,
                value(text(node, "subject"), "(no subject)"),
                value(text(body, "content"), ""), format,
                attachments(account, id, node.path("attachments"), lease), metadata);
    }

    private List<ProviderAttachment> attachments(
            String account, String messageId, JsonNode values,
            MailProviderTokenOwner.AccessTokenLease lease) {
        if (!values.isArray()) return List.of();
        List<ProviderAttachment> result = new ArrayList<>();
        for (JsonNode item : values) {
            String id = text(item, "id");
            long size = item.path("size").asLong(0);
            if (id == null || size < 1) continue;
            byte[] content = size <= MAXIMUM_FETCHED_ATTACHMENT_BYTES
                    ? graphAttachment(account, messageId, id, lease) : null;
            if (content != null && content.length != size) content = null;
            result.add(new ProviderAttachment(id, "graph:" + messageId + ':' + id,
                    value(text(item, "name"), "attachment"),
                    value(text(item, "contentType"), "application/octet-stream"),
                    size, content == null ? null : sha256(content), content,
                    Map.of("inline", Boolean.toString(
                                    item.path("isInline").asBoolean(false)),
                            "fetchState", content == null ? "NOT_FETCHED" : "FETCHED")));
        }
        return List.copyOf(result);
    }

    private byte[] graphAttachment(
            String account, String messageId, String attachmentId,
            MailProviderTokenOwner.AccessTokenLease lease) {
        URI uri = http.uri(value -> value.pathSegment("v1.0", "users", account,
                "messages", messageId, "attachments", attachmentId)
                .queryParam("$select", "contentBytes"));
        try {
            var response = http.get(uri, lease, Map.of());
            String encoded = response.successful() ? text(response.body(), "contentBytes") : null;
            return encoded == null ? null : Base64.getDecoder().decode(encoded);
        } catch (RuntimeException invalidOrUnavailable) {
            return null;
        }
    }

    private Map<String, Object> outboundMessage(SendRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("subject", request.subject());
        message.put("body", Map.of("contentType",
                request.bodyFormat() == BodyFormat.HTML ? "HTML" : "Text",
                "content", request.body()));
        message.put("toRecipients", graphRecipients(request.toRecipients()));
        message.put("ccRecipients", graphRecipients(request.ccRecipients()));
        message.put("bccRecipients", graphRecipients(request.bccRecipients()));
        if (!request.attachments().isEmpty()) message.put("attachments",
                request.attachments().stream().map(item -> Map.of(
                        "@odata.type", "#microsoft.graph.fileAttachment",
                        "name", item.fileName(), "contentType", item.contentType(),
                        "contentBytes", Base64.getEncoder().encodeToString(item.content())))
                        .toList());
        return Map.copyOf(message);
    }

    private List<Map<String, Object>> graphRecipients(List<String> values) {
        return values.stream().map(address -> Map.<String, Object>of(
                "emailAddress", Map.of("address", address))).toList();
    }

    private List<String> addresses(JsonNode values) {
        if (!values.isArray()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        values.forEach(item -> {
            String address = text(item.path("emailAddress"), "address");
            if (address != null) result.add(address);
        });
        return List.copyOf(result);
    }

    private MailSyncProviderException syncFailure(
            MailProviderHttpTransport.Response response, String resetCursor) {
        if (response.status() == 401 || response.status() == 403) return new MailSyncProviderException(
                MailSyncProviderException.Category.AUTHENTICATION,
                "GRAPH_AUTHENTICATION_REQUIRED", null, null);
        if (response.status() == 410) return new MailSyncProviderException(
                MailSyncProviderException.Category.CURSOR_RESET,
                "GRAPH_CURSOR_RESET_REQUIRED", null, resetCursor);
        if (response.status() == 429) return new MailSyncProviderException(
                MailSyncProviderException.Category.RATE_LIMIT,
                "GRAPH_RATE_LIMITED", retryAfter(response.headers()), null);
        return response.status() >= 500 ? syncTransient("GRAPH_SYNC_UNAVAILABLE")
                : syncPermanent("GRAPH_SYNC_REJECTED");
    }

    private MailProviderException providerHttpFailure(
            MailProviderHttpTransport.Response response, String operation) {
        if (response.status() == 401 || response.status() == 403) return providerFailure(
                MailProviderException.Category.AUTHENTICATION,
                "GRAPH_AUTHENTICATION_REQUIRED", null);
        if (response.status() == 429) return providerFailure(
                MailProviderException.Category.RATE_LIMIT,
                "GRAPH_RATE_LIMITED", retryAfter(response.headers()));
        return providerFailure(response.status() >= 500
                        ? MailProviderException.Category.TRANSIENT
                        : MailProviderException.Category.PERMANENT,
                operation + (response.status() >= 500 ? "_UNAVAILABLE" : "_REJECTED"), null);
    }

    private MailSyncProviderException syncProviderFailure(MailProviderException failure) {
        return switch (failure.category()) {
            case AUTHENTICATION -> new MailSyncProviderException(
                    MailSyncProviderException.Category.AUTHENTICATION,
                    failure.errorCode(), null, null);
            case RATE_LIMIT -> new MailSyncProviderException(
                    MailSyncProviderException.Category.RATE_LIMIT,
                    failure.errorCode(), failure.retryAfter(), null);
            case PERMANENT -> syncPermanent(failure.errorCode());
            case TRANSIENT, RESULT_UNKNOWN -> syncTransient(failure.errorCode());
        };
    }

    private MailSyncProviderException syncTransient(String code) {
        return new MailSyncProviderException(
                MailSyncProviderException.Category.TRANSIENT, code, null, null);
    }

    private MailSyncProviderException syncPermanent(String code) {
        return new MailSyncProviderException(
                MailSyncProviderException.Category.PERMANENT, code, null, null);
    }

    private MailProviderException providerFailure(
            MailProviderException.Category category, String code, Instant retryAfter) {
        return new MailProviderException(category, code, retryAfter);
    }

    private PurgeReceipt purgeUnknown(int purged, List<String> statuses, String code) {
        return new PurgeReceipt(PurgeOutcome.RECONCILIATION_REQUIRED, purged,
                statuses.isEmpty() ? null : sha256(String.join(",", statuses)),
                clock.instant(), code);
    }

    private Instant retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) return clock.instant().plusSeconds(60);
        try { return clock.instant().plusSeconds(Math.max(1, Long.parseLong(value))); }
        catch (NumberFormatException invalid) { return clock.instant().plusSeconds(60); }
    }

    private String required(JsonNode node, String field, String code) {
        String result = text(node, field);
        if (result == null) throw providerFailure(
                MailProviderException.Category.PERMANENT, code, null);
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.textValue().isBlank()
                ? null : value.textValue().trim();
    }

    private Instant parseInstant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException invalid) {
            try { return Instant.parse(value); }
            catch (DateTimeParseException ignored) { return null; }
        }
    }

    private String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
