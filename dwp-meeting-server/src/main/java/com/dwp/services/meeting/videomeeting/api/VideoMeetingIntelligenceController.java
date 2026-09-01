package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}/intelligence")
public class VideoMeetingIntelligenceController {

    private final VideoMeetingIntelligenceService service;

    public VideoMeetingIntelligenceController(VideoMeetingIntelligenceService service) {
        this.service = service;
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<VideoMeetingIntelligenceDtos.RunResponse>> createRun(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingIntelligenceDtos.CreateRunCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                service.createRun(meetingId, request, idempotencyKey, correlationId)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<VideoMeetingIntelligenceDtos.RunResponse> run(
            @PathVariable UUID meetingId,
            @PathVariable UUID runId) {
        return ApiResponse.success(service.run(meetingId, runId));
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReportResponse> report(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId) {
        return ApiResponse.success(service.report(meetingId, reportId));
    }

    @GetMapping("/reports/latest")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReportResponse> latestReport(
            @PathVariable UUID meetingId) {
        return ApiResponse.success(service.latestReport(meetingId));
    }

    @GetMapping("/reports/latest-published")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReportResponse> latestPublishedReport(
            @PathVariable UUID meetingId) {
        return ApiResponse.success(service.latestPublishedReport(meetingId));
    }

    @PostMapping("/reports/{reportId}/review")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReportResponse> review(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId,
            @Valid @RequestBody VideoMeetingIntelligenceDtos.ReviewCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.review(meetingId, reportId, request, correlationId));
    }

    @PostMapping("/reports/{reportId}/publish")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReportResponse> publish(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId,
            @Valid @RequestBody VideoMeetingIntelligenceDtos.VersionCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.publish(meetingId, reportId, request, correlationId));
    }

    @DeleteMapping("/reports/{reportId}")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReportResponse> delete(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId,
            @RequestParam long expectedVersion,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.delete(
                meetingId, reportId, expectedVersion, correlationId));
    }

    @PutMapping("/reports/{reportId}/acl/{principalUserId}")
    public ApiResponse<VideoMeetingIntelligenceDtos.GrantResponse> grant(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId,
            @PathVariable long principalUserId,
            @Valid @RequestBody VideoMeetingIntelligenceDtos.GrantCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.grant(
                meetingId, reportId, principalUserId, request, correlationId));
    }

    @GetMapping("/reports/{reportId}/reviewer-assignments")
    public ApiResponse<VideoMeetingIntelligenceDtos.ReviewerAssignmentsResponse>
            reviewerAssignments(
                    @PathVariable UUID meetingId,
                    @PathVariable UUID reportId) {
        return ApiResponse.success(service.reviewerAssignments(meetingId, reportId));
    }

    @DeleteMapping("/reports/{reportId}/acl/{principalUserId}/{permission}")
    public ApiResponse<Void> revoke(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId,
            @PathVariable long principalUserId,
            @PathVariable String permission,
            @RequestParam("expectedReportVersion") long expectedReportVersion,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        service.revoke(
                meetingId, reportId, principalUserId, permission,
                expectedReportVersion, correlationId);
        return ApiResponse.success(null);
    }
}
