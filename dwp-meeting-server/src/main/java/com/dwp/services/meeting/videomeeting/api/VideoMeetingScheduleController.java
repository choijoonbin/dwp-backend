package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingScheduleService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
@RequestMapping("/v1")
public class VideoMeetingScheduleController {
    private final VideoMeetingScheduleService service;

    public VideoMeetingScheduleController(VideoMeetingScheduleService service) {
        this.service = service;
    }

    @PostMapping("/meeting-series")
    public ApiResponse<VideoMeetingDtos.MeetingCreatedResponse> createSeries(
            @Valid @RequestBody VideoMeetingScheduleDtos.CreateSeriesRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createSeries(request, idempotencyKey, correlationId));
    }

    @PostMapping("/meeting-series/preview")
    public ApiResponse<VideoMeetingScheduleDtos.SeriesPreviewResponse> previewSeries(
            @Valid @RequestBody VideoMeetingScheduleDtos.SeriesPreviewRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.previewSeries(request));
    }

    @GetMapping("/meetings/{meetingId}/schedule")
    public ApiResponse<VideoMeetingScheduleDtos.ScheduleStateResponse> schedule(
            @PathVariable UUID meetingId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.read(meetingId));
    }

    @PutMapping("/meetings/{meetingId}/schedule")
    public ApiResponse<VideoMeetingScheduleDtos.ScheduleStateResponse> reschedule(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingScheduleDtos.RescheduleRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.reschedule(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/schedule/preview")
    public ApiResponse<VideoMeetingScheduleDtos.SeriesPreviewResponse> previewReschedule(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingScheduleDtos.RescheduleRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.previewReschedule(meetingId, request));
    }

    @PostMapping("/meetings/{meetingId}/cancel")
    public ApiResponse<VideoMeetingScheduleDtos.ScheduleStateResponse> cancel(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingScheduleDtos.CancelRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.cancel(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/cancel/preview")
    public ApiResponse<VideoMeetingScheduleDtos.CancellationPreviewResponse> previewCancel(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingScheduleDtos.CancelPreviewRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.previewCancellation(meetingId, request));
    }
}
