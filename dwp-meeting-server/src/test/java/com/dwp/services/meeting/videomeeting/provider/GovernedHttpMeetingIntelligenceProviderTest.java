package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedHttpMeetingIntelligenceProviderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void capabilityRequestCarriesAllGovernedHeadersAndAssertion() throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", mapper.writeValueAsBytes(
                new MeetingIntelligenceProvider.Capability(
                        true, "agent", "model-v1", "ap-northeast-2",
                        true, true, java.util.List.of("meeting-intelligence-v1"))));
        var provider = provider(properties(), client);
        ExecutionContext context = context();

        assertThat(provider.capability(context).available()).isTrue();

        var request = client.request();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().toString()).isEqualTo(
                "https://agent.example.test/internal/v1/meeting-intelligence/capabilities");
        assertThat(header(request, "X-DWP-Tenant-ID")).isEqualTo("77");
        assertThat(header(request, "X-DWP-Meeting-ID"))
                .isEqualTo(context.meetingId().toString());
        assertThat(header(request, "X-DWP-Intelligence-Run-ID"))
                .isEqualTo(context.runId().toString());
        assertThat(header(request, "X-Correlation-ID")).isEqualTo("corr-http-test");
        assertThat(header(request, "X-DWP-Meeting-Intelligence-Token"))
                .isEqualTo("t".repeat(32));
        assertThat(header(request, "X-DWP-Meeting-Workload-Assertion"))
                .startsWith("dwp1.");
        assertThat(request.headers().map().keySet()).noneMatch(name ->
                name.equalsIgnoreCase("X-DWP-User-ID")
                        || name.equalsIgnoreCase("X-DWP-Object-Key"));
    }

    @Test
    void analyzeBodyContainsOnlyTheFourCanonicalFields() throws Exception {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", minimalAnalysisJson());
        var provider = provider(properties(), client);
        var request = new MeetingIntelligenceProvider.Request(
                "STANDARD_RECAP_V1", "ko-KR", "a".repeat(64),
                java.util.List.of(new MeetingIntelligenceProvider.TranscriptSegment(
                        "s1", 0, 1000, "Meeting text")));

        provider.analyze(context(), request);

        JsonNode json = mapper.readTree(client.requestBody());
        assertThat(json.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "analysisProfile", "outputLanguage", "sourceSha256", "transcript");
    }

    @Test
    void rejectsUnknownCapabilityFields() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", ("""
                {"available":true,"providerCode":"agent","model":"v1",
                 "processingRegion":"ap-northeast-2",
                 "customerDataTrainingDisabled":true,
                 "providerRetentionDisabled":true,
                 "schemaVersions":["meeting-intelligence-v1"],"unexpected":true}
                """).getBytes());

        assertThatThrownBy(() -> provider(properties(), client).capability(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting intelligence provider is unavailable.");
    }

    @Test
    void rejectsNonJsonResponseWithoutLeakingRemoteBody() {
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(500, "text/plain", "secret upstream failure".getBytes());

        assertThatThrownBy(() -> provider(properties(), client).capability(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting intelligence provider is unavailable.")
                .hasNoCause();
    }

    @Test
    void rejectsDeclaredOversizeBeforeReadingAndNeverRetries() {
        var properties = properties();
        properties.setMaximumResponseBytes(1_024);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respondWithContentLength(
                200, "application/json", "secret".getBytes(), 1_025);

        assertThatThrownBy(() -> provider(properties, client).capability(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting intelligence provider is unavailable.")
                .hasNoCause();
        assertThat(client.sendCount()).isOne();
        assertThat(client.responseBytesRead()).isZero();
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsChunkedOversizeAtLimitPlusOneAndNeverRetries() {
        var properties = properties();
        properties.setMaximumResponseBytes(1_024);
        CapturingHttpClient client = new CapturingHttpClient();
        client.respond(200, "application/json", new byte[1_025]);

        assertThatThrownBy(() -> provider(properties, client).capability(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting intelligence provider is unavailable.")
                .hasNoCause();
        assertThat(client.sendCount()).isOne();
        assertThat(client.responseBytesRead()).isEqualTo(1_025);
        assertThat(client.responseClosed()).isTrue();
    }

    @Test
    void rejectsHttpBaseUrl() {
        var properties = properties();
        properties.setBaseUrl("http://agent.example.test");

        assertThatThrownBy(() -> provider(properties, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHostOutsideExactAllowlist() {
        var properties = properties();
        properties.setAllowedHosts(Set.of("other.example.test"));

        assertThatThrownBy(() -> provider(properties, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLocalhostEvenWhenAllowlisted() {
        var properties = properties();
        properties.setBaseUrl("https://localhost");
        properties.setAllowedHosts(Set.of("localhost"));

        assertThatThrownBy(() -> provider(properties, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsShortStaticToken() {
        var properties = properties();
        properties.setServiceToken("short");

        assertThatThrownBy(() -> provider(properties, new CapturingHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GovernedHttpMeetingIntelligenceProvider provider(
            MeetingIntelligenceHttpProperties properties, CapturingHttpClient client) {
        return new GovernedHttpMeetingIntelligenceProvider(
                properties, mapper, new MeetingWorkloadAssertionSigner(properties), client);
    }

    private MeetingIntelligenceHttpProperties properties() {
        var properties = new MeetingIntelligenceHttpProperties();
        properties.setProvider("http");
        properties.setBaseUrl("https://agent.example.test");
        properties.setAllowedHosts(Set.of("agent.example.test"));
        properties.setServiceToken("t".repeat(32));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.setAssertionKeyId("meeting-workload-v1");
        properties.setAssertionSecretBase64(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }

    private ExecutionContext context() {
        return new ExecutionContext(
                77, UUID.fromString("29f14739-0f92-469e-8528-d3731e809f55"),
                UUID.fromString("776d0d8e-96e2-4c29-8df5-9f5e3beaf72f"),
                "corr-http-test");
    }

    private String header(java.net.http.HttpRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow();
    }

    private byte[] minimalAnalysisJson() {
        return ("""
                {"executiveSummary":{"text":"Summary","citations":[{"segmentId":"s1","startMillis":0,"endMillis":900}]},
                 "topics":[],"decisions":[],"actionItems":[],"openQuestions":[],"risks":[],
                 "conversationClimate":{"label":"INSUFFICIENT_EVIDENCE",
                 "signals":["LOW_TRANSCRIPT_EVIDENCE"],"citations":[]}}
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
