package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactFinalizationService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Hidden
@RequestMapping("/internal/v1/meetings/{meetingId}/artifacts/transcript")
public class MeetingTranscriptArtifactController {

    private final MeetingTranscriptArtifactFinalizationService service;

    public MeetingTranscriptArtifactController(
            MeetingTranscriptArtifactFinalizationService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public ApiResponse<MeetingTranscriptArtifactDtos.TranscriptArtifactResponse> registerTranscript(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingTranscriptArtifactDtos.RegisterTranscriptCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader("X-DWP-Transcript-Finalization-Token") String producerToken,
            @RequestHeader("X-DWP-Transcript-Artifact-Assertion") String producerAssertion) {
        return ApiResponse.success(service.registerTranscript(
                meetingId, request, idempotencyKey, correlationId,
                producerToken, producerAssertion));
    }

    @PostMapping("/finalize")
    public ApiResponse<MeetingTranscriptArtifactDtos.TranscriptArtifactResponse> finalizeTranscript(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader("X-DWP-Transcript-Finalization-Token") String producerToken,
            @RequestHeader("X-DWP-Transcript-Artifact-Assertion") String producerAssertion) {
        return ApiResponse.success(service.finalizeTranscript(
                meetingId, request, idempotencyKey, correlationId,
                producerToken, producerAssertion));
    }
}
