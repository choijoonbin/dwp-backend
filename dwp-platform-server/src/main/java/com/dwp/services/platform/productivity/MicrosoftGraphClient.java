package com.dwp.services.platform.productivity;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.dwp.services.platform.productivity.ProductivityTypes.ConnectorHealth;
import static com.dwp.services.platform.productivity.ProductivityTypes.ResourceKind;

@Component
public class MicrosoftGraphClient {

    private static final String LOGIN_HOST = "login.microsoftonline.com";
    private static final String GRAPH_HOST = "graph.microsoft.com";
    private static final Set<String> OUTLOOK_LINK_HOST_SUFFIXES = Set.of(
            "outlook.office.com", "outlook.office365.com", "outlook.live.com");

    private final RestClient restClient;
    private final URI authorizeBase;
    private final URI apiBase;

    public MicrosoftGraphClient(
            RestClient.Builder builder,
            @Value("${dwp.platform.productivity.microsoft-graph.authorize-base-url:https://login.microsoftonline.com}")
            String authorizeBaseUrl,
            @Value("${dwp.platform.productivity.microsoft-graph.api-base-url:https://graph.microsoft.com}")
            String apiBaseUrl) {
        this.restClient = builder.build();
        this.authorizeBase = trustedBase(authorizeBaseUrl, LOGIN_HOST);
        this.apiBase = trustedBase(apiBaseUrl, GRAPH_HOST);
    }

    public URI authorizationUri(
            ProductivityRepository.ConnectorRecord connector,
            String state,
            String codeChallenge) {
        URI uri = UriComponentsBuilder.fromUri(authorizeBase)
                .pathSegment(connector.providerTenantId(), "oauth2", "v2.0", "authorize")
                .queryParam("client_id", connector.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", connector.redirectUri())
                .queryParam("response_mode", "query")
                .queryParam("scope", String.join(" ", connector.requestedScopes()))
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUri();
        requireHost(uri, LOGIN_HOST);
        return uri;
    }

    public TokenResponse exchangeCode(
            ProductivityRepository.ConnectorRecord connector,
            String clientSecret,
            String code,
            String verifier) {
        MultiValueMap<String, String> form = baseTokenForm(connector, clientSecret);
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", verifier);
        form.add("redirect_uri", connector.redirectUri());
        return token(connector, form);
    }

    public TokenResponse refresh(
            ProductivityRepository.ConnectorRecord connector,
            String clientSecret,
            String refreshToken) {
        MultiValueMap<String, String> form = baseTokenForm(connector, clientSecret);
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return token(connector, form);
    }

    public String subjectReference(String accessToken) {
        try {
            JsonNode response = restClient.get()
                    .uri(UriComponentsBuilder.fromUri(apiBase)
                            .path("/v1.0/me")
                            .queryParam("$select", "id")
                            .build().encode().toUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            String id = text(response, "id");
            if (id == null) throw new GraphFailure(
                    "GRAPH_SUBJECT_UNAVAILABLE", ConnectorHealth.DEGRADED, null, false, false);
            return id;
        } catch (RestClientResponseException exception) {
            throw failure(exception, false);
        }
    }

    public GraphPage readPage(
            String accessToken,
            ResourceKind resourceKind,
            String opaqueCursor,
            Instant calendarWindowStart,
            Instant calendarWindowEnd) {
        URI uri = opaqueCursor == null
                ? initialUri(resourceKind, calendarWindowStart, calendarWindowEnd)
                : trustedGraphCursor(opaqueCursor);
        try {
            JsonNode response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Prefer", "outlook.timezone=\"UTC\"")
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("value").isArray()) {
                throw new GraphFailure(
                        "GRAPH_INVALID_RESPONSE", ConnectorHealth.DEGRADED, null, true, false);
            }
            List<GraphItem> items = new ArrayList<>();
            response.path("value").forEach(node -> items.add(toItem(resourceKind, node)));
            String next = text(response, "@odata.nextLink");
            String delta = text(response, "@odata.deltaLink");
            if (next != null) trustedGraphCursor(next);
            if (delta != null) trustedGraphCursor(delta);
            return new GraphPage(items, next == null ? delta : next, next != null, delta != null);
        } catch (RestClientResponseException exception) {
            throw failure(exception, true);
        }
    }

    public boolean trustedDeepLink(String value) {
        if (value == null || value.isBlank()) return true;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase();
            return host.equals(GRAPH_HOST) || OUTLOOK_LINK_HOST_SUFFIXES.stream()
                    .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private TokenResponse token(
            ProductivityRepository.ConnectorRecord connector,
            MultiValueMap<String, String> form) {
        URI tokenUri = UriComponentsBuilder.fromUri(authorizeBase)
                .pathSegment(connector.providerTenantId(), "oauth2", "v2.0", "token")
                .build().encode().toUri();
        requireHost(tokenUri, LOGIN_HOST);
        try {
            JsonNode response = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            String accessToken = text(response, "access_token");
            String refreshToken = text(response, "refresh_token");
            if (accessToken == null) {
                throw new GraphFailure(
                        "OAUTH_TOKEN_MISSING", ConnectorHealth.AUTHENTICATION_REQUIRED,
                        null, false, false);
            }
            long expiresIn = response == null ? 3600 : response.path("expires_in").asLong(3600);
            String scope = text(response, "scope");
            return new TokenResponse(
                    accessToken,
                    refreshToken,
                    Instant.now().plusSeconds(Math.max(60, expiresIn)),
                    scope == null || scope.isBlank() ? List.of() : List.of(scope.split("\\s+")));
        } catch (RestClientResponseException exception) {
            throw failure(exception, false);
        }
    }

    private MultiValueMap<String, String> baseTokenForm(
            ProductivityRepository.ConnectorRecord connector,
            String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", connector.clientId());
        form.add("client_secret", clientSecret);
        form.add("scope", String.join(" ", connector.requestedScopes()));
        return form;
    }

    private URI initialUri(ResourceKind resourceKind, Instant windowStart, Instant windowEnd) {
        if (resourceKind == ResourceKind.MAIL) {
            return UriComponentsBuilder.fromUri(apiBase)
                    .path("/v1.0/me/mailFolders/inbox/messages/delta")
                    .queryParam("$select", String.join(",",
                            "id", "subject", "receivedDateTime", "lastModifiedDateTime",
                            "webLink", "isRead", "importance"))
                    .queryParam("$top", 50)
                    .build().encode().toUri();
        }
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new GraphFailure(
                    "CALENDAR_WINDOW_INVALID", ConnectorHealth.DEGRADED, null, false, false);
        }
        return UriComponentsBuilder.fromUri(apiBase)
                .path("/v1.0/me/calendarView/delta")
                .queryParam("startDateTime", windowStart.toString())
                .queryParam("endDateTime", windowEnd.toString())
                .queryParam("$select", String.join(",",
                        "id", "subject", "start", "end", "lastModifiedDateTime",
                        "webLink", "isCancelled"))
                .build().encode().toUri();
    }

    private GraphItem toItem(ResourceKind kind, JsonNode node) {
        String sourceId = text(node, "id");
        if (sourceId == null) {
            return new GraphItem(null, true, null, null, null, null, null, null, false, null);
        }
        boolean removed = node.has("@removed");
        if (removed) {
            return new GraphItem(sourceId, true, null, null, null, null, null, null, false, null);
        }
        if (kind == ResourceKind.MAIL) {
            return new GraphItem(
                    sourceId,
                    false,
                    defaultTitle(text(node, "subject")),
                    text(node, "webLink"),
                    parseInstant(text(node, "receivedDateTime")),
                    null,
                    text(node, "importance"),
                    node.has("isRead") ? node.path("isRead").asBoolean() : null,
                    false,
                    text(node, "lastModifiedDateTime"));
        }
        return new GraphItem(
                sourceId,
                false,
                defaultTitle(text(node, "subject")),
                text(node, "webLink"),
                parseGraphDateTime(node.path("start")),
                parseGraphDateTime(node.path("end")),
                null,
                null,
                node.path("isCancelled").asBoolean(false),
                text(node, "lastModifiedDateTime"));
    }

    private GraphFailure failure(RestClientResponseException exception, boolean deltaRequest) {
        int status = exception.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new GraphFailure(
                    "GRAPH_AUTHENTICATION_REQUIRED", ConnectorHealth.AUTHENTICATION_REQUIRED,
                    null, false, false);
        }
        if (status == 410 && deltaRequest) {
            return new GraphFailure(
                    "GRAPH_CURSOR_RESET_REQUIRED", ConnectorHealth.DEGRADED,
                    null, false, true);
        }
        if (status == 429) {
            Duration retry = retryAfter(exception.getResponseHeaders());
            return new GraphFailure(
                    "GRAPH_RATE_LIMITED", ConnectorHealth.DEGRADED, retry, true, false);
        }
        if (status >= 500) {
            return new GraphFailure(
                    "GRAPH_UNAVAILABLE", ConnectorHealth.UNAVAILABLE, Duration.ofMinutes(1), true, false);
        }
        String code = status == 400 && !deltaRequest
                ? "OAUTH_REQUEST_REJECTED"
                : "GRAPH_REQUEST_REJECTED";
        return new GraphFailure(code, ConnectorHealth.DEGRADED, null, false, false);
    }

    private Duration retryAfter(HttpHeaders headers) {
        if (headers == null) return Duration.ofMinutes(1);
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value)));
        } catch (NumberFormatException | NullPointerException exception) {
            return Duration.ofMinutes(1);
        }
    }

    private URI trustedGraphCursor(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new GraphFailure(
                    "GRAPH_CURSOR_INVALID", ConnectorHealth.DEGRADED, null, false, true);
        }
        requireHost(uri, GRAPH_HOST);
        return uri;
    }

    private static URI trustedBase(String value, String expectedHost) {
        URI uri = URI.create(value);
        requireHost(uri, expectedHost);
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Microsoft Graph base URL must not contain query or fragment.");
        }
        return uri;
    }

    private static void requireHost(URI uri, String expectedHost) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !expectedHost.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalStateException("Untrusted Microsoft Graph endpoint.");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return null;
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static String defaultTitle(String value) {
        return value == null || value.isBlank() ? "(No title)" : value;
    }

    private static Instant parseGraphDateTime(JsonNode node) {
        return node == null ? null : parseInstant(text(node, "dateTime"));
    }

    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException second) {
                try {
                    return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException third) {
                    return null;
                }
            }
        }
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            List<String> scopes) {
    }

    public record GraphPage(
            List<GraphItem> items,
            String nextCursor,
            boolean hasMore,
            boolean deltaComplete) {
    }

    public record GraphItem(
            String sourceId,
            boolean removed,
            String title,
            String sourceUrl,
            Instant occurredAt,
            Instant endsAt,
            String importance,
            Boolean read,
            boolean cancelled,
            String sourceVersion) {
    }

    public static final class GraphFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;
        private final ConnectorHealth health;
        private final Duration retryAfter;
        private final boolean retryable;
        private final boolean resetRequired;

        GraphFailure(
                String code,
                ConnectorHealth health,
                Duration retryAfter,
                boolean retryable,
                boolean resetRequired) {
            super(code);
            this.code = code;
            this.health = health;
            this.retryAfter = retryAfter;
            this.retryable = retryable;
            this.resetRequired = resetRequired;
        }

        public String code() { return code; }

        public ConnectorHealth health() { return health; }

        public Duration retryAfter() { return retryAfter; }

        public boolean retryable() { return retryable; }

        public boolean resetRequired() { return resetRequired; }
    }
}
