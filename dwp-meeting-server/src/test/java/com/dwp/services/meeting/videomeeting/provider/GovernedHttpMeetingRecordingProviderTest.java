package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedHttpMeetingRecordingProviderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void capabilityUsesBoundedHttpsProbeAndReturnsOnlyVerifiedReadiness() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-capability-v1","available":true,
                 "egressAvailable":true,"storageAvailable":true,
                 "speechToTextAvailable":true,"deletionAvailable":true,
                 "cryptoShredAvailable":true,"customerManagedStorage":true,
                 "providerRetentionDisabled":true,"processingRegion":"ap-northeast-2",
                 "providerCode":"GOVERNED_EGRESS"}
                """).getBytes());

        var capability = provider(properties(), client).capability();

        assertThat(capability.available()).isTrue();
        assertThat(capability.egressAvailable()).isTrue();
        assertThat(capability.storageAvailable()).isTrue();
        assertThat(capability.speechToTextAvailable()).isTrue();
        assertThat(capability.deletionAvailable()).isTrue();
        assertThat(capability.cryptoShredAvailable()).isTrue();
        assertThat(client.request().method()).isEqualTo("GET");
        assertThat(client.request().uri().toString()).isEqualTo(
                "https://recording.example.test/internal/v1/meeting-recording/capability");
        assertThat(header(client, "X-DWP-Meeting-Recording-Token"))
                .isEqualTo("r".repeat(32));
    }

    @Test
    void commandCarriesOnlyGovernedIdentifiersAndSignedBodyBinding() throws Exception {
        UUID sessionId = UUID.randomUUID();
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-command-v1",
                 "recordingSessionId":"%s","commandState":"STARTED",
                 "providerCommandId":"provider-command-001"}
                """.formatted(sessionId)).getBytes());
        var command = new MeetingRecordingProvider.Command(
                77, UUID.randomUUID(), sessionId, 4, UUID.randomUUID(),
                "tenant-77-room", "corr-recording-001");

        var receipt = provider(properties(), client).start(command);

        assertThat(receipt.recordingSessionId()).isEqualTo(sessionId);
        assertThat(receipt.commandState()).isEqualTo("STARTED");
        assertThat(client.request().method()).isEqualTo("POST");
        assertThat(client.request().uri().getPath()).isEqualTo(
                "/internal/v1/meeting-recording/start");
        assertThat(header(client, "Idempotency-Key")).isEqualTo("START:" + sessionId);
        assertThat(header(client, "X-DWP-Meeting-Workload-Assertion")).startsWith("dwp1.");
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "schemaVersion", "commandType", "tenantId", "meetingId",
                        "recordingSessionId", "planVersion", "noticeId", "providerRoomName");
        assertThat(body.toString()).doesNotContain(
                "transcript", "objectKey", "storageCredential", "participantName");
    }

    @Test
    void capabilityFailsClosedOnUnknownResponseFields() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-capability-v1","available":true,
                 "egressAvailable":true,"storageAvailable":true,
                 "speechToTextAvailable":true,"deletionAvailable":true,
                 "cryptoShredAvailable":true,"customerManagedStorage":true,
                 "providerRetentionDisabled":true,"processingRegion":"ap-northeast-2",
                 "providerCode":"GOVERNED_EGRESS",
                 "objectKey":"must-not-exist"}
                """).getBytes());

        assertThat(provider(properties(), client).capability())
                .isEqualTo(MeetingRecordingProvider.Capability.unavailable());
    }

    @Test
    void capabilityFailsClosedUnlessStorageRetentionAndRegionAreGoverned() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-capability-v1","available":true,
                 "egressAvailable":true,"storageAvailable":true,
                 "speechToTextAvailable":true,"deletionAvailable":false,
                 "cryptoShredAvailable":false,"customerManagedStorage":false,
                 "providerRetentionDisabled":false,"processingRegion":"us-east-1",
                 "providerCode":"GOVERNED_EGRESS"}
                """).getBytes());

        assertThat(provider(properties(), client).capability().available()).isFalse();
    }

    @Test
    void commandNeverTreatsAnAsynchronousAcceptanceAsRecordingEvidence() {
        UUID sessionId = UUID.randomUUID();
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-command-v1",
                 "recordingSessionId":"%s","commandState":"ACCEPTED",
                 "providerCommandId":"provider-command-async"}
                """.formatted(sessionId)).getBytes());
        var command = new MeetingRecordingProvider.Command(
                77, UUID.randomUUID(), sessionId, 4, UUID.randomUUID(),
                "tenant-77-room", "corr-recording-async");

        assertThatThrownBy(() -> provider(properties(), client).start(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting recording provider is unavailable.");
    }

    @Test
    void accessTicketIsBodyBoundShortLivedAndRestrictedToThePlaybackHost()
            throws Exception {
        UUID meetingId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s",
                 "requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://media.example.test/playback/recording?token=short-lived-ticket-001",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), now.plusMinutes(1))).getBytes());
        var request = new MeetingRecordingProvider.AccessRequest(
                77, meetingId, artifactId, 101, "BROKER",
                "tenant-77/opaque-recording", "video/mp4", "a".repeat(64), 4,
                now.plusSeconds(90), "corr-recording-access-001");

        var ticket = provider(properties(), client).issueAccessTicket(request);

        assertThat(ticket.artifactId()).isEqualTo(artifactId);
        assertThat(ticket.accessUri().getHost()).isEqualTo("media.example.test");
        assertThat(ticket.expiresAt()).isBeforeOrEqualTo(request.expiresNoLaterThan());
        assertThat(client.request().uri().getPath()).isEqualTo(
                "/internal/v1/meeting-recording/access-ticket");
        assertThat(header(client, "X-DWP-Recording-Artifact-ID"))
                .isEqualTo(artifactId.toString());
        assertThat(header(client, "X-DWP-Requester-User-ID")).isEqualTo("101");
        assertThat(header(client, "X-DWP-Meeting-Workload-Assertion")).startsWith("dwp1.");
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "schemaVersion", "tenantId", "meetingId", "artifactId",
                        "requesterUserId", "storageProvider", "objectKey", "contentType",
                        "sourceSha256", "artifactVersion", "expiresNoLaterThan");
        assertThat(body.toString()).doesNotContain(
                "accessUrl", "signedUrl", "participantName", "serviceToken");
    }

    @Test
    void accessTicketFailsClosedOnWrongHostHttpExpiryOrProviderPayload() {
        UUID artifactId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String response : java.util.List.of(
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://evil.example.test/playback/x",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), now.plusMinutes(1)),
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"http://media.example.test/playback/x",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), now.plusMinutes(1)),
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://media.example.test/playback/x",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), now.minusSeconds(1)),
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://media.example.test/playback/x",
                 "expiresAt":"%s","objectKey":"must-not-be-returned"}
                """.formatted(artifactId, "a".repeat(64), now.plusMinutes(1)),
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://media.example.test/playback/tenant-77/opaque-recording?token=opaque-access-token-001",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), now.plusMinutes(1)),
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://media.example.test/playback/%s?token=opaque-access-token-001",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), "a".repeat(64),
                        now.plusMinutes(1)),
                """
                {"schemaVersion":"meeting-recording-access-ticket-v1",
                 "artifactId":"%s","requesterUserId":101,"artifactVersion":4,
                 "sourceSha256":"%s",
                 "accessUrl":"https://media.example.test/playback/%s?token=opaque-access-token-001",
                 "expiresAt":"%s"}
                """.formatted(artifactId, "a".repeat(64), "%61".repeat(64),
                        now.plusMinutes(1)))) {
            CapturingHttpClient client = new CapturingHttpClient();
            client.respond(200, "application/json", response.getBytes());
            var request = new MeetingRecordingProvider.AccessRequest(
                    77, UUID.randomUUID(), artifactId, 101, "BROKER",
                    "tenant-77/opaque-recording", "video/mp4", "a".repeat(64), 4,
                    now.plusSeconds(90), "corr-recording-access-002");

            assertThatThrownBy(() -> provider(properties(), client).issueAccessTicket(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Meeting recording provider is unavailable.");
        }
    }

    @Test
    void deletionIsSignedIdempotentAndAcceptsAnOldReceiptAfterCrashRecovery()
            throws Exception {
        UUID artifactId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-recording-delete-v1",
                 "artifactId":"%s","artifactVersion":7,"deletionBindingSha256":"%s",
                 "deletionState":"DELETED","cryptoShredded":true,
                 "providerDeletionId":"provider-delete-001","deletedAt":"%s"}
                """.formatted(artifactId, "b".repeat(64), now.minusDays(30))).getBytes());
        var request = new MeetingRecordingProvider.DeleteRequest(
                77, UUID.randomUUID(), artifactId, "BROKER",
                "tenant-77/opaque-recording", "b".repeat(64), 7,
                "corr-recording-delete-001");

        var receipt = provider(properties(), client).delete(request);

        assertThat(receipt.artifactId()).isEqualTo(artifactId);
        assertThat(receipt.artifactVersion()).isEqualTo(7);
        assertThat(receipt.providerDeletionId()).isEqualTo("provider-delete-001");
        assertThat(client.request().uri().getPath()).isEqualTo(
                "/internal/v1/meeting-recording/delete");
        assertThat(header(client, "Idempotency-Key")).isEqualTo("DELETE:" + artifactId);
        assertThat(header(client, "X-DWP-Meeting-Workload-Assertion")).startsWith("dwp1.");
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "schemaVersion", "tenantId", "meetingId", "artifactId",
                        "storageProvider", "objectKey", "deletionBindingSha256",
                        "artifactVersion");
        assertThat(body.toString()).doesNotContain(
                "accessUrl", "participantName", "storageCredential");
        assertWorkloadAssertion(
                client, 77, request.meetingId(), artifactId,
                "/internal/v1/meeting-recording/delete");
    }

    @Test
    void deletionFailsClosedOnWrongDigestOrMissingCryptoShredEvidence() {
        UUID artifactId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String response : java.util.List.of(
                """
                {"schemaVersion":"meeting-recording-delete-v1",
                 "artifactId":"%s","artifactVersion":7,"deletionBindingSha256":"%s",
                 "deletionState":"DELETED","cryptoShredded":true,
                 "providerDeletionId":"provider-delete-001","deletedAt":"%s"}
                """.formatted(artifactId, "c".repeat(64), now),
                """
                {"schemaVersion":"meeting-recording-delete-v1",
                 "artifactId":"%s","artifactVersion":7,"deletionBindingSha256":"%s",
                 "deletionState":"DELETED","cryptoShredded":false,
                 "providerDeletionId":"provider-delete-001","deletedAt":"%s"}
                """.formatted(artifactId, "b".repeat(64), now))) {
            CapturingHttpClient client = new CapturingHttpClient();
            client.respond(200, "application/json", response.getBytes());
            var request = new MeetingRecordingProvider.DeleteRequest(
                    77, UUID.randomUUID(), artifactId, "BROKER",
                    "tenant-77/opaque-recording", "b".repeat(64), 7,
                    "corr-recording-delete-002");

            assertThatThrownBy(() -> provider(properties(), client).delete(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Meeting recording provider is unavailable.");
        }
    }

    @Test
    void commandRejectsOversizeResponseWithoutLeakingProviderBody() {
        MeetingRecordingHttpProperties properties = properties();
        properties.setMaximumResponseBytes(1_024);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respondWithContentLength(
                200, "application/json", "private provider error".getBytes(), 1_025);

        assertThatThrownBy(() -> provider(properties, client).start(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting recording provider is unavailable.")
                .hasNoCause();
        assertThat(client.sendCount()).isOne();
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsNonHttpsOrNonAllowlistedOriginsAndMissingSigningMaterial() {
        MeetingRecordingHttpProperties http = properties();
        http.setBaseUrl("http://recording.example.test");
        assertThatThrownBy(() -> provider(http, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);

        MeetingRecordingHttpProperties outside = properties();
        outside.setAllowedHosts(Set.of("other.example.test"));
        assertThatThrownBy(() -> provider(outside, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);

        MeetingRecordingHttpProperties unsigned = properties();
        unsigned.setAssertionSecretBase64("");
        assertThatThrownBy(() -> provider(unsigned, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GovernedHttpMeetingRecordingProvider provider(
            MeetingRecordingHttpProperties properties, CapturingHttpClient client) {
        return new GovernedHttpMeetingRecordingProvider(
                properties, mapper, new MeetingWorkloadAssertionSigner(properties), client);
    }

    private void assertWorkloadAssertion(
            CapturingHttpClient client,
            long tenantId,
            UUID meetingId,
            UUID runId,
            String path) throws Exception {
        String assertion = header(client, "X-DWP-Meeting-Workload-Assertion");
        String[] parts = assertion.split("\\.", -1);
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("dwp1");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("v").asInt()).isEqualTo(1);
        assertThat(payload.get("kid").asText()).isEqualTo("recording-workload-v1");
        assertThat(payload.get("method").asText()).isEqualTo("POST");
        assertThat(payload.get("path").asText()).isEqualTo(path);
        assertThat(payload.get("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(payload.get("meetingId").asText()).isEqualTo(meetingId.toString());
        assertThat(payload.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(payload.get("exp").asLong() - payload.get("iat").asLong())
                .isEqualTo(30);
        assertThat(UUID.fromString(payload.get("jti").asText())).isNotNull();
        String bodySha = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(client.requestBody()));
        assertThat(payload.get("bodySha256").asText()).isEqualTo(bodySha);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] expected = mac.doFinal(
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(expected);
    }

    private MeetingRecordingHttpProperties properties() {
        var properties = new MeetingRecordingHttpProperties();
        properties.setProvider("http");
        properties.setBaseUrl("https://recording.example.test");
        properties.setAllowedHosts(Set.of("recording.example.test"));
        properties.setAccessTicketAllowedHosts(Set.of("media.example.test"));
        properties.setServiceToken("r".repeat(32));
        properties.setProcessingRegion("ap-northeast-2");
        properties.setAssertionKeyId("recording-workload-v1");
        properties.setAssertionSecretBase64(
                Base64.getEncoder().encodeToString(new byte[32]));
        properties.setAssertionTtl(Duration.ofSeconds(30));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.setAccessTicketTtl(Duration.ofMinutes(2));
        return properties;
    }

    private MeetingRecordingProvider.Command command() {
        return new MeetingRecordingProvider.Command(
                77, UUID.randomUUID(), UUID.randomUUID(), 4, UUID.randomUUID(),
                "tenant-77-room", "corr-recording-001");
    }

    private String header(CapturingHttpClient client, String name) {
        return client.request().headers().firstValue(name).orElseThrow();
    }
}
