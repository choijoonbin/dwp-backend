package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos;
import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupCurrentAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedProductSurfaceMeetingFollowupCurrentAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsTheDedicatedIdentityAndAcceptsFreshSourceBoundEvidence() throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        MeetingFollowupSourceDtos.Request request = request(
                MeetingFollowupSourceDtos.Operation.CREATE, null, 7L);
        client.respond(200, "application/json", response(request, true, null,
                "2026-09-08T12:01:00Z").getBytes(StandardCharsets.UTF_8));
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
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.path("tenantId").asLong()).isEqualTo(42L);
        assertThat(body.path("actorUserId").asLong()).isEqualTo(17L);
        assertThat(body.path("action").asText()).isEqualTo("CREATE");
        assertThat(body.path("source").path("candidateId").asText())
                .isEqualTo(request.source().candidateId().toString());
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
