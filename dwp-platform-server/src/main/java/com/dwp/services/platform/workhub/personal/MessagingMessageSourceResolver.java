package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;

/** Hydrates a message identity through Messaging's current tenant/member/history ACL. */
@Component
public final class MessagingMessageSourceResolver implements PersonalWorkSourceResolver {
    static final String SOURCE = "MESSAGING_MESSAGE";
    private final ObjectMapper mapper;
    private final URI base;
    private final String serviceToken;
    private final String workSourceToken;
    private final HttpClient http;
    private final Duration timeout;

    @Autowired
    public MessagingMessageSourceResolver(
            ObjectMapper mapper,
            @Value("${DWP_WORK_MESSAGING_SOURCE_BASE_URL:}") String baseUrl,
            @Value("${DWP_WORK_MESSAGING_SOURCE_SERVICE_TOKEN:}") String serviceToken,
            @Value("${DWP_WORK_MESSAGING_SOURCE_TOKEN:}") String workSourceToken,
            @Value("${DWP_WORK_MESSAGING_SOURCE_ALLOW_HTTP:false}") boolean allowHttp) {
        this(mapper, baseUrl, serviceToken, workSourceToken, allowHttp,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(5));
    }

    MessagingMessageSourceResolver(ObjectMapper mapper, String baseUrl, String serviceToken,
            String workSourceToken, boolean allowHttp, HttpClient http, Duration timeout) {
        this.mapper = mapper;
        this.http = http;
        this.timeout = timeout;
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.workSourceToken = workSourceToken == null ? "" : workSourceToken.strip();
        URI configured = null;
        try {
            URI candidate = URI.create(baseUrl);
            if (candidate.getHost() == null || candidate.getRawUserInfo() != null
                    || candidate.getRawQuery() != null || candidate.getRawFragment() != null
                    || !(candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))
                    || !("https".equals(candidate.getScheme())
                    || allowHttp && "http".equals(candidate.getScheme()))) {
                throw new IllegalArgumentException("Invalid Messaging source endpoint.");
            }
            configured = candidate;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // An unconfigured resolver stays unavailable. There is no local or cached metadata fallback.
        }
        this.base = configured;
    }

    @Override
    public boolean supports(SourceReference reference) {
        return reference != null && SOURCE.equals(reference.sourceSystem());
    }

    @Override
    public Optional<ResolvedSource> resolve(AccessContext context, SourceReference reference) {
        if (!supports(reference) || context == null
                || context.tenantId() == null || context.tenantId() <= 0
                || context.userId() == null || context.userId() <= 0
                || !permitted(context.permissions(), "APP.WORK:VIEW")
                || !permitted(context.permissions(), "APP.MESSAGING:VIEW")) return Optional.empty();
        UUID conversationId = uuid(reference.sourceReference());
        UUID messageId = uuid(reference.obligationKey());
        if (conversationId == null || messageId == null) return Optional.empty();
        JsonNode data = request(context, conversationId, messageId);
        if (data == null) return Optional.empty();
        try {
            if (!conversationId.toString().equals(text(data, "conversationId"))
                    || !messageId.toString().equals(text(data, "messageId"))) throw unavailable();
            String channel = bounded(data, "channelName", 200);
            String sender = bounded(data, "senderName", 200);
            String excerpt = bounded(data, "excerpt", 500);
            String received = text(data, "receivedAt");
            JsonNode versionNode = data.path("version");
            long version = versionNode.longValue();
            if (channel == null || sender == null || excerpt == null || excerpt.isBlank()
                    || received == null || !versionNode.isIntegralNumber()
                    || !versionNode.canConvertToLong() || version < 0) {
                throw unavailable();
            }
            OffsetDateTime editedAt = optionalTime(data, "editedAt");
            String route = "/messages/inbox?conversation=" + conversationId
                    + "&message=" + messageId;
            return Optional.of(new ResolvedSource(reference, excerpt, route, "AVAILABLE", null,
                    channel, sender, OffsetDateTime.parse(received), excerpt,
                    messageId.toString(), version, editedAt));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private JsonNode request(AccessContext context, UUID conversationId, UUID messageId) {
        if (base == null || serviceToken.isBlank() || workSourceToken.isBlank()) throw unavailable();
        URI endpoint = base.resolve("/internal/v1/work-sources/conversations/" + conversationId
                + "/messages/" + messageId);
        try {
            HttpHeaders observability = new HttpHeaders();
            OutboundHttpHeaders.propagateObservability(observability);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Accept", "application/json")
                    .header("X-DWP-Service-Token", serviceToken)
                    .header("X-DWP-Work-Source-Token", workSourceToken)
                    .header("X-DWP-Tenant-ID", context.tenantId().toString())
                    .header("X-DWP-User-ID", context.userId().toString())
                    .header("X-DWP-Permissions", context.permissions());
            if (context.personPublicId() != null)
                builder.header("X-DWP-Person-Public-ID", context.personPublicId().toString());
            if (trusted(context.groupRefs())) builder.header("X-DWP-Group-Refs", context.groupRefs());
            if (trusted(context.locale())) builder.header("Accept-Language", context.locale());
            observability.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                    builder.GET().build(), ignored -> new MessagingSourceBodySubscriber());
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            if (response.statusCode() == 401) throw unavailable();
            if (response.statusCode() == 403 || response.statusCode() == 404
                    || response.statusCode() == 410) return null;
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw unavailable();
            }
            JsonNode envelope = mapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body());
            if (envelope == null || !"SUCCESS".equals(text(envelope, "status"))
                    || !envelope.path("success").isBoolean() || !envelope.path("success").booleanValue()
                    || !envelope.path("data").isObject()) throw unavailable();
            return envelope.path("data");
        } catch (BaseException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static String bounded(JsonNode node, String field, int limit) {
        String value = text(node, field);
        return value != null && !value.isBlank() && value.length() <= limit ? value : null;
    }

    private static OffsetDateTime optionalTime(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : OffsetDateTime.parse(value.textValue());
    }

    private static boolean trusted(String value) {
        return value != null && !value.isBlank() && value.length() <= 8_192
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static boolean permitted(String permissions, String permission) {
        return permissions != null && Arrays.stream(permissions.split(","))
                .map(String::strip).anyMatch(permission::equals);
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            UUID result = UUID.fromString(value);
            return result.toString().equals(value) ? result : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static BaseException unavailable() {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Messaging source verification is unavailable.");
    }
}
