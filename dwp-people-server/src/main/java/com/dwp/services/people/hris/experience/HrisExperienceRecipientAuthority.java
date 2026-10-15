package com.dwp.services.people.hris.experience;

import com.dwp.core.http.OutboundHttpHeaders;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Port for a server-verified, tenant-bound People subject to active DWP user
 * mapping.
 */
interface HrisExperienceRecipientAuthority {

    Optional<VerifiedRecipient> resolve(
            long tenantId,
            UUID targetPersonPublicId,
            Instant at);

    record VerifiedRecipient(
            long tenantId,
            UUID personPublicId,
            long userId,
            String evidenceRevision,
            Instant validUntil) {
    }
}

/** Fail-closed adapter for the single purpose-bound Auth recipient authority. */
@Component
final class AuthHrisExperienceRecipientAuthority
        implements HrisExperienceRecipientAuthority {

    static final String AUTHORITY_PATH =
            "/internal/auth/v1/hris-recipient-authority/resolve";
    static final String RECIPIENT_TOKEN_HEADER =
            "X-DWP-Hris-Recipient-Authority-Token";
    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String SERVICE_IDENTITY = "dwp-people-server";
    static final String PURPOSE =
            "HCM.EMPLOYEE_CHANGE_APPLIED_NOTIFICATION";

    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final int MAX_TOKEN_LENGTH = 2_048;
    private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "requestId", "tenantId", "personPublicId", "userId",
            "evidenceRevision", "capturedAt", "validUntil");

    private final RestClient auth;
    private final ObjectMapper strictJson;
    private final String token;
    private final Clock clock;
    private final Supplier<UUID> requestIds;

    @Autowired
    AuthHrisExperienceRecipientAuthority(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.experience.recipient-authority.auth-url:http://localhost:8001}")
            String authUrl,
            @Value("${dwp.hris.experience.recipient-authority.token:}") String token,
            @Value("${dwp.hris.experience.recipient-authority.timeout:PT2S}")
            Duration timeout) {
        this(client(builder, authUrl, timeout), objectMapper, token,
                Clock.systemUTC(), UUID::randomUUID);
    }

    AuthHrisExperienceRecipientAuthority(
            RestClient auth,
            ObjectMapper objectMapper,
            String token,
            Clock clock,
            Supplier<UUID> requestIds) {
        this.auth = Objects.requireNonNull(auth);
        this.token = canonicalToken(token);
        this.clock = Objects.requireNonNull(clock);
        this.requestIds = Objects.requireNonNull(requestIds);
        this.strictJson = Objects.requireNonNull(objectMapper).copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public Optional<VerifiedRecipient> resolve(
            long tenantId,
            UUID targetPersonPublicId,
            Instant at) {
        try {
            return resolveClosed(tenantId, targetPersonPublicId, at);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<VerifiedRecipient> resolveClosed(
            long tenantId,
            UUID targetPersonPublicId,
            Instant at) {
        if (tenantId < 1 || targetPersonPublicId == null || at == null
                || token.isBlank()) {
            return Optional.empty();
        }

        UUID requestId = Objects.requireNonNull(requestIds.get());
        String body;
        try {
            body = strictJson.writeValueAsString(new ResolveRequest(
                    requestId.toString(), tenantId, targetPersonPublicId.toString(),
                    PURPOSE));
        } catch (Exception exception) {
            return Optional.empty();
        }

        ResponseEntity<String> response = auth.post().uri(AUTHORITY_PATH)
                .headers(OutboundHttpHeaders::propagateObservability)
                .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                .header(RECIPIENT_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        MediaType responseType = response.getHeaders().getContentType();
        if (response.getStatusCode().value() != 200 || responseType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(responseType)
                || response.getBody() == null || response.getBody().isBlank()) {
            return Optional.empty();
        }

        JsonNode value;
        try {
            value = strictJson.readTree(response.getBody());
        } catch (Exception exception) {
            return Optional.empty();
        }
        return validate(value, requestId, tenantId, targetPersonPublicId, at);
    }

    private Optional<VerifiedRecipient> validate(
            JsonNode value,
            UUID requestId,
            long tenantId,
            UUID personPublicId,
            Instant businessEventAt) {
        if (value == null || !value.isObject() || value.size() != RESPONSE_FIELDS.size()
                || RESPONSE_FIELDS.stream().anyMatch(field -> !value.has(field))) {
            return Optional.empty();
        }

        UUID responseRequestId = canonicalUuid(value.get("requestId"));
        UUID responsePersonId = canonicalUuid(value.get("personPublicId"));
        Long responseTenantId = positiveLong(value.get("tenantId"));
        Long userId = positiveLong(value.get("userId"));
        String evidenceRevision = text(value.get("evidenceRevision"));
        Instant capturedAt = canonicalInstant(value.get("capturedAt"));
        Instant validUntil = canonicalInstant(value.get("validUntil"));
        if (!requestId.equals(responseRequestId)
                || responseTenantId == null || responseTenantId != tenantId
                || !personPublicId.equals(responsePersonId)
                || userId == null
                || evidenceRevision == null
                || !evidenceRevision.matches(DIGEST_PATTERN)
                || capturedAt == null || validUntil == null) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        Duration lease = Duration.between(capturedAt, validUntil);
        if (capturedAt.isAfter(now.plus(MAX_CLOCK_SKEW))
                || !validUntil.isAfter(capturedAt)
                || !validUntil.isAfter(now)
                || !validUntil.isAfter(businessEventAt)
                || lease.compareTo(MAX_LEASE) > 0) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedRecipient(
                tenantId, personPublicId, userId, evidenceRevision, validUntil));
    }

    private static RestClient client(
            RestClient.Builder builder,
            String baseUrl,
            Duration timeout) {
        if (!canonical(baseUrl, 2_048) || timeout == null || timeout.isZero()
                || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "HRIS recipient authority URL/timeout is invalid.");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static String canonicalToken(String value) {
        if (value == null || value.isBlank()) return "";
        if (!canonical(value, MAX_TOKEN_LENGTH)) {
            throw new IllegalArgumentException(
                    "HRIS recipient authority token is not canonical.");
        }
        return value;
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static UUID canonicalUuid(JsonNode value) {
        String text = text(value);
        if (text == null) return null;
        try {
            UUID parsed = UUID.fromString(text);
            return parsed.toString().equals(text) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long positiveLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long parsed = value.longValue();
        return parsed > 0 ? parsed : null;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Instant canonicalInstant(JsonNode value) {
        String text = text(value);
        if (text == null) return null;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(text);
            return ZoneOffset.UTC.equals(parsed.getOffset())
                    ? parsed.toInstant() : null;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private record ResolveRequest(
            String requestId,
            long tenantId,
            String personPublicId,
            String purpose) {
    }
}
