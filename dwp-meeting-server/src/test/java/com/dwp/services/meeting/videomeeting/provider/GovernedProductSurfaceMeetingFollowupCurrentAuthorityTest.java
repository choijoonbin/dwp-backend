package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos;
import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupCurrentAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedProductSurfaceMeetingFollowupCurrentAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sendsTheDedicatedIdentityAndAcceptsFreshSourceBoundEvidence() throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        MeetingFollowupSourceDtos.Request request = request(
                MeetingFollowupSourceDtos.Operation.CREATE, null, 7L);
        client.respond(200, "application/json", response(request, true, null,
                "2026-09-08T12:01:00Z").getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest inbound = new MockHttpServletRequest(
                "POST", "/internal/v1/meeting-followups/resolve");
        inbound.addHeader(HeaderConstants.X_CORRELATION_ID, "meeting-followup-correlation");
        inbound.addHeader(HeaderConstants.TRACE_PARENT,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        inbound.addHeader(HeaderConstants.TRACE_STATE, "vendor=value");
        inbound.addHeader("Authorization", "Bearer borrowed-user-token");
        inbound.addHeader("X-DWP-Service-Token", "borrowed-service-token");
        inbound.addHeader("X-DWP-Product-Surface-Token", "borrowed-product-surface-token");
        inbound.addHeader("X-DWP-Service-Identity", "dwp-platform-server");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
        var adapter = adapter(client);

        MeetingFollowupCurrentAuthority.Decision decision = adapter.authorize(request);

        assertThat(decision.allowed()).isTrue();
        assertThat(client.request().uri().toString()).isEqualTo(
                "https://auth.example.com/internal/auth/v1/meeting-followup-authority/evaluate");
        assertThat(client.request().headers().firstValue(
                "X-DWP-Meeting-Followup-Authority-Token"))
                .contains("meeting-followup-authority-token");
        assertThat(client.request().headers().firstValue("X-DWP-Service-Identity"))
                .contains("dwp-meeting-server");
        assertThat(client.request().headers().firstValue(HeaderConstants.X_CORRELATION_ID))
                .contains("meeting-followup-correlation");
        assertThat(client.request().headers().firstValue(HeaderConstants.TRACE_STATE))
                .contains("vendor=value");
        assertThat(client.request().headers().firstValue(HeaderConstants.TRACE_PARENT)
                .orElseThrow())
                .matches("^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$")
                .doesNotContain("00f067aa0ba902b7");
        assertThat(client.request().headers().firstValue("Authorization")).isEmpty();
        assertThat(client.request().headers().firstValue("X-DWP-Service-Token")).isEmpty();
        assertThat(client.request().headers().firstValue("X-DWP-Product-Surface-Token"))
                .isEmpty();
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.path("tenantId").asLong()).isEqualTo(42L);
        assertThat(body.path("actorUserId").asLong()).isEqualTo(17L);
        assertThat(body.path("action").asText()).isEqualTo("CREATE");
        assertThat(body.path("source").path("candidateId").asText())
                .isEqualTo(request.source().candidateId().toString());
    }

    @Test
    void acceptsExactJsonMediaTypeWithParametersAndRejectsSimilarMediaTypes() {
        CapturingHttpClient client = new CapturingHttpClient();
        MeetingFollowupSourceDtos.Request request = request(
                MeetingFollowupSourceDtos.Operation.READ, null, null);
        byte[] evidence = response(request, true, null,
                "2026-09-08T12:01:00Z").getBytes(StandardCharsets.UTF_8);
        var adapter = adapter(client);
        client.respond(200, "Application/JSON; charset=UTF-8", evidence);

        assertThat(adapter.authorize(request).allowed()).isTrue();
        assertThat(client.responseClosed()).isTrue();

        for (String mediaType : new String[] {
                "application/jsonp", "application/json-seq", "application/problem+json",
                "text/plain"}) {
            client.respond(200, mediaType, evidence);
            assertThat(adapter.authorize(request).denial())
                    .as("Content-Type %s must not establish authority", mediaType)
                    .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);
            assertThat(client.responseBytesRead()).isZero();
            assertThat(client.responseClosed()).isTrue();
        }
    }

    @Test
    void deniesNullEvidenceInsteadOfThrowingAndClosesItsBody() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", "null".getBytes(StandardCharsets.UTF_8));

        assertThat(adapter(client).authorize(request(
                MeetingFollowupSourceDtos.Operation.READ, null, null)).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void boundsAndCancelsTheResponseBodyReadWithinTheAuthorityDeadline() {
        CapturingHttpClient client = new CapturingHttpClient();
        MeetingFollowupSourceDtos.Request request = request(
                MeetingFollowupSourceDtos.Operation.CREATE, null, 7L);
        client.respondWithReadDelay(200, "application/json", response(request, true, null,
                "2026-09-08T12:01:00Z").getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(5));
        MeetingFollowupAuthorityProperties properties = properties();
        properties.setRequestTimeout(Duration.ofMillis(250));
        var adapter = new GovernedProductSurfaceMeetingFollowupCurrentAuthority(
                properties, mapper, client, Clock.fixed(NOW, ZoneOffset.UTC));
        long startedAt = System.nanoTime();

        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(2));
        assertThat(client.request().timeout()).contains(Duration.ofMillis(250));
        assertThat(client.sendCount()).isOne();
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsAnOversizedDeclaredBodyBeforeReadingIt() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respondWithContentLength(200, "application/json", new byte[1], 1_025);
        MeetingFollowupAuthorityProperties properties = properties();
        properties.setMaximumResponseBytes(1_024);
        var adapter = new GovernedProductSurfaceMeetingFollowupCurrentAuthority(
                properties, mapper, client, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(adapter.authorize(request(
                MeetingFollowupSourceDtos.Operation.READ, null, null)).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsEvidenceForAnotherSourceAndExpiredOrOverlongEvidence() {
        CapturingHttpClient client = new CapturingHttpClient();
        MeetingFollowupSourceDtos.Request request = request(
                MeetingFollowupSourceDtos.Operation.CREATE, null, 7L);
        String wrongSource = response(request, true, null, "2026-09-08T12:01:00Z")
                .replace(request.source().candidateId().toString(),
                        "00000000-0000-0000-0000-000000000099");
        client.respond(200, "application/json", wrongSource.getBytes(StandardCharsets.UTF_8));
        var adapter = adapter(client);

        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        client.respond(200, "application/json", response(
                request, true, null, "2026-09-08T11:59:00Z")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        client.respond(200, "application/json", response(
                request, true, null, "2026-09-08T11:59:59Z")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        client.respond(200, "application/json", response(
                request, true, null, "2026-09-08T12:05:00Z")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);
    }

    @Test
    void returnsTheExactAuthDenialAndFailsClosedOnTransportOrSchemaErrors() {
        CapturingHttpClient client = new CapturingHttpClient();
        MeetingFollowupSourceDtos.Request request = request(
                MeetingFollowupSourceDtos.Operation.READ, null, null);
        client.respond(200, "application/json", response(
                request, false, "SCOPE_FORBIDDEN", null).getBytes(StandardCharsets.UTF_8));
        var adapter = adapter(client);

        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.SCOPE_FORBIDDEN);

        client.respond(503, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        client.respond(200, "application/json",
                "{\"tenantId\":42,\"tenantId\":42}".getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        client.respond(200, "application/json", response(
                request, false, "SCOPE_FORBIDDEN", null)
                .replace(request.source().candidateId().toString(), "null")
                .replace("\"candidateId\": \"null\"", "\"candidateId\": null")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);

        client.respond(200, "application/json", response(
                request, false, "SCOPE_FORBIDDEN", null)
                .replace("\"tenantId\": 42", "\"tenantId\": 42.5")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.authorize(request).denial())
                .isEqualTo(MeetingFollowupCurrentAuthority.Denial.AUTHORITY_UNVERIFIED);
    }

    @Test
    void permitsOnlyHttpsOrExplicitLoopbackHttpConfiguration() {
        MeetingFollowupAuthorityProperties properties = properties();
        properties.setBaseUrl("http://auth.example.com");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new GovernedProductSurfaceMeetingFollowupCurrentAuthority(
                        properties, mapper, new CapturingHttpClient(), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setBaseUrl("http://localhost:8001");
        properties.setAllowHttp(true);
        assertThat(new GovernedProductSurfaceMeetingFollowupCurrentAuthority(
                properties, mapper, new CapturingHttpClient(), Clock.systemUTC())).isNotNull();
    }

    private GovernedProductSurfaceMeetingFollowupCurrentAuthority adapter(
            CapturingHttpClient client) {
        return new GovernedProductSurfaceMeetingFollowupCurrentAuthority(
                properties(), mapper, client, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MeetingFollowupAuthorityProperties properties() {
        MeetingFollowupAuthorityProperties properties = new MeetingFollowupAuthorityProperties();
        properties.setProvider("auth-product-surface");
        properties.setBaseUrl("https://auth.example.com");
        properties.setServiceToken("meeting-followup-authority-token");
        return properties;
    }

    private MeetingFollowupSourceDtos.Request request(
            MeetingFollowupSourceDtos.Operation operation,
            Long target,
            Long version) {
        return new MeetingFollowupSourceDtos.Request(
                42L, 17L,
                new MeetingFollowupSourceDtos.Source(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003")),
                operation, target, version);
    }

    private String response(
            MeetingFollowupSourceDtos.Request request,
            boolean allowed,
            String denial,
            String validUntil) {
        String nullableDenial = denial == null ? "null" : "\"" + denial + "\"";
        String evidence = allowed ? "\"evidence-1\"" : "null";
        String authRevision = allowed ? "\"auth-1\"" : "null";
        String policyRevision = allowed ? "\"policy-1\"" : "null";
        String validity = validUntil == null ? "null" : "\"" + validUntil + "\"";
        return """
                {
                  "tenantId": %d,
                  "actorUserId": %d,
                  "source": {
                    "meetingId": "%s",
                    "reportId": "%s",
                    "candidateId": "%s"
                  },
                  "action": "%s",
                  "targetAssigneeUserId": %s,
                  "expectedSourceVersion": %s,
                  "allowed": %s,
                  "denial": %s,
                  "authRevision": %s,
                  "policyRevision": %s,
                  "validUntil": %s,
                  "evidenceRef": %s
                }
                """.formatted(
                request.tenantId(), request.actorUserId(), request.source().meetingId(),
                request.source().reportId(), request.source().candidateId(), request.action(),
                request.targetAssigneeUserId(), request.expectedSourceVersion(), allowed,
                nullableDenial, authRevision, policyRevision, validity, evidence);
    }
}
