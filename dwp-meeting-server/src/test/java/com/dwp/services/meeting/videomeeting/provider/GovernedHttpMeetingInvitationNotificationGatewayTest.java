package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryException;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Event;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedHttpMeetingInvitationNotificationGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sendsExactIdentityHeadersAndImmutablePerRecipientContract() throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        Claim claim = claim("MEETING_SCHEDULED");
        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));

        var acceptance = adapter(client).deliver(claim);

        assertThat(acceptance.intentId()).isEqualTo(INTENT_ID);
        assertThat(acceptance.notificationId()).isEqualTo(UUID.fromString(NOTIFICATION_ID));
        assertThat(acceptance.recipientCount()).isOne();
        assertThat(client.request().uri().toString()).isEqualTo(
                "https://notification.example.com/internal/v1/intents/direct");
        assertThat(client.request().timeout()).contains(java.time.Duration.ofSeconds(3));
        assertThat(client.request().headers().firstValue("X-DWP-Service-Token"))
                .contains("meeting-notification-service-token");
        assertThat(client.request().headers().firstValue("X-DWP-Tenant-ID"))
                .contains("42");
        assertThat(client.request().headers().firstValue("X-DWP-Source-Service"))
                .contains("dwp-meeting-server");
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.path("sourceEventId").asText())
                .isEqualTo(claim.sourceEventId().toString());
        assertThat(body.path("sourceEventType").asText())
                .isEqualTo("meetings.meeting.scheduled.v1");
        assertThat(body.path("typeKey").asText())
                .isEqualTo("MEETINGS.INVITATION_CREATED");
        assertThat(body.path("recipientUserIds").get(0).asLong()).isEqualTo(77L);
        assertThat(body.path("threadKey").asText()).isEqualTo(
                "meeting-invitation:00000000-0000-0000-0000-000000000002:77");
        assertThat(body.path("actorReference").asText()).isEqualTo("urn:dwp:meetings");
        assertThat(body.path("targetReference").asText()).isEqualTo(
                "/meetings/mine?view=preparation&meetingId="
                        + "00000000-0000-0000-0000-000000000002");
        assertThat(body.path("variables").size()).isOne();
        assertThat(body.toString()).doesNotContain(
                "title", "agenda", "email", "joinCode", "token");
    }

    @Test
    void propagatesChildTraceWithoutBorrowingCallerCredentialsOrCorrelation() {
        MockHttpServletRequest inbound = new MockHttpServletRequest("POST", "/v1/meetings");
        String parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        inbound.addHeader("traceparent", parent);
        inbound.addHeader("tracestate", "vendor=value");
        inbound.addHeader("X-Correlation-ID", "caller-correlation");
        inbound.addHeader("X-DWP-Service-Token", "caller-service-token");
        inbound.addHeader("X-DWP-Tenant-ID", "999");
        inbound.addHeader("X-DWP-Source-Service", "caller-service");
        for (String header : new String[]{
                "Authorization", "X-DWP-User-ID", "X-DWP-Roles", "X-DWP-Group-Refs",
                "X-DWP-Permissions", "X-DWP-Service-Identity", "X-DWP-Product-Surface-Token"}) {
            inbound.addHeader(header, "caller-credential");
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));
        Claim claim = claim("MEETING_SCHEDULED");

        adapter(client).deliver(claim);

        assertThat(client.request().headers().allValues("X-Correlation-ID"))
                .containsExactly("meeting-invitation:" + claim.sourceEventId());
        assertThat(client.request().headers().allValues("X-DWP-Service-Token"))
                .containsExactly("meeting-notification-service-token");
        assertThat(client.request().headers().allValues("X-DWP-Tenant-ID"))
                .containsExactly("42");
        assertThat(client.request().headers().allValues("X-DWP-Source-Service"))
                .containsExactly("dwp-meeting-server");
        assertThat(client.request().headers().firstValue("traceparent"))
                .hasValueSatisfying(value -> assertThat(value)
                        .matches("^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$")
                        .isNotEqualTo(parent));
        assertThat(client.request().headers().allValues("tracestate"))
                .containsExactly("vendor=value");
        assertThat(client.request().headers().map().keySet())
                .allSatisfy(name -> assertThat(name.toLowerCase(java.util.Locale.ROOT))
                        .isIn("accept", "content-type", "x-correlation-id",
                                "x-dwp-service-token", "x-dwp-tenant-id",
                                "x-dwp-source-service", "traceparent", "tracestate"));
    }

    @Test
    void preparationContractsExactlyMatchTheRegisteredNotificationSourceTypes()
            throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));

        adapter(client).deliver(claim("PREPARATION_MATERIAL_ADDED"));
        assertThat(mapper.readTree(client.requestBody()).path("sourceEventType").asText())
                .isEqualTo("meetings.meeting.preparation-material-added.v1");
        assertThat(mapper.readTree(client.requestBody()).path("typeKey").asText())
                .isEqualTo("MEETINGS.PREPARATION_MATERIAL_ADDED");
        assertThat(mapper.readTree(client.requestBody()).path("actionRequired").asBoolean())
                .isFalse();

        adapter(client).deliver(claim("PREPARATION_MATERIAL_REMOVED"));
        assertThat(mapper.readTree(client.requestBody()).path("sourceEventType").asText())
                .isEqualTo("meetings.meeting.preparation-material-removed.v1");
        assertThat(mapper.readTree(client.requestBody()).path("typeKey").asText())
                .isEqualTo("MEETINGS.PREPARATION_MATERIAL_REMOVED");
    }

    @Test
    void acceptsOnlyAVisibleMaterializationAndExactDuplicateStatus() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", response(true, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));
        assertThat(adapter(client).deliver(claim("MEETING_RESCHEDULED")).duplicate())
                .isTrue();

        client.respond(201, "application/json", response(false, 0, null)
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_NOT_MATERIALIZED", false);

        client.respond(200, "application/json", response(false, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);

        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .replace("\"duplicate\":false,", "")
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);

        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .replace("\"duplicate\":false", "\"duplicate\":null")
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);

        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .replace("\"recipientCount\":1,", "")
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);

        client.respond(201, "application/json", response(false, 1, NOTIFICATION_ID)
                .replace("\"recipientCount\":1", "\"recipientCount\":null")
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);
    }

    @Test
    void boundsTheEntireResponseBodyReadBeforeTheDeliveryLeaseCanExpire() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respondWithReadDelay(
                201,
                "application/json",
                response(false, 1, NOTIFICATION_ID).getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(5));
        MeetingInvitationDeliveryProperties properties = properties();
        properties.setRequestTimeout(Duration.ofMillis(250));
        var gateway = new GovernedHttpMeetingInvitationNotificationGateway(
                properties, mapper, client);
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> gateway.deliver(claim("MEETING_SCHEDULED")))
                .isInstanceOfSatisfying(MeetingInvitationDeliveryException.class, error -> {
                    assertThat(error.failureCode()).isEqualTo("NOTIFICATION_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                });

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(2));
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void retriesOnlyTransportAndServerFailuresAndClosesRejectedBodies() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(503, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_UNAVAILABLE", true);
        assertThat(client.responseClosed()).isTrue();

        client.respond(422, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_REJECTED", false);

        client.respond(302, "text/html", new byte[8]);
        assertFailure(client, "NOTIFICATION_REJECTED", false);
        assertThat(client.request().method()).isEqualTo("POST");
    }

    @Test
    void failsClosedOnSchemaContentTypeAndBoundViolations() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(201, "text/plain", response(false, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);
        assertThat(client.responseClosed()).isTrue();

        client.respond(201, "application/jsonp", response(false, 1, NOTIFICATION_ID)
                .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);

        client.respond(201, "application/json",
                response(false, 1, NOTIFICATION_ID)
                        .replace("\"status\":\"SUCCESS\"",
                                "\"status\":\"SUCCESS\",\"status\":\"SUCCESS\"")
                        .getBytes(StandardCharsets.UTF_8));
        assertFailure(client, "NOTIFICATION_RESPONSE_INVALID", false);

        MeetingInvitationDeliveryProperties small = properties();
        small.setMaximumResponseBytes(1_024);
        client.respondWithContentLength(
                201, "application/json", new byte[1], 1_025);
        assertThatThrownBy(() -> new GovernedHttpMeetingInvitationNotificationGateway(
                small, mapper, client).deliver(claim("MEETING_SCHEDULED")))
                .isInstanceOf(MeetingInvitationDeliveryException.class)
                .extracting("failureCode").isEqualTo("NOTIFICATION_RESPONSE_INVALID");
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void permitsOnlyHttpsOrExplicitLoopbackHttpAndRequiresDedicatedToken() {
        MeetingInvitationDeliveryProperties properties = properties();
        properties.setBaseUrl("http://notification.example.com");
        assertThatThrownBy(() -> new GovernedHttpMeetingInvitationNotificationGateway(
                properties, mapper, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setBaseUrl("http://localhost:8008");
        properties.setAllowHttp(true);
        assertThat(new GovernedHttpMeetingInvitationNotificationGateway(
                properties, mapper, new CapturingHttpClient())).isNotNull();

        properties.setServiceToken("short");
        assertThatThrownBy(() -> new GovernedHttpMeetingInvitationNotificationGateway(
                properties, mapper, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertFailure(
            CapturingHttpClient client, String code, boolean retryable) {
        assertThatThrownBy(() -> adapter(client).deliver(claim("MEETING_SCHEDULED")))
                .isInstanceOfSatisfying(MeetingInvitationDeliveryException.class, error -> {
                    assertThat(error.failureCode()).isEqualTo(code);
                    assertThat(error.retryable()).isEqualTo(retryable);
                });
    }

    private GovernedHttpMeetingInvitationNotificationGateway adapter(
            CapturingHttpClient client) {
        return new GovernedHttpMeetingInvitationNotificationGateway(
                properties(), mapper, client);
    }

    private MeetingInvitationDeliveryProperties properties() {
        MeetingInvitationDeliveryProperties properties =
                new MeetingInvitationDeliveryProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://notification.example.com");
        properties.setServiceToken("meeting-notification-service-token");
        return properties;
    }

    private Claim claim(String eventType) {
        Event event = new Event(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 42L,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                eventType, OffsetDateTime.parse("2026-09-08T12:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                OffsetDateTime.parse("2026-09-08T12:02:00Z"));
        return new Claim(event, 77L,
                UUID.fromString("00000000-0000-0000-0000-000000000004"), 1,
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                OffsetDateTime.parse("2026-09-08T12:02:00Z"));
    }

    private String response(boolean duplicate, int recipientCount, String notificationId) {
        String notification = notificationId == null ? "null" : "\"" + notificationId + "\"";
        return """
                {"status":"SUCCESS","data":{
                  "intentId":"%s","notificationId":%s,"recipientCount":%d,
                  "duplicate":%s,"highestChangeVersion":"17"},
                 "timestamp":"2026-09-08T12:00:01Z","success":true}
                """.formatted(INTENT_ID, notification, recipientCount, duplicate);
    }

    private static final UUID INTENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String NOTIFICATION_ID =
            "00000000-0000-0000-0000-000000000011";
}
