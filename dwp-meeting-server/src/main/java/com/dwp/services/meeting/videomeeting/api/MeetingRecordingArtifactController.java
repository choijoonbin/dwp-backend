package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingArtifactFinalizationService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Hidden
@RestController
@RequestMapping("/internal/v1/meetings/{meetingId}/artifacts/recording")
public class MeetingRecordingArtifactController {

    private final MeetingRecordingArtifactFinalizationService service;

    public MeetingRecordingArtifactController(
            MeetingRecordingArtifactFinalizationService service) {
        this.service = service;
    }

    @PostMapping("/finalize")
    public ApiResponse<MeetingRecordingArtifactDtos.RecordingArtifactResponse>
            finalizeRecording(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingRecordingArtifactDtos.FinalizeRecordingCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId,
            @RequestHeader("X-DWP-Recording-Artifact-Finalization-Token")
            String producerToken,
            @RequestHeader("X-DWP-Recording-Artifact-Assertion")
            String producerAssertion) {
        return ApiResponse.success(service.finalizeRecording(
                meetingId, request, idempotencyKey, correlationId,
                producerToken, producerAssertion));
    }
}
