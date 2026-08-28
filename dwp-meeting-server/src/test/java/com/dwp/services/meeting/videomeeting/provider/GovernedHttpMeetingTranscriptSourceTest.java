package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedHttpMeetingTranscriptSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsStrictHashBoundTranscriptWithoutObjectKey() {
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
        assertThat(client.request().method()).isEqualTo("GET");
        assertThat(header(client, "X-DWP-Transcript-Artifact-ID"))
                .isEqualTo(context.artifactId().toString());
        assertThat(header(client, "X-DWP-Source-SHA256")).isEqualTo(hash);
        assertThat(header(client, "X-DWP-Meeting-Workload-Assertion"))
                .startsWith("dwp1.");
        assertThat(client.request().headers().map().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("X-DWP-Object-Key"));
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
}
