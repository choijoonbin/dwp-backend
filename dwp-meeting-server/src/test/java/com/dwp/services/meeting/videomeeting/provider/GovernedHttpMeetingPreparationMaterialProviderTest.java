package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedHttpMeetingPreparationMaterialProviderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void ticketRequestIsResourceSignedAndBindsTheCurrentSourceAclContext() throws Exception {
        UUID meetingId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-preparation-material-access-ticket-v1",
                 "materialId":"%s","requesterUserId":41,"materialVersion":3,
                 "referenceBindingSha256":"%s",
                 "accessUrl":"https://files.example.test/meeting-materials/open?ticket=short-lived-ticket-001",
                 "expiresAt":"%s"}
                """.formatted(materialId, "a".repeat(64), now.plusMinutes(1))).getBytes());

        var ticket = provider(properties(), client).issueAccessTicket(request(
                meetingId, materialId, now.plusSeconds(90)));

        assertThat(ticket.materialId()).isEqualTo(materialId);
        assertThat(ticket.accessUri().getHost()).isEqualTo("files.example.test");
        assertThat(client.request().uri().getPath()).isEqualTo(
                "/internal/v1/meeting-preparation-material/access-ticket");
        assertThat(header(client, "X-DWP-Meeting-Material-ID"))
                .isEqualTo(materialId.toString());
        assertThat(header(client, "X-DWP-Requester-User-ID")).isEqualTo("41");
        assertThat(header(client, "X-DWP-Meeting-Workload-Assertion")).startsWith("dwp1.");
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.get("opaqueReference").asText()).isEqualTo("governed/release-brief");
        assertThat(body.get("referenceBindingSha256").asText()).isEqualTo("a".repeat(64));
        assertThat(body.toString()).doesNotContain(
                "accessUrl", "signedUrl", "serviceToken", "participantName");
    }

    @Test
    void ticketFailsClosedOnWrongBindingHostProtocolExpiryOrUnknownPayload() {
        UUID meetingId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String response : java.util.List.of(
                response(materialId, "b".repeat(64),
                        "https://files.example.test/meeting-materials/open", now.plusMinutes(1), ""),
                response(materialId, "a".repeat(64),
                        "https://evil.example.test/meeting-materials/open", now.plusMinutes(1), ""),
                response(materialId, "a".repeat(64),
                        "http://files.example.test/meeting-materials/open", now.plusMinutes(1), ""),
                response(materialId, "a".repeat(64),
                        "https://files.example.test/meeting-materials/open", now.minusSeconds(1), ""),
                response(materialId, "a".repeat(64),
                        "https://files.example.test/meeting-materials/open", now.plusMinutes(1),
                        ",\"opaqueReference\":\"must-not-return\""))) {
            CapturingHttpClient client = new CapturingHttpClient();
            client.respond(200, "application/json", response.getBytes());
            assertThatThrownBy(() -> provider(properties(), client).issueAccessTicket(
                    request(meetingId, materialId, now.plusSeconds(90))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Meeting preparation material provider is unavailable.");
        }
    }

    @Test
    void ticketUrlNeverContainsTheOpaqueReferenceOrItsBinding() {
        UUID materialId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String url : java.util.List.of(
                "https://files.example.test/meeting-materials/governed/release-brief",
                "https://files.example.test/meeting-materials/" + "a".repeat(64))) {
            CapturingHttpClient client = new CapturingHttpClient();
            client.respond(200, "application/json", response(
                    materialId, "a".repeat(64), url, now.plusMinutes(1), "").getBytes());
            assertThatThrownBy(() -> provider(properties(), client).issueAccessTicket(
                    request(UUID.randomUUID(), materialId, now.plusSeconds(90))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void oversizedProviderResponseIsRejectedBeforeTheBodyIsRead() {
        MeetingPreparationMaterialHttpProperties properties = properties();
        properties.setMaximumResponseBytes(1_024);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respondWithContentLength(
                200, "application/json", "private provider error".getBytes(), 1_025);

        assertThatThrownBy(() -> provider(properties, client).issueAccessTicket(
                request(UUID.randomUUID(), UUID.randomUUID(),
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void configurationRejectsNonHttpsNonAllowlistedOrUnsignedProvider() {
        MeetingPreparationMaterialHttpProperties http = properties();
        http.setBaseUrl("http://broker.example.test");
        assertThatThrownBy(() -> provider(http, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);

        MeetingPreparationMaterialHttpProperties outside = properties();
        outside.setAllowedHosts(Set.of("other.example.test"));
        assertThatThrownBy(() -> provider(outside, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);

        MeetingPreparationMaterialHttpProperties unsigned = properties();
        unsigned.setAssertionSecretBase64("");
        assertThatThrownBy(() -> provider(unsigned, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GovernedHttpMeetingPreparationMaterialProvider provider(
            MeetingPreparationMaterialHttpProperties properties,
            CapturingHttpClient client) {
        return new GovernedHttpMeetingPreparationMaterialProvider(
                properties, mapper, new MeetingWorkloadAssertionSigner(properties), client);
    }

    private MeetingPreparationMaterialProvider.AccessRequest request(
            UUID meetingId, UUID materialId, OffsetDateTime expiresNoLaterThan) {
        return new MeetingPreparationMaterialProvider.AccessRequest(
                7, meetingId, materialId, 41, "DWP_FILES", "governed/release-brief",
                "v3", "CONFIDENTIAL", "application/pdf", "c".repeat(64),
                "a".repeat(64), 3, expiresNoLaterThan, "corr-material-access-001");
    }

    private String response(
            UUID materialId,
            String binding,
            String url,
            OffsetDateTime expiresAt,
            String extra) {
        return """
                {"schemaVersion":"meeting-preparation-material-access-ticket-v1",
                 "materialId":"%s","requesterUserId":41,"materialVersion":3,
                 "referenceBindingSha256":"%s","accessUrl":"%s",
                 "expiresAt":"%s"%s}
                """.formatted(materialId, binding, url, expiresAt, extra);
    }

    private MeetingPreparationMaterialHttpProperties properties() {
        var properties = new MeetingPreparationMaterialHttpProperties();
        properties.setProvider("http");
        properties.setBaseUrl("https://broker.example.test");
        properties.setAllowedHosts(Set.of("broker.example.test"));
        properties.setAccessTicketAllowedHosts(Set.of("files.example.test"));
        properties.setAccessTicketPathPrefix("/meeting-materials/");
        properties.setServiceToken("m".repeat(32));
        properties.setAssertionKeyId("material-workload-v1");
        properties.setAssertionSecretBase64(
                Base64.getEncoder().encodeToString(new byte[32]));
        properties.setAssertionTtl(Duration.ofSeconds(30));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.setAccessTicketTtl(Duration.ofMinutes(2));
        return properties;
    }

    private String header(CapturingHttpClient client, String name) {
        return client.request().headers().firstValue(name).orElseThrow();
    }
}
