package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.BlockerCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Artifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoMeetingContentContractTest {

    @Test
    void exposesTheCanonicalGovernedContentRoutes() throws Exception {
        RequestMapping root = VideoMeetingContentController.class
                .getAnnotation(RequestMapping.class);

        assertThat(root.value()).containsExactly("/v1/meetings/{meetingId}");
        assertGet("contentPlan", "/content-plan", UUID.class);
        assertPut("updateContentPlan", "/content-plan", UUID.class,
                VideoMeetingContentDtos.UpdateContentPlanCommand.class,
                String.class, String.class);
        assertPost("acknowledgeNotice", "/content-notices/{noticeId}/acknowledge",
                UUID.class, UUID.class, String.class, String.class);
        assertPost("requestRecording", "/recording/request", UUID.class,
                VideoMeetingContentDtos.RequestRecordingCommand.class,
                String.class, String.class);
        assertPost("stopRecording", "/recording/stop", UUID.class,
                VideoMeetingContentDtos.StopRecordingCommand.class,
                String.class, String.class);
    }

    @Test
    void recordingSessionResponseIsControlStateNotArtifactMetadata() {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 27, 8, 0, 0, 0, ZoneOffset.UTC);
        RecordingSession session = new RecordingSession(
                UUID.randomUUID(), 77, UUID.randomUUID(), 3, UUID.randomUUID(),
                RecordingState.REQUESTED, now, 101L, null, null,
                null, null, null, null, 0);

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(
                VideoMeetingContentDtos.RecordingSessionResponse.from(session));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "recordingSessionId", "state", "planVersion", "noticeId",
                "requestedAt", "stopRequestedAt", "startedAt", "stoppedAt",
                "failureCode", "version");
        assertThat(fieldNames(json)).doesNotContain(
                "objectKey", "storageProvider", "contentType", "sha256",
                "requestedBy", "metadata");
    }

    @Test
    void blockerContractUsesStableMachineCodesAndRetryClassification() {
        VideoMeetingContentDtos.BlockerResponse policy =
                VideoMeetingContentDtos.BlockerResponse.from(BlockerCode.POLICY_NEVER);
        VideoMeetingContentDtos.BlockerResponse egress =
                VideoMeetingContentDtos.BlockerResponse.from(BlockerCode.EGRESS);

        assertThat(policy.code()).isEqualTo("POLICY_NEVER");
        assertThat(policy.category()).isEqualTo("GOVERNANCE");
        assertThat(policy.retryable()).isFalse();
        assertThat(egress.code()).isEqualTo("EGRESS");
        assertThat(egress.category()).isEqualTo("DEPENDENCY");
        assertThat(egress.retryable()).isTrue();
    }

    @Test
    void artifactContractNeverLeaksProviderMetadata() {
        UUID meetingId = UUID.randomUUID();
        var rawMetadata = JsonNodeFactory.instance.objectNode();
        rawMetadata.put("objectKey", "tenant-77/private/recording.mp4");
        rawMetadata.put("signedUrl", "https://storage.invalid/private-token");
        Artifact artifact = new Artifact(
                UUID.randomUUID(), 77, meetingId, "RECORDING", "UNAVAILABLE",
                null, null, null, rawMetadata, 0);

        VideoMeetingDtos.ArtifactResponse response =
                VideoMeetingDtos.ArtifactResponse.from(artifact);

        assertThat(response.metadata().isObject()).isTrue();
        assertThat(response.metadata().isEmpty()).isTrue();
        assertThat(response.metadata().toString())
                .doesNotContain("objectKey", "signedUrl", "private-token");
    }

    @Test
    void mapsGovernanceAndDependencyBlockersTo409And503ErrorEnvelopes() {
        UUID meetingId = UUID.randomUUID();
        VideoMeetingContentService service = mock(VideoMeetingContentService.class);
        VideoMeetingContentController controller = new VideoMeetingContentController(service);
        var request = new VideoMeetingContentDtos.RequestRecordingCommand(3);
        var stop = new VideoMeetingContentDtos.StopRecordingCommand(0);
        var dependency = blocked(503, BlockerCode.EGRESS);
        var governance = blocked(409, BlockerCode.POLICY_NEVER);
        when(service.requestRecording(meetingId, request, "recording-http-01", "corr-503"))
                .thenReturn(dependency);
        when(service.stopRecording(meetingId, stop, "recording-http-02", "corr-409"))
                .thenReturn(governance);

        var unavailable = controller.requestRecording(
                meetingId, request, "recording-http-01", "corr-503");
        var conflict = controller.stopRecording(
                meetingId, stop, "recording-http-02", "corr-409");

        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody()).isNotNull();
        assertThat(unavailable.getBody().getSuccess()).isFalse();
        assertThat(unavailable.getBody().getErrorCode()).isEqualTo("E5000");
        assertThat(unavailable.getBody().getCorrelationId()).isEqualTo("corr-503");
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody()).isNotNull();
        assertThat(conflict.getBody().getSuccess()).isFalse();
        assertThat(conflict.getBody().getErrorCode()).isEqualTo("E1009");
    }

    private VideoMeetingContentDtos.RecordingCommandResult blocked(
            int status, BlockerCode blocker) {
        return new VideoMeetingContentDtos.RecordingCommandResult(
                status,
                new VideoMeetingContentDtos.RecordingCommandResponse(
                        false, "BLOCKED",
                        List.of(VideoMeetingContentDtos.BlockerResponse.from(blocker)),
                        null, 3));
    }

    private void assertGet(String name, String path, Class<?>... parameters) throws Exception {
        Method method = VideoMeetingContentController.class.getDeclaredMethod(name, parameters);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    }

    private void assertPut(String name, String path, Class<?>... parameters) throws Exception {
        Method method = VideoMeetingContentController.class.getDeclaredMethod(name, parameters);
        assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
    }

    private void assertPost(String name, String path, Class<?>... parameters) throws Exception {
        Method method = VideoMeetingContentController.class.getDeclaredMethod(name, parameters);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    }

    private Set<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }
}
