package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingWorkloadAssertionSignerTest {

    private static final UUID MEETING_ID =
            UUID.fromString("29f14739-0f92-469e-8528-d3731e809f55");
    private static final UUID RUN_ID =
            UUID.fromString("776d0d8e-96e2-4c29-8df5-9f5e3beaf72f");
    private static final UUID JTI =
            UUID.fromString("e0aa6c62-6cd1-45d0-bf94-547934369eb8");
    private static final String CAPABILITY_PATH =
            "/internal/v1/meeting-intelligence/capabilities";

    @Test
    void matchesTheCrossRuntimeGoldenAssertionExactly() {
        MeetingWorkloadAssertionSigner signer = signer(Duration.ofSeconds(30));

        String assertion = signer.sign(
                new ExecutionContext(77, MEETING_ID, RUN_ID, "corr-golden"),
                "GET", CAPABILITY_PATH, null);

        assertThat(assertion).isEqualTo(
                "dwp1.eyJ2IjoxLCJraWQiOiJtZWV0aW5nLXdvcmtsb2FkLXYxIiwibWV0aG9kIjoiR0VUIiwicGF0aCI6Ii9pbnRlcm5hbC92MS9tZWV0aW5nLWludGVsbGlnZW5jZS9jYXBhYmlsaXRpZXMiLCJ0ZW5hbnRJZCI6NzcsIm1lZXRpbmdJZCI6IjI5ZjE0NzM5LTBmOTItNDY5ZS04NTI4LWQzNzMxZTgwOWY1NSIsInJ1bklkIjoiNzc2ZDBkOGUtOTZlMi00YzI5LThkZjUtOWY1ZTNiZWFmNzJmIiwiaWF0IjoxNzg3ODc4ODAwLCJleHAiOjE3ODc4Nzg4MzAsImp0aSI6ImUwYWE2YzYyLTZjZDEtNDVkMC1iZjk0LTU0NzkzNDM2OWViOCIsImJvZHlTaGEyNTYiOiJlM2IwYzQ0Mjk4ZmMxYzE0OWFmYmY0Yzg5OTZmYjkyNDI3YWU0MWU0NjQ5YjkzNGNhNDk1OTkxYjc4NTJiODU1In0.kJN5DXqOTSVfKwlPSgLfQHMjQoTzvET9TFO4uXJTZws");
    }

    @Test
    void payloadContainsOnlyTheCanonicalFields() {
        String assertion = signer(Duration.ofSeconds(30)).sign(
                new ExecutionContext(77, MEETING_ID, RUN_ID, "corr"),
                "GET", CAPABILITY_PATH, null);
        String payload = new String(Base64.getUrlDecoder().decode(
                assertion.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).isEqualTo(
                "{\"v\":1,\"kid\":\"meeting-workload-v1\",\"method\":\"GET\","
                        + "\"path\":\"/internal/v1/meeting-intelligence/capabilities\","
                        + "\"tenantId\":77,"
                        + "\"meetingId\":\"29f14739-0f92-469e-8528-d3731e809f55\","
                        + "\"runId\":\"776d0d8e-96e2-4c29-8df5-9f5e3beaf72f\","
                        + "\"iat\":1787878800,\"exp\":1787878830,"
                        + "\"jti\":\"e0aa6c62-6cd1-45d0-bf94-547934369eb8\","
                        + "\"bodySha256\":\"e3b0c44298fc1c149afbf4c8996fb924"
                        + "27ae41e4649b934ca495991b7852b855\"}");
    }

    @Test
    void changingBodyChangesTheBoundAssertion() {
        MeetingWorkloadAssertionSigner signer = signer(Duration.ofSeconds(30));
        ExecutionContext context = new ExecutionContext(77, MEETING_ID, RUN_ID, "corr");

        assertThat(signer.sign(context, "POST", "/internal/v1/test", "a".getBytes()))
                .isNotEqualTo(signer.sign(
                        context, "POST", "/internal/v1/test", "b".getBytes()));
    }

    @Test
    void rejectsTtlAboveSixtySeconds() {
        assertThatThrownBy(() -> signer(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsShortDecodedSecret() {
        MeetingIntelligenceHttpProperties properties = properties(Duration.ofSeconds(30));
        properties.setAssertionSecretBase64(Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new MeetingWorkloadAssertionSigner(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeKeyIdentifier() {
        MeetingIntelligenceHttpProperties properties = properties(Duration.ofSeconds(30));
        properties.setAssertionKeyId("bad key id");

        assertThatThrownBy(() -> new MeetingWorkloadAssertionSigner(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalMethod() {
        assertThatThrownBy(() -> signer(Duration.ofSeconds(30)).sign(
                new ExecutionContext(77, MEETING_ID, RUN_ID, "corr"),
                "PUT", CAPABILITY_PATH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPathWithQueryMaterial() {
        assertThatThrownBy(() -> signer(Duration.ofSeconds(30)).sign(
                new ExecutionContext(77, MEETING_ID, RUN_ID, "corr"),
                "GET", CAPABILITY_PATH + "?token=bad", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MeetingWorkloadAssertionSigner signer(Duration ttl) {
        return new MeetingWorkloadAssertionSigner(
                properties(ttl),
                Clock.fixed(Instant.ofEpochSecond(1_787_878_800L), ZoneOffset.UTC),
                () -> JTI);
    }

    private MeetingIntelligenceHttpProperties properties(Duration ttl) {
        MeetingIntelligenceHttpProperties properties = new MeetingIntelligenceHttpProperties();
        properties.setAssertionKeyId("meeting-workload-v1");
        properties.setAssertionSecretBase64(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.setAssertionTtl(ttl);
        return properties;
    }
}
