package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentService;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingRecordingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}")
public class VideoMeetingContentController {

    private final VideoMeetingContentService service;
    private final VideoMeetingRecordingService recording;

    public VideoMeetingContentController(
            VideoMeetingContentService service,
            VideoMeetingRecordingService recording) {
        this.service = service;
        this.recording = recording;
    }

    @GetMapping("/content-plan")
    public ApiResponse<VideoMeetingContentDtos.ContentPlanResponse> contentPlan(
            @PathVariable UUID meetingId) {
        return ApiResponse.success(service.contentPlan(meetingId));
    }

    @PutMapping("/content-plan")
    public ApiResponse<VideoMeetingContentDtos.ContentPlanResponse> updateContentPlan(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingContentDtos.UpdateContentPlanCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateContentPlan(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/content-notices/{noticeId}/acknowledge")
    public ApiResponse<VideoMeetingContentDtos.NoticeAcknowledgementResponse> acknowledgeNotice(
            @PathVariable UUID meetingId,
            @PathVariable UUID noticeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.acknowledgeNotice(
                meetingId, noticeId, idempotencyKey, correlationId));
    }

    @PostMapping("/recording/request")
    public ResponseEntity<ApiResponse<VideoMeetingContentDtos.RecordingCommandResponse>>
            requestRecording(
                    @PathVariable UUID meetingId,
                    @Valid @RequestBody VideoMeetingContentDtos.RequestRecordingCommand request,
                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                    @RequestHeader(value = "X-Correlation-ID", required = false)
                    String correlationId) {
        return commandResponse(recording.requestRecording(
                meetingId, request, idempotencyKey, correlationId), correlationId);
    }

    @PostMapping("/recording/stop")
    public ResponseEntity<ApiResponse<VideoMeetingContentDtos.RecordingCommandResponse>>
            stopRecording(
                    @PathVariable UUID meetingId,
                    @Valid @RequestBody VideoMeetingContentDtos.StopRecordingCommand request,
                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                    @RequestHeader(value = "X-Correlation-ID", required = false)
                    String correlationId) {
        return commandResponse(recording.stopRecording(
                meetingId, request, idempotencyKey, correlationId), correlationId);
    }

    private ResponseEntity<ApiResponse<VideoMeetingContentDtos.RecordingCommandResponse>>
            commandResponse(
                    VideoMeetingContentDtos.RecordingCommandResult result,
                    String correlationId) {
        if (result.accepted()) {
            return ResponseEntity.ok(ApiResponse.success(result.response()));
        }
        ErrorCode error = result.httpStatus() == 503
                ? ErrorCode.EXTERNAL_SERVICE_ERROR : ErrorCode.RESOURCE_CONFLICT;
        return ResponseEntity.status(result.httpStatus()).body(ApiResponse.error(
                error, "The recording command was blocked by governed readiness checks.",
                result.response(), correlationId));
    }
}
