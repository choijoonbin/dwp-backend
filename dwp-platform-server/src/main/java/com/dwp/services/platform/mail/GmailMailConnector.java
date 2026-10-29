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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real Gmail API adapter with history sync, MIME send and Pub/Sub watch. */
@Component
@ConditionalOnProperty(name = "dwp.platform.mail.providers.google-gmail.enabled",
        havingValue = "true")
class GmailMailConnector implements MailConnectorPort {
    private static final ProviderFamily PROVIDER = ProviderFamily.GOOGLE_GMAIL;
    private static final long MAXIMUM_FETCHED_ATTACHMENT_BYTES = 5L * 1024L * 1024L;
    private final MailProviderTokenBroker tokens;
    private final MailProviderHttpTransport http;
    private final Clock clock;
    private final String topicName;
    private final String subscriptionName;

    GmailMailConnector(
            RestClient.Builder builder, ObjectMapper mapper, MailProviderTokenBroker tokens,
            @Value("${dwp.platform.mail.providers.google-gmail.base-url:https://gmail.googleapis.com}")
            String baseUrl,
            @Value("${dwp.platform.mail.providers.google-gmail.allowed-hosts:gmail.googleapis.com}")
            String allowedHosts,
            @Value("${dwp.platform.mail.providers.google-gmail.allow-insecure-localhost:false}")
            boolean allowLocal,
            @Value("${dwp.platform.mail.providers.google-gmail.maximum-response-bytes:8388608}")
            int responseLimit,
            @Value("${dwp.platform.mail.providers.google-gmail.maximum-request-bytes:33554432}")
            int requestLimit,
            @Value("${dwp.platform.mail.providers.google-gmail.topic-name:}") String topicName,
            @Value("${dwp.platform.mail.providers.google-gmail.subscription-name:}")
            String subscriptionName) {
        this(tokens, new MailProviderHttpTransport(builder, mapper, baseUrl, allowedHosts,
                allowLocal, responseLimit, requestLimit), Clock.systemUTC(), topicName,
                subscriptionName);
    }

    GmailMailConnector(MailProviderTokenBroker tokens, MailProviderHttpTransport http,
                       Clock clock, String topicName, String subscriptionName) {
        this.tokens = tokens;
        this.http = http;
        this.clock = clock;
        this.topicName = topicName == null ? "" : topicName.trim();
        this.subscriptionName = subscriptionName == null ? "" : subscriptionName.trim();
    }

    @Override public Manifest manifest() {
        return new Manifest(PROVIDER, "1.0", "Gmail API v1", Set.of(
                Capability.READ, Capability.SEND, Capability.BCC, Capability.HTML_BODY,
                Capability.ATTACHMENTS, Capability.THREADS, Capability.LABELS,
                Capability.DELTA_SYNC, Capability.PUSH));
    }

    @Override public Readiness readiness(ConnectionContext context) {
        Instant now = clock.instant();
        if (!tokens.ready(PROVIDER, context)) return new Readiness(
                ReadinessState.AUTHENTICATION_REQUIRED, now, "GMAIL_TOKEN_OWNER_UNAVAILABLE", null);
        try (var lease = tokens.lease(PROVIDER, context)) {
            URI uri = userUri("me", "profile");
            var response = http.get(uri, lease, Map.of());
            if (response.successful()) return new Readiness(ReadinessState.READY, now, null, null);
            if (response.status() == 401 || response.status() == 403) return new Readiness(
                    ReadinessState.AUTHENTICATION_REQUIRED, now,
                    "GMAIL_AUTHENTICATION_REQUIRED", null);
            return new Readiness(response.status() >= 500
                    ? ReadinessState.UNAVAILABLE : ReadinessState.DEGRADED,
                    now, response.status() == 429 ? "GMAIL_RATE_LIMITED"
                    : "GMAIL_READINESS_REJECTED",
                    response.status() == 429 ? retryAfter(response.headers()) : null);
        } catch (MailProviderException failure) {
            return new Readiness(failure.category() == MailProviderException.Category.AUTHENTICATION
                    ? ReadinessState.AUTHENTICATION_REQUIRED : ReadinessState.UNAVAILABLE,
                    now, failure.errorCode(), failure.retryAfter());
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            return new Readiness(ReadinessState.UNAVAILABLE, now,
                    "GMAIL_UNAVAILABLE", now.plusSeconds(60));
        }
    }

    @Override public SyncBatch synchronize(SyncRequest request) {
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            GmailCursor cursor = cursor(request.opaqueCursor(), request.providerAccountReference(), lease);
            return cursor.history()
                    ? history(request, cursor, lease)
                    : initial(request, cursor, lease);
        } catch (MailSyncProviderException failure) {
            throw failure;
        } catch (MailProviderException failure) {
            throw syncProviderFailure(failure);
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            throw syncTransient("GMAIL_SYNC_UNAVAILABLE");
        }
    }

    @Override public DeliveryReceipt send(SendRequest request) {
        URI uri = http.uri(value -> value.pathSegment(
                "gmail", "v1", "users", request.providerAccountReference(), "messages", "send"));
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime(request));
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            var response = http.post(uri, lease,
                    Map.of("Idempotency-Key", request.idempotencyKey().toString()),
                    Map.of("raw", raw));
            if (!response.successful()) throw providerHttpFailure(response, "GMAIL_SEND");
            String id = required(response.body(), "id", "GMAIL_MESSAGE_ID_MISSING");
            String thread = required(response.body(), "threadId", "GMAIL_THREAD_ID_MISSING");
            return new DeliveryReceipt(id, thread, clock.instant(),
                    DeliveryOutcome.PROVIDER_ACCEPTED);
        } catch (MailProviderException failure) {
            throw failure;
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            MailProviderException.Category category = failure.requestMayHaveBeenSubmitted()
                    ? MailProviderException.Category.RESULT_UNKNOWN
                    : MailProviderException.Category.TRANSIENT;
            throw providerFailure(category, category == MailProviderException.Category.RESULT_UNKNOWN
                    ? "GMAIL_SEND_RESULT_UNKNOWN" : "GMAIL_SEND_UNAVAILABLE", null);
        }
    }

    @Override public SubscriptionReceipt renewSubscription(SubscriptionRequest request) {
        if (!topicName.matches("projects/[A-Za-z0-9._-]{1,200}/topics/[A-Za-z0-9._-]{1,200}")
                || !subscriptionName.matches(
                        "projects/[A-Za-z0-9._-]{1,200}/subscriptions/[A-Za-z0-9._-]{1,200}"))
            throw providerFailure(MailProviderException.Category.PERMANENT,
                    "GMAIL_WATCH_BINDING_INVALID", null);
        URI uri = userUri(request.providerAccountReference(), "watch");
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            var response = http.post(uri, lease, Map.of(), Map.of(
                    "topicName", topicName, "labelIds", List.of("INBOX"),
                    "labelFilterBehavior", "INCLUDE"));
            if (!response.successful()) throw providerHttpFailure(response, "GMAIL_WATCH");
            required(response.body(), "historyId", "GMAIL_HISTORY_ID_MISSING");
            long expiration = response.body().path("expiration").asLong(0);
            if (expiration <= clock.millis()) throw providerFailure(
                    MailProviderException.Category.PERMANENT, "GMAIL_WATCH_EXPIRY_INVALID", null);
            return new SubscriptionReceipt(subscriptionName,
                    Instant.ofEpochMilli(expiration),
                    sha256(subscriptionName + ':' + topicName));
        }
    }

    @Override public PurgeReceipt purge(PurgeRequest request) {
        if (request.providerMessageReferences().isEmpty()) return new PurgeReceipt(
                PurgeOutcome.NOT_SUPPORTED, 0, null, clock.instant(),
                "GMAIL_THREAD_PURGE_NOT_SUPPORTED");
        URI uri = http.uri(value -> value.pathSegment(
                "gmail", "v1", "users", request.providerAccountReference(),
                "messages", "batchDelete"));
        try (var lease = tokens.lease(PROVIDER, request.context())) {
            var response = http.post(uri, lease,
                    Map.of("Idempotency-Key", request.idempotencyKey().toString()),
                    Map.of("ids", request.providerMessageReferences()));
            if (response.status() == 204 || response.status() == 200) return new PurgeReceipt(
                    PurgeOutcome.PURGED, request.providerMessageReferences().size(),
                    sha256(request.idempotencyKey().toString()), clock.instant(), null);
            if (response.status() == 401 || response.status() == 403) return new PurgeReceipt(
                    PurgeOutcome.FAILED, 0, null, clock.instant(),
                    "GMAIL_PURGE_AUTHENTICATION_REQUIRED");
            return new PurgeReceipt(PurgeOutcome.RECONCILIATION_REQUIRED, 0, null,
                    clock.instant(), "GMAIL_PURGE_RECONCILIATION_REQUIRED");
        } catch (MailProviderHttpTransport.TransportFailure failure) {
            return new PurgeReceipt(PurgeOutcome.RECONCILIATION_REQUIRED, 0, null,
                    clock.instant(), "GMAIL_PURGE_RESULT_UNKNOWN");
        } catch (MailProviderException failure) {
            return new PurgeReceipt(PurgeOutcome.FAILED, 0, null,
                    clock.instant(), failure.errorCode());
        }
    }

    private SyncBatch initial(SyncRequest request, GmailCursor cursor,
                              MailProviderTokenOwner.AccessTokenLease lease) {
        URI uri = http.uri(value -> {
            value.pathSegment("gmail", "v1", "users", request.providerAccountReference(),
                    "messages").queryParam("maxResults", Math.min(50, request.limit()))
                    .queryParam("labelIds", "INBOX");
            if (cursor.pageToken() != null) value.queryParam("pageToken", cursor.pageToken());
        });
        var response = http.get(uri, lease, Map.of());
        if (!response.successful()) throw syncFailure(response, "initial:" + cursor.historyId());
        List<String> ids = ids(response.body().path("messages"), "id");
        List<ProviderMessage> messages = loadMessages(
                request.providerAccountReference(), ids, lease);
        String next = text(response.body(), "nextPageToken");
        return new SyncBatch(messages, next == null
                ? "history:" + cursor.historyId()
                : "initial:" + cursor.historyId() + ':' + encode(next), false, next != null);
    }

    private SyncBatch history(SyncRequest request, GmailCursor cursor,
                              MailProviderTokenOwner.AccessTokenLease lease) {
        URI uri = http.uri(value -> {
            value.pathSegment("gmail", "v1", "users", request.providerAccountReference(),
                    "history").queryParam("startHistoryId", cursor.historyId())
                    .queryParam("historyTypes", "messageAdded")
                    .queryParam("maxResults", Math.min(50, request.limit()));
            if (cursor.pageToken() != null) value.queryParam("pageToken", cursor.pageToken());
        });
        var response = http.get(uri, lease, Map.of());
        if (response.status() == 404) {
            String reset = "initial:" + profileHistory(request.providerAccountReference(), lease);
            throw new MailSyncProviderException(MailSyncProviderException.Category.CURSOR_RESET,
                    "GMAIL_HISTORY_RESET_REQUIRED", null, reset);
        }
        if (!response.successful()) throw syncFailure(response, "initial:" + cursor.historyId());
        Set<String> unique = new LinkedHashSet<>();
        response.body().path("history").forEach(history -> history.path("messagesAdded")
                .forEach(added -> {
                    String id = text(added.path("message"), "id");
                    if (id != null) unique.add(id);
                }));
        List<ProviderMessage> messages = loadMessages(
                request.providerAccountReference(), List.copyOf(unique), lease);
        String next = text(response.body(), "nextPageToken");
        String latest = value(text(response.body(), "historyId"), cursor.historyId());
        return new SyncBatch(messages, next == null ? "history:" + latest
                : "history:" + cursor.historyId() + ':' + encode(next), false, next != null);
    }

    private GmailCursor cursor(String raw, String account,
                               MailProviderTokenOwner.AccessTokenLease lease) {
        if (raw == null || raw.isBlank()) return new GmailCursor(
                false, profileHistory(account, lease), null);
        String[] parts = raw.split(":", 3);
        if (parts.length < 2 || !(parts[0].equals("initial") || parts[0].equals("history"))
                || !parts[1].matches("[0-9]{1,40}")) throw syncPermanent(
                "GMAIL_CURSOR_INVALID");
        String page = parts.length == 3 ? decode(parts[2]) : null;
        return new GmailCursor(parts[0].equals("history"), parts[1], page);
    }

    private String profileHistory(String account,
                                  MailProviderTokenOwner.AccessTokenLease lease) {
        var response = http.get(userUri(account, "profile"), lease, Map.of());
        if (!response.successful()) throw syncFailure(response, "initial:0");
        String history = text(response.body(), "historyId");
        if (history == null || !history.matches("[0-9]{1,40}")) throw syncPermanent(
                "GMAIL_HISTORY_ID_INVALID");
        return history;
    }

    private List<ProviderMessage> loadMessages(
            String account, List<String> ids,
            MailProviderTokenOwner.AccessTokenLease lease) {
        List<ProviderMessage> result = new ArrayList<>();
        for (String id : ids) {
            URI uri = http.uri(value -> value.pathSegment(
                    "gmail", "v1", "users", account, "messages", id)
                    .queryParam("format", "full"));
            var response = http.get(uri, lease, Map.of());
            if (response.status() == 404) continue;
            if (!response.successful()) throw syncFailure(response, "initial:0");
            result.add(providerMessage(response.body(), account, lease));
        }
        return List.copyOf(result);
    }

    private ProviderMessage providerMessage(
            JsonNode node, String account,
            MailProviderTokenOwner.AccessTokenLease lease) {
        String id = required(node, "id", "GMAIL_MESSAGE_ID_MISSING");
        String thread = required(node, "threadId", "GMAIL_THREAD_ID_MISSING");
        Map<String, String> headers = headers(node.path("payload").path("headers"));
        Body body = body(node.path("payload"));
        List<String> recipients = new ArrayList<>();
        recipients.addAll(addressList(headers.get("to")));
        recipients.addAll(addressList(headers.get("cc")));
        // ProviderMessage cannot retain recipient roles yet. Never flatten BCC into the
        // participant list because materialization would index it as a visible TO address.
        String from = value(headers.get("from"), "unknown@invalid.local");
        Instant occurred = Instant.ofEpochMilli(node.path("internalDate")
                .asLong(clock.millis()));
        return new ProviderMessage(id, thread, "INBOX", occurred, from, recipients,
                value(headers.get("subject"), "(no subject)"), body.content(), body.format(),
                gmailAttachments(account, id, node.path("payload"), lease), Map.of(
                "senderEmail", email(from), "senderName", displayName(from),
                "historyId", value(text(node, "historyId"), "0")));
    }

    private Body body(JsonNode payload) {
        List<JsonNode> parts = flatten(payload);
        for (String wanted : List.of("text/plain", "text/html")) {
            for (JsonNode part : parts) if (wanted.equalsIgnoreCase(text(part, "mimeType"))) {
                String data = text(part.path("body"), "data");
                if (data != null) return new Body(decodeText(data),
                        wanted.endsWith("html") ? BodyFormat.HTML : BodyFormat.TEXT);
            }
        }
        String data = text(payload.path("body"), "data");
        return new Body(data == null ? "" : decodeText(data), BodyFormat.TEXT);
    }

    private List<ProviderAttachment> gmailAttachments(
            String account, String messageId, JsonNode payload,
            MailProviderTokenOwner.AccessTokenLease lease) {
        List<ProviderAttachment> result = new ArrayList<>();
        for (JsonNode part : flatten(payload)) {
            String fileName = text(part, "filename");
            long size = part.path("body").path("size").asLong(0);
            String attachmentId = text(part.path("body"), "attachmentId");
            if (fileName == null || size < 1) continue;
            String providerId = value(attachmentId, sha256(fileName + ':' + size));
            byte[] content = size <= MAXIMUM_FETCHED_ATTACHMENT_BYTES
                    ? gmailAttachment(account, messageId, part, lease) : null;
            if (content != null && content.length != size) content = null;
            result.add(new ProviderAttachment(providerId,
                    "gmail:" + messageId + ':' + providerId, fileName,
                    value(text(part, "mimeType"), "application/octet-stream"),
                    size, content == null ? null : sha256(content), content,
                    Map.of("fetchState", content == null ? "NOT_FETCHED" : "FETCHED")));
        }
        return List.copyOf(result);
    }

    private byte[] gmailAttachment(
            String account, String messageId, JsonNode part,
            MailProviderTokenOwner.AccessTokenLease lease) {
        String inline = text(part.path("body"), "data");
        try {
            if (inline != null) return Base64.getUrlDecoder().decode(inline);
            String attachmentId = text(part.path("body"), "attachmentId");
            if (attachmentId == null) return null;
            URI uri = http.uri(value -> value.pathSegment("gmail", "v1", "users", account,
                    "messages", messageId, "attachments", attachmentId));
            var response = http.get(uri, lease, Map.of());
            String data = response.successful() ? text(response.body(), "data") : null;
            return data == null ? null : Base64.getUrlDecoder().decode(data);
        } catch (RuntimeException invalidOrUnavailable) {
            return null;
        }
    }

    private List<JsonNode> flatten(JsonNode root) {
        List<JsonNode> result = new ArrayList<>();
        flatten(root, result);
        return result;
    }

    private void flatten(JsonNode node, List<JsonNode> result) {
        result.add(node);
        if (node.path("parts").isArray()) node.path("parts").forEach(part -> flatten(part, result));
    }

    private byte[] mime(SendRequest request) {
        String boundary = "dwp-" + request.idempotencyKey();
        StringBuilder result = new StringBuilder();
        header(result, "To", String.join(", ", request.toRecipients()));
        if (!request.ccRecipients().isEmpty())
            header(result, "Cc", String.join(", ", request.ccRecipients()));
        if (!request.bccRecipients().isEmpty())
            header(result, "Bcc", String.join(", ", request.bccRecipients()));
        header(result, "Subject", encodedHeader(request.subject()));
        header(result, "MIME-Version", "1.0");
        if (request.attachments().isEmpty()) {
            header(result, "Content-Type", request.bodyFormat() == BodyFormat.HTML
                    ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
            header(result, "Content-Transfer-Encoding", "base64");
            result.append("\r\n").append(mimeBase64(
                    request.body().getBytes(StandardCharsets.UTF_8))).append("\r\n");
        } else {
            header(result, "Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"");
            result.append("\r\n--").append(boundary).append("\r\n");
            header(result, "Content-Type", request.bodyFormat() == BodyFormat.HTML
                    ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
            header(result, "Content-Transfer-Encoding", "base64");
            result.append("\r\n").append(mimeBase64(
                    request.body().getBytes(StandardCharsets.UTF_8))).append("\r\n");
            for (OutboundAttachment attachment : request.attachments()) {
                result.append("--").append(boundary).append("\r\n");
                header(result, "Content-Type", safeHeader(attachment.contentType())
                        + "; name=\"" + quoted(attachment.fileName()) + "\"");
                header(result, "Content-Disposition", "attachment; filename=\""
                        + quoted(attachment.fileName()) + "\"");
                header(result, "Content-Transfer-Encoding", "base64");
                result.append("\r\n").append(mimeBase64(attachment.content())).append("\r\n");
            }
            result.append("--").append(boundary).append("--\r\n");
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void header(StringBuilder result, String name, String value) {
        result.append(name).append(": ").append(safeHeader(value)).append("\r\n");
    }

    private String safeHeader(String value) {
        if (value == null || value.isBlank() || value.length() > 8_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw providerFailure(MailProviderException.Category.PERMANENT,
                    "GMAIL_MIME_HEADER_INVALID", null);
        return value;
    }

    private String quoted(String value) {
        return safeHeader(value).replace("\\", "_").replace("\"", "_");
    }

    private String encodedHeader(String value) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(
                safeHeader(value).getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private String mimeBase64(byte[] value) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(value);
    }

    private URI userUri(String account, String operation) {
        return http.uri(value -> value.pathSegment("gmail", "v1", "users", account, operation));
    }

    private List<String> ids(JsonNode values, String field) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(item -> {
            String id = text(item, field);
            if (id != null) result.add(id);
        });
        return List.copyOf(result);
    }

    private Map<String, String> headers(JsonNode values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values.isArray()) values.forEach(item -> {
            String name = text(item, "name");
            String value = text(item, "value");
            if (name != null && value != null)
                result.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
        });
        return Map.copyOf(result);
    }

    private List<String> addressList(String header) {
        if (header == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : header.split(",")) {
            String email = email(value);
            if (!email.isBlank()) result.add(email);
        }
        return List.copyOf(result);
    }

    private String email(String value) {
        if (value == null) return "unknown@invalid.local";
        int open = value.lastIndexOf('<');
        int close = value.lastIndexOf('>');
        return (open >= 0 && close > open ? value.substring(open + 1, close) : value)
                .trim().toLowerCase(Locale.ROOT);
    }

    private String displayName(String value) {
        if (value == null) return "unknown@invalid.local";
        int open = value.lastIndexOf('<');
        return open > 0 ? value.substring(0, open).trim().replace("\"", "") : email(value);
    }

    private MailSyncProviderException syncFailure(
            MailProviderHttpTransport.Response response, String resetCursor) {
        if (response.status() == 401 || response.status() == 403) return new MailSyncProviderException(
                MailSyncProviderException.Category.AUTHENTICATION,
                "GMAIL_AUTHENTICATION_REQUIRED", null, null);
        if (response.status() == 429) return new MailSyncProviderException(
                MailSyncProviderException.Category.RATE_LIMIT,
                "GMAIL_RATE_LIMITED", retryAfter(response.headers()), null);
        return response.status() >= 500 ? syncTransient("GMAIL_SYNC_UNAVAILABLE")
                : syncPermanent("GMAIL_SYNC_REJECTED");
    }

    private MailProviderException providerHttpFailure(
            MailProviderHttpTransport.Response response, String operation) {
        if (response.status() == 401 || response.status() == 403) return providerFailure(
                MailProviderException.Category.AUTHENTICATION,
                "GMAIL_AUTHENTICATION_REQUIRED", null);
        if (response.status() == 429) return providerFailure(
                MailProviderException.Category.RATE_LIMIT,
                "GMAIL_RATE_LIMITED", retryAfter(response.headers()));
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
        return value == null || !value.isValueNode() || value.asText().isBlank()
                ? null : value.asText().trim();
    }

    private String decodeText(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw syncPermanent("GMAIL_BODY_INVALID"); }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw syncPermanent("GMAIL_CURSOR_INVALID"); }
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

    private record GmailCursor(boolean history, String historyId, String pageToken) { }
    private record Body(String content, BodyFormat format) { }
}
