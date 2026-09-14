package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryException;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryProperties;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Sends immutable, per-recipient direct intents to the Notification owner. */
final class GovernedHttpMeetingInvitationNotificationGateway
        implements MeetingInvitationNotificationGateway {

    private static final String PATH = "/internal/v1/intents/direct";
    private static final String TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Source-Service";
    private static final String SERVICE_IDENTITY = "dwp-meeting-server";
    private static final String ACTOR_REFERENCE = "urn:dwp:meetings";
    private static final String LOCALE = "ko-KR";

    private final URI endpoint;
    private final String token;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final ObjectMapper mapper;
    private final HttpClient client;

    GovernedHttpMeetingInvitationNotificationGateway(
            MeetingInvitationDeliveryProperties properties,
            ObjectMapper mapper) {
        this(properties, mapper, httpClient(properties));
    }

    GovernedHttpMeetingInvitationNotificationGateway(
            MeetingInvitationDeliveryProperties properties,
            ObjectMapper mapper,
            HttpClient client) {
        this.endpoint = origin(properties).resolve(PATH);
        this.token = requiredToken(properties.getServiceToken());
        this.requestTimeout = bounded(
                properties.getRequestTimeout(), Duration.ofMillis(250), Duration.ofSeconds(10));
        bounded(properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        if (properties.getMaximumResponseBytes() < 1_024
                || properties.getMaximumResponseBytes() > 262_144) {
            throw new IllegalArgumentException(
                    "Notification response size is invalid.");
        }
        this.maximumResponseBytes = properties.getMaximumResponseBytes();
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Acceptance deliver(Claim claim) {
        validateClaim(claim);
        long responseDeadline = System.nanoTime() + requestTimeout.toNanos();
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(DirectIntent.from(claim));
        } catch (IOException invalidLocalContract) {
            throw failure("NOTIFICATION_REQUEST_INVALID", false);
        }
        HttpHeaders observability = new HttpHeaders();
        OutboundHttpHeaders.propagateObservability(observability);
        observability.remove(HeaderConstants.X_CORRELATION_ID);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, token)
                .header("X-DWP-Tenant-ID", Long.toString(claim.event().tenantId()))
                .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                .header("X-Correlation-ID", "meeting-invitation:" + claim.sourceEventId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        observability.forEach((name, values) ->
                values.forEach(value -> requestBuilder.header(name, value)));
        HttpRequest request = requestBuilder.build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("NOTIFICATION_UNAVAILABLE", true);
        } catch (IOException | RuntimeException unavailable) {
            throw failure("NOTIFICATION_UNAVAILABLE", true);
        }
        int status = response.statusCode();
        if (status != 200 && status != 201) {
            close(response.body());
            throw failure(status >= 500 && status <= 599
                    ? "NOTIFICATION_UNAVAILABLE" : "NOTIFICATION_REJECTED",
                    status >= 500 && status <= 599);
        }
        if (!jsonContentType(response)) {
            close(response.body());
            throw failure("NOTIFICATION_RESPONSE_INVALID", false);
        }
        byte[] responseBody;
        try {
            responseBody = BoundedHttpResponseReader.readBeforeDeadline(
                    response, maximumResponseBytes, responseDeadline);
        } catch (IOException invalidOrInterruptedBody) {
            boolean boundedRejection = invalidOrInterruptedBody.getMessage() != null
                    && (invalidOrInterruptedBody.getMessage().contains("configured limit")
                        || invalidOrInterruptedBody.getMessage().contains("unavailable"));
            throw failure(boundedRejection
                    ? "NOTIFICATION_RESPONSE_INVALID" : "NOTIFICATION_UNAVAILABLE",
                    !boundedRejection);
        }
        Envelope envelope;
        try {
            envelope = mapper.readerFor(Envelope.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readValue(responseBody);
        } catch (IOException | RuntimeException invalidResponse) {
            throw failure("NOTIFICATION_RESPONSE_INVALID", false);
        }
        if (!valid(envelope, status)) {
            throw failure("NOTIFICATION_RESPONSE_INVALID", false);
        }
        Result result = envelope.data();
        if (result.recipientCount() != 1 || result.notificationId() == null) {
            throw failure("NOTIFICATION_NOT_MATERIALIZED", false);
        }
        return new Acceptance(
                result.intentId(), result.notificationId(), result.recipientCount(),
                result.duplicate(), result.highestChangeVersion());
    }

    private boolean valid(Envelope envelope, int status) {
        if (envelope == null || !"SUCCESS".equals(envelope.status())
                || !Boolean.TRUE.equals(envelope.success())
                || envelope.errorCode() != null || envelope.data() == null
                || envelope.timestamp() == null) return false;
        Result result = envelope.data();
        if (result.intentId() == null || result.recipientCount() == null
                || result.duplicate() == null
                || result.recipientCount() < 0 || result.recipientCount() > 1
                || result.notificationId() == null && result.recipientCount() != 0
                || result.notificationId() != null && result.recipientCount() != 1
                || result.highestChangeVersion() == null
                || !result.highestChangeVersion().matches("^(0|[1-9][0-9]{0,18})$")) {
            return false;
        }
        return status == (Boolean.TRUE.equals(result.duplicate()) ? 200 : 201);
    }

    private void validateClaim(Claim claim) {
        if (claim == null || claim.event() == null || claim.event().eventId() == null
                || claim.event().tenantId() <= 0 || claim.event().meetingId() == null
                || claim.event().occurredAt() == null || claim.recipientUserId() <= 0
                || claim.sourceEventId() == null
                || claim.event().eventType() == null
                || !EventContract.supports(claim.event().eventType())) {
            throw failure("NOTIFICATION_REQUEST_INVALID", false);
        }
    }

    private static URI origin(MeetingInvitationDeliveryProperties properties) {
        URI candidate;
        try {
            candidate = URI.create(properties.getBaseUrl().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Notification URL is invalid.", exception);
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
                    "Notification URL must be an HTTPS origin or approved loopback HTTP.");
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
            throw new IllegalArgumentException("Notification service token is unavailable.");
        }
        return token;
    }

    private static Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Notification timeout is invalid.");
        }
        return value;
    }

    private static HttpClient httpClient(MeetingInvitationDeliveryProperties properties) {
        Duration connect = bounded(
                properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        return HttpClient.newBuilder().connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static MeetingInvitationDeliveryException failure(
            String code, boolean retryable) {
        return new MeetingInvitationDeliveryException(code, retryable);
    }

    private static boolean jsonContentType(HttpResponse<?> response) {
        String value = response.headers().firstValue("Content-Type")
                .orElse("").toLowerCase(Locale.ROOT);
        int parameter = value.indexOf(';');
        String mediaType = parameter < 0 ? value : value.substring(0, parameter);
        return "application/json".equals(mediaType.strip());
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Remote details never cross this trust boundary.
        }
    }

    private record DirectIntent(
            UUID sourceEventId,
            String sourceEventType,
            int sourceSchemaVersion,
            String typeKey,
            List<Long> recipientUserIds,
            String threadKey,
            String locale,
            String reasonCode,
            String actorReference,
            String subjectReference,
            String targetReference,
            Instant occurredAt,
            Instant dueAt,
            boolean actionRequired,
            Map<String, Object> variables) {

        static DirectIntent from(Claim claim) {
            EventContract contract = EventContract.from(claim.event().eventType());
            String meetingId = claim.event().meetingId().toString().toLowerCase(Locale.ROOT);
            return new DirectIntent(
                    claim.sourceEventId(), contract.sourceEventType(), 1,
                    contract.typeKey(), List.of(claim.recipientUserId()),
                    "meeting-invitation:" + meetingId + ":" + claim.recipientUserId(),
                    LOCALE, claim.event().eventType(), ACTOR_REFERENCE,
                    "meeting:" + meetingId,
                    "/meetings/mine?view=preparation&meetingId=" + meetingId,
                    claim.event().occurredAt().toInstant(), null,
                    contract.actionRequired(),
                    Map.of("meetingId", meetingId));
        }
    }

    private record Envelope(
            String status,
            String message,
            Result data,
            String errorCode,
            Instant timestamp,
            Boolean success,
            String correlationId) {
    }

    private record Result(
            UUID intentId,
            UUID notificationId,
            Integer recipientCount,
            Boolean duplicate,
            String highestChangeVersion) {
    }

    private enum EventContract {
        MEETING_SCHEDULED(
                "meetings.meeting.scheduled.v1", "MEETINGS.INVITATION_CREATED", true),
        MEETING_RESCHEDULED(
                "meetings.meeting.rescheduled.v1", "MEETINGS.INVITATION_RESCHEDULED", true),
        MEETING_CANCELLED(
                "meetings.meeting.cancelled.v1", "MEETINGS.INVITATION_CANCELLED", false),
        PREPARATION_MATERIAL_ADDED(
                "meetings.meeting.preparation-material-added.v1",
                "MEETINGS.PREPARATION_MATERIAL_ADDED", false),
        PREPARATION_MATERIAL_REMOVED(
                "meetings.meeting.preparation-material-removed.v1",
                "MEETINGS.PREPARATION_MATERIAL_REMOVED", false);

        private final String sourceEventType;
        private final String typeKey;
        private final boolean actionRequired;

        EventContract(String sourceEventType, String typeKey, boolean actionRequired) {
            this.sourceEventType = sourceEventType;
            this.typeKey = typeKey;
            this.actionRequired = actionRequired;
        }

        String sourceEventType() { return sourceEventType; }
        String typeKey() { return typeKey; }
        boolean actionRequired() { return actionRequired; }

        static boolean supports(String eventType) {
            try {
                valueOf(eventType);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        static EventContract from(String eventType) {
            return valueOf(eventType);
        }
    }
}
