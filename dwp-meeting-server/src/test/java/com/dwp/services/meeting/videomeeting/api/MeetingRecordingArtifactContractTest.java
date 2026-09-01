package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingArtifactRepository;
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

class MeetingRecordingArtifactContractTest {

    @Test
    void exposesOnlyAnInternalFinalizationRouteWithDedicatedInboundCredentials()
            throws Exception {
        RequestMapping root = MeetingRecordingArtifactController.class
                .getAnnotation(RequestMapping.class);
        Method method = MeetingRecordingArtifactController.class.getDeclaredMethod(
                "finalizeRecording", UUID.class,
                MeetingRecordingArtifactDtos.FinalizeRecordingCommand.class,
                String.class, String.class, String.class, String.class);

        assertThat(root.value()).containsExactly(
                "/internal/v1/meetings/{meetingId}/artifacts/recording");
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/finalize");
        Set<String> headers = java.util.Arrays.stream(method.getParameterAnnotations())
                .flatMap(java.util.Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .map(RequestHeader::value)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(headers).containsExactlyInAnyOrder(
                "Idempotency-Key", "X-Correlation-ID",
                "X-DWP-Recording-Artifact-Finalization-Token",
                "X-DWP-Recording-Artifact-Assertion");
        assertThat(MeetingRecordingArtifactController.class
                .isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isTrue();
    }

    @Test
    void recordingFinalizationAndPlaybackResponsesNeverExposeStorageOrProducerEvidence() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T00:00:00Z");
        UUID artifactId = UUID.randomUUID();
        var artifact = new MeetingRecordingArtifactRepository.RecordingArtifact(
                artifactId, 77, UUID.randomUUID(), "AVAILABLE", "BROKER",
                "tenant-77/opaque-recording", "video/mp4", 1_024L,
                "a".repeat(64), now.plusDays(30), "ap-northeast-2",
                UUID.randomUUID(), "b".repeat(64), UUID.randomUUID(), 4L,
                "GOVERNED_EGRESS", "recording-finalize-0001", "c".repeat(64),
                now, 101L, null, 1);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode finalized = mapper.valueToTree(
                MeetingRecordingArtifactDtos.RecordingArtifactResponse.from(artifact));
        JsonNode ticket = mapper.valueToTree(
                new MeetingRecordingAccessDtos.AccessTicketResponse(
                        artifactId, 1, "https://media.example.test/playback/token",
                        now.plusMinutes(1), "video/mp4"));

        assertThat(fields(finalized)).containsExactlyInAnyOrder(
                "artifactId", "recordingSessionId", "state", "contentType",
                "sizeBytes", "retentionUntil", "finalizedAt", "version");
        assertThat(fields(ticket)).containsExactlyInAnyOrder(
                "artifactId", "artifactVersion", "accessUrl", "expiresAt", "contentType");
        assertThat(finalized.toString()).doesNotContain(
                "objectKey", "storageProvider", "sourceSha256", "consentSnapshotSha256",
                "producerToken", "producerAssertion", "idempotencyKey", "requestSha256");
    }

    private Set<String> fields(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }
}
