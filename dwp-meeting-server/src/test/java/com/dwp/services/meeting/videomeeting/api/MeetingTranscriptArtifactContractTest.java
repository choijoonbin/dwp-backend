package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingTranscriptArtifactContractTest {

    @Test
    void internalProducerEndpointIsExcludedFromThePublicOpenApiContract() {
        assertThat(MeetingTranscriptArtifactController.class
                .isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isTrue();
    }

    @Test
    void exposesInternalFinalizationWithDedicatedCredentialAndAssertionHeaders()
            throws Exception {
        RequestMapping root = MeetingTranscriptArtifactController.class
                .getAnnotation(RequestMapping.class);
        Method method = java.util.Arrays.stream(
                        MeetingTranscriptArtifactController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("finalizeTranscript"))
                .findFirst().orElseThrow();

        assertThat(root.value()).containsExactly(
                "/internal/v1/meetings/{meetingId}/artifacts/transcript");
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/finalize");
        Set<String> headers = java.util.Arrays.stream(method.getParameterAnnotations())
                .flatMap(java.util.Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .map(RequestHeader::value)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(headers).contains(
                "Idempotency-Key", "X-DWP-Transcript-Finalization-Token",
                "X-DWP-Transcript-Artifact-Assertion");

        Method registration = java.util.Arrays.stream(
                        MeetingTranscriptArtifactController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("registerTranscript"))
                .findFirst().orElseThrow();
        assertThat(registration.getAnnotation(PostMapping.class).value())
                .containsExactly("/register");
        Set<String> registrationHeaders = java.util.Arrays.stream(
                        registration.getParameterAnnotations())
                .flatMap(java.util.Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .map(RequestHeader::value)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(registrationHeaders).contains(
                "Idempotency-Key", "X-DWP-Transcript-Finalization-Token",
                "X-DWP-Transcript-Artifact-Assertion");
    }

    @Test
    void finalizationResponseNeverExposesObjectKeyCredentialsOrConsentHash() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T00:00:00Z");
        var artifact = new MeetingTranscriptArtifactRepository.TranscriptArtifact(
                UUID.randomUUID(), 77, UUID.randomUUID(), "AVAILABLE",
                "a".repeat(64), now.plusDays(30), true, "ap-northeast-2",
                UUID.randomUUID(), "b".repeat(64), "idempotency-key",
                "c".repeat(64), now, 101L, 1,
                "registration-key", "d".repeat(64), now.minusMinutes(1), 101L,
                4L, "TRANSCRIPT_BROKER", "GOVERNED_STORE", null, null, null);
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(
                MeetingTranscriptArtifactDtos.TranscriptArtifactResponse.from(artifact));
        Set<String> fields = json.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fields).contains(
                "artifactId", "state", "sourceSha256", "processingRegion",
                "contentNoticeId", "retentionUntil", "finalizedAt", "version");
        assertThat(fields).doesNotContain(
                "objectKey", "storageProvider", "idempotencyKey", "requestSha256",
                "consentSnapshotSha256", "producerToken", "producerAssertion");
    }
}
