package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

class GovernedHttpMeetingTranscriptSourceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readsStrictHashBoundTranscriptWithoutObjectKey() throws Exception {
        String hash = "a".repeat(64);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-transcript-v1","sourceSha256":"%s",
                 "segments":[{"segmentId":"s1","startMillis":0,
                 "endMillis":1000,"text":"Meeting text"}]}
                """.formatted(hash)).getBytes());
        MeetingTranscriptSource.ReadContext context = context(hash);

        var segments = source(properties(), client).read(context);

        assertThat(segments).hasSize(1);
        assertThat(client.request().method()).isEqualTo("POST");
        assertThat(header(client, "X-DWP-Transcript-Artifact-ID"))
                .isEqualTo(context.artifactId().toString());
        assertThat(header(client, "X-DWP-Source-SHA256")).isEqualTo(hash);
        assertThat(header(client, "X-DWP-Meeting-Workload-Assertion"))
                .startsWith("dwp1.");
        assertThat(client.request().headers().map().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("X-DWP-Object-Key"));
        JsonNode request = mapper.readTree(client.requestBody());
        assertThat(request.get("artifactId").asText())
                .isEqualTo(context.artifactId().toString());
        assertThat(request.get("sourceSha256").asText()).isEqualTo(hash);
        assertAssertion(client, context.tenantId(), context.meetingId(),
                context.runId(), "POST", "/internal/v1/meeting-transcripts/read");
    }

    @Test
    void retentionCapabilityRequiresBoundedOrphanCryptoShredAndSignedServiceProbe()
            throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", """
                {"schemaVersion":"meeting-transcript-retention-capability-v1",
                 "available":true,"deletionAvailable":true,"cryptoShredAvailable":true,
                 "customerManagedStorage":true,"providerRetentionDisabled":true,
                 "orphanCleanupAvailable":true,"maximumOrphanTtlSeconds":300,
                 "legacyLocatorDeletionAvailable":true,
                 "providerCode":"TRANSCRIPT_BROKER","storageProviderCode":"BROKER"}
                """.getBytes());

        var capability = source(properties(), client).retentionCapability();

        assertThat(capability.available()).isTrue();
        assertThat(capability.maximumOrphanTtlSeconds()).isEqualTo(300);
        assertThat(client.request().method()).isEqualTo("GET");
        assertThat(header(client, "X-DWP-Meeting-Transcript-Token"))
                .isEqualTo("s".repeat(32));
        assertServiceAssertion(
                client, "GET", "/internal/v1/meeting-transcripts/retention-capability");
    }

    @Test
    void deletionBindsTheExactSerializedLocatorBodyAndRequiresCryptoShredEvidence()
            throws Exception {
        UUID meetingId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        String binding = "b".repeat(64);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", """
                {"schemaVersion":"meeting-transcript-delete-v1",
                 "artifactId":"%s","artifactVersion":4,
                 "deletionBindingSha256":"%s","deletionState":"DELETED",
                 "cryptoShredded":true,"providerDeletionId":"delete-proof-001",
                 "deletedAt":"%s"}
                """.formatted(artifactId, binding,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)).getBytes());
        var request = new MeetingTranscriptSource.DeleteRequest(
                77, meetingId, artifactId, "BROKER", "opaque/transcript/object",
                binding, 4, "corr-transcript-delete");

        var receipt = source(properties(), client).delete(request);

        assertThat(receipt.artifactId()).isEqualTo(artifactId);
        assertThat(client.request().method()).isEqualTo("POST");
        assertThat(header(client, "Idempotency-Key")).isEqualTo("DELETE:" + artifactId);
        JsonNode body = mapper.readTree(client.requestBody());
        assertThat(body.get("objectKey").asText()).isEqualTo("opaque/transcript/object");
        assertAssertion(client, 77, meetingId, artifactId,
                "POST", "/internal/v1/meeting-transcripts/delete");
    }

    @Test
    void rejectsBrokerHashMismatch() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-transcript-v1","sourceSha256":"%s",
                 "segments":[]}
                """.formatted("b".repeat(64))).getBytes());

        assertThatThrownBy(() -> source(properties(), client).read(context("a".repeat(64))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting transcript broker is unavailable.")
                .hasNoCause();
    }

    @Test
    void rejectsUnknownResponseField() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"schemaVersion":"meeting-transcript-v1","sourceSha256":"%s",
                 "segments":[],"objectKey":"must-not-exist"}
                """.formatted("a".repeat(64))).getBytes());

        assertThatThrownBy(() -> source(properties(), client).read(context("a".repeat(64))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDeclaredOversizeBeforeReadingAndNeverRetries() {
        MeetingTranscriptHttpProperties properties = properties();
        properties.setMaximumResponseBytes(1_024);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respondWithContentLength(
                200, "application/json", "secret".getBytes(), 1_025);

        assertThatThrownBy(() -> source(properties, client).read(context("a".repeat(64))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting transcript broker is unavailable.")
                .hasNoCause();
        assertThat(client.sendCount()).isOne();
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsChunkedOversizeAtLimitPlusOneAndNeverRetries() {
        MeetingTranscriptHttpProperties properties = properties();
        properties.setMaximumResponseBytes(1_024);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", new byte[1_025]);

        assertThatThrownBy(() -> source(properties, client).read(context("a".repeat(64))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting transcript broker is unavailable.")
                .hasNoCause();
        assertThat(client.sendCount()).isOne();
        assertThat(client.responseBytesRead()).isEqualTo(1_025);
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsNonHttpsBroker() {
        MeetingTranscriptHttpProperties properties = properties();
        properties.setBaseUrl("http://transcript.example.test");

        assertThatThrownBy(() -> source(properties, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBrokerOutsideAllowlist() {
        MeetingTranscriptHttpProperties properties = properties();
        properties.setAllowedHosts(Set.of("other.example.test"));

        assertThatThrownBy(() -> source(properties, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GovernedHttpMeetingTranscriptSource source(
            MeetingTranscriptHttpProperties transcript,
            CapturingHttpClient client) {
        MeetingIntelligenceHttpProperties assertion = new MeetingIntelligenceHttpProperties();
        assertion.setAssertionKeyId("meeting-workload-v1");
        assertion.setAssertionSecretBase64(
                Base64.getEncoder().encodeToString(new byte[32]));
        return new GovernedHttpMeetingTranscriptSource(
                transcript, mapper, new MeetingWorkloadAssertionSigner(assertion), client);
    }

    private MeetingTranscriptHttpProperties properties() {
        MeetingTranscriptHttpProperties properties = new MeetingTranscriptHttpProperties();
        properties.setProvider("http");
        properties.setBaseUrl("https://transcript.example.test");
        properties.setAllowedHosts(Set.of("transcript.example.test"));
        properties.setServiceToken("s".repeat(32));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private MeetingTranscriptSource.ReadContext context(String hash) {
        return new MeetingTranscriptSource.ReadContext(
                77, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), hash, "corr-source");
    }

    private String header(CapturingHttpClient client, String name) {
        return client.request().headers().firstValue(name).orElseThrow();
    }

    private void assertAssertion(
            CapturingHttpClient client,
            long tenantId,
            UUID meetingId,
            UUID runId,
            String method,
            String path) throws Exception {
        JsonNode payload = assertionPayload(client);
        assertThat(payload.get("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(payload.get("meetingId").asText()).isEqualTo(meetingId.toString());
        assertThat(payload.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(payload.get("method").asText()).isEqualTo(method);
        assertThat(payload.get("path").asText()).isEqualTo(path);
        assertThat(payload.get("bodySha256").asText()).isEqualTo(java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(client.requestBody())));
    }

    private void assertServiceAssertion(
            CapturingHttpClient client, String method, String path) throws Exception {
        JsonNode payload = assertionPayload(client);
        assertThat(payload.get("scope").asText()).isEqualTo("SERVICE");
        assertThat(payload.get("method").asText()).isEqualTo(method);
        assertThat(payload.get("path").asText()).isEqualTo(path);
        assertThat(payload.get("bodySha256").asText()).isEqualTo(java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0])));
    }

    private JsonNode assertionPayload(CapturingHttpClient client) throws Exception {
        String assertion = header(client, "X-DWP-Meeting-Workload-Assertion");
        String[] parts = assertion.split("\\.", -1);
        assertThat(parts).hasSize(3);
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(mac.doFinal(
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        return payload;
    }
}
