package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupCurrentAuthority;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Calls Auth directly for a short-lived decision bound to the exact follow-up source action. */
final class GovernedProductSurfaceMeetingFollowupCurrentAuthority
        implements MeetingFollowupCurrentAuthority {

    private static final String PATH =
            "/internal/auth/v1/meeting-followup-authority/evaluate";
    private static final String TOKEN_HEADER =
            "X-DWP-Meeting-Followup-Authority-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String SERVICE_IDENTITY = "dwp-meeting-server";

    private final URI endpoint;
    private final String token;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Clock clock;

    GovernedProductSurfaceMeetingFollowupCurrentAuthority(
            MeetingFollowupAuthorityProperties properties,
            ObjectMapper mapper) {
        this(properties, mapper, httpClient(properties), Clock.systemUTC());
    }

    GovernedProductSurfaceMeetingFollowupCurrentAuthority(
            MeetingFollowupAuthorityProperties properties,
            ObjectMapper mapper,
            HttpClient client,
            Clock clock) {
        this.endpoint = origin(properties).resolve(PATH);
        this.token = requiredToken(properties.getServiceToken());
        this.requestTimeout = bounded(
                properties.getRequestTimeout(), Duration.ofMillis(250), Duration.ofSeconds(10));
        bounded(properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        if (properties.getMaximumResponseBytes() < 1_024
                || properties.getMaximumResponseBytes() > 262_144) {
            throw new IllegalArgumentException("Follow-up authority response size is invalid.");
        }
        this.maximumResponseBytes = properties.getMaximumResponseBytes();
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Decision authorize(Request request) {
        if (!valid(request)) return Decision.deny(Denial.ACTION_NOT_AUTHORIZED);
        AuthorityResponse response;
        try {
            byte[] body = mapper.writeValueAsBytes(AuthorityRequest.from(request));
            HttpRequest outbound = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header(TOKEN_HEADER, token)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<InputStream> result = client.send(
                    outbound, HttpResponse.BodyHandlers.ofInputStream());
            if (result.statusCode() != 200 || !result.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")) {
                close(result.body());
                return Decision.deny(Denial.AUTHORITY_UNVERIFIED);
            }
            response = mapper.readerFor(AuthorityResponse.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readValue(BoundedHttpResponseReader.read(result, maximumResponseBytes));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Decision.deny(Denial.AUTHORITY_UNVERIFIED);
        } catch (IOException | RuntimeException exception) {
            return Decision.deny(Denial.AUTHORITY_UNVERIFIED);
        }
        if (!response.matches(request) || response.allowed() == (response.denial() != null)) {
            return Decision.deny(Denial.AUTHORITY_UNVERIFIED);
        }
        if (!response.allowed()) return Decision.deny(response.denial());
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (blank(response.authRevision()) || blank(response.policyRevision())
                || blank(response.evidenceRef()) || response.validUntil() == null
                || !response.validUntil().isAfter(now)
                || response.validUntil().isAfter(now.plusSeconds(90))) {
            return Decision.deny(Denial.AUTHORITY_UNVERIFIED);
        }
        return Decision.allow();
    }

    private boolean valid(Request request) {
        if (request == null || request.tenantId() <= 0 || request.actorUserId() <= 0
                || request.source() == null || request.source().meetingId() == null
                || request.source().reportId() == null || request.source().candidateId() == null
                || request.action() == null) return false;
        return switch (request.action()) {
            case READ -> request.targetAssigneeUserId() == null
                    && request.expectedSourceVersion() == null;
            case CREATE -> request.targetAssigneeUserId() == null
                    && request.expectedSourceVersion() != null
                    && request.expectedSourceVersion() >= 0;
            case REASSIGN -> request.targetAssigneeUserId() != null
                    && request.targetAssigneeUserId() > 0
                    && request.expectedSourceVersion() == null;
        };
    }

    private static URI origin(MeetingFollowupAuthorityProperties properties) {
        URI candidate;
        try {
            candidate = URI.create(properties.getBaseUrl().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Follow-up authority URL is invalid.", exception);
        }
        boolean https = "https".equalsIgnoreCase(candidate.getScheme());
        boolean loopbackHttp = properties.isAllowHttp()
                && "http".equalsIgnoreCase(candidate.getScheme())
                && isLoopback(candidate.getHost());
        if ((!https && !loopbackHttp) || candidate.getHost() == null
                || candidate.getRawUserInfo() != null || candidate.getRawQuery() != null
                || candidate.getRawFragment() != null
                || candidate.getRawPath() != null && !candidate.getRawPath().isBlank()
                        && !"/".equals(candidate.getRawPath())) {
            throw new IllegalArgumentException(
                    "Follow-up authority URL must be an HTTPS origin or approved loopback HTTP.");
        }
        return candidate;
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1"));
    }

    private static String requiredToken(String value) {
        String token = value == null ? "" : value.strip();
        if (token.length() < 24 || !token.equals(value)) {
            throw new IllegalArgumentException("Follow-up authority token is unavailable.");
        }
        return token;
    }

    private static Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Follow-up authority timeout is invalid.");
        }
        return value;
    }

    private static HttpClient httpClient(MeetingFollowupAuthorityProperties properties) {
        Duration connect = bounded(
                properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        return HttpClient.newBuilder().connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // The stable decision intentionally omits remote transport details.
        }
    }

    private record Source(UUID meetingId, UUID reportId, UUID candidateId) {
    }

    private record AuthorityRequest(
            long tenantId,
            long actorUserId,
            Source source,
            Action action,
            Long targetAssigneeUserId,
            Long expectedSourceVersion) {

        static AuthorityRequest from(Request request) {
            return new AuthorityRequest(
                    request.tenantId(), request.actorUserId(),
                    new Source(request.source().meetingId(), request.source().reportId(),
                            request.source().candidateId()),
                    Action.valueOf(request.action().name()), request.targetAssigneeUserId(),
                    request.expectedSourceVersion());
        }
    }

    private enum Action {
        READ,
        CREATE,
        REASSIGN
    }

    private record AuthorityResponse(
            long tenantId,
            long actorUserId,
            Source source,
            Action action,
            Long targetAssigneeUserId,
            Long expectedSourceVersion,
            boolean allowed,
            Denial denial,
            String authRevision,
            String policyRevision,
            OffsetDateTime validUntil,
            String evidenceRef) {

        boolean matches(Request request) {
            return tenantId == request.tenantId() && actorUserId == request.actorUserId()
                    && source != null
                    && Objects.equals(source.meetingId(), request.source().meetingId())
                    && Objects.equals(source.reportId(), request.source().reportId())
                    && Objects.equals(source.candidateId(), request.source().candidateId())
                    && action != null && action.name().equals(request.action().name())
                    && Objects.equals(targetAssigneeUserId, request.targetAssigneeUserId())
                    && Objects.equals(expectedSourceVersion, request.expectedSourceVersion());
        }
    }
}
