package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
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
import java.util.List;

@Validated
@RestController
@RequestMapping("/v1")
public class VideoMeetingController {

    private final VideoMeetingService service;

    public VideoMeetingController(VideoMeetingService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public ApiResponse<VideoMeetingDtos.CapabilityResponse> capabilities() {
        return ApiResponse.success(service.capabilities());
    }

    @GetMapping("/home")
    public ApiResponse<VideoMeetingDtos.HomeResponse> home(
            @RequestParam(defaultValue = "UTC") String timeZone) {
        return ApiResponse.success(service.home(timeZone));
    }

    @GetMapping("/people")
    public ApiResponse<List<VideoMeetingDtos.MeetingPersonResponse>> people(
            @RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return ApiResponse.success(service.people(q, limit));
    }

    @GetMapping("/meetings")
    public ApiResponse<VideoMeetingDtos.PageResponse<VideoMeetingDtos.MeetingSummary>> meetings(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize) {
        return ApiResponse.success(service.meetings(page, pageSize));
    }

    @PostMapping("/meetings/instant")
    public ApiResponse<VideoMeetingDtos.MeetingCreatedResponse> instant(
            @Valid @RequestBody VideoMeetingDtos.InstantMeetingRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.instant(request, idempotencyKey, correlationId));
    }

    @PostMapping("/meetings")
    public ApiResponse<VideoMeetingDtos.MeetingCreatedResponse> schedule(
            @Valid @RequestBody VideoMeetingDtos.ScheduleMeetingRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.schedule(request, idempotencyKey, correlationId));
    }

    @GetMapping("/meetings/{meetingId}")
    public ApiResponse<VideoMeetingDtos.MeetingDetailResponse> detail(
            @PathVariable UUID meetingId) {
        return ApiResponse.success(service.detail(meetingId));
    }

    @GetMapping("/join-codes/{code}")
    public ApiResponse<VideoMeetingDtos.JoinCodeResolutionResponse> resolveCode(
            @PathVariable String code) {
        return ApiResponse.success(service.resolveCode(code));
    }

    @PostMapping("/meetings/{meetingId}/join-requests")
    public ApiResponse<VideoMeetingDtos.JoinRequestResponse> requestJoin(
            @PathVariable UUID meetingId,
            @Valid @RequestBody(required = false) VideoMeetingDtos.JoinRequestCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.requestJoin(
                meetingId, request, idempotencyKey, correlationId));
    }

    @GetMapping("/meetings/{meetingId}/join-requests/{requestId}")
    public ApiResponse<VideoMeetingDtos.JoinRequestResponse> joinRequest(
            @PathVariable UUID meetingId,
            @PathVariable UUID requestId) {
        return ApiResponse.success(service.joinRequest(meetingId, requestId));
    }

    @GetMapping("/meetings/{meetingId}/lobby")
    public ApiResponse<VideoMeetingDtos.LobbyResponse> lobby(
            @PathVariable UUID meetingId) {
        return ApiResponse.success(service.lobby(meetingId));
    }

    @PostMapping("/meetings/{meetingId}/join-requests/{participantId}/admit")
    public ApiResponse<VideoMeetingDtos.JoinRequestResponse> admit(
            @PathVariable UUID meetingId,
            @PathVariable UUID participantId,
            @Valid @RequestBody VideoMeetingDtos.AdmissionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.decideAdmission(
                meetingId, participantId, true, request, idempotencyKey, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/join-requests/{participantId}/deny")
    public ApiResponse<VideoMeetingDtos.JoinRequestResponse> deny(
            @PathVariable UUID meetingId,
            @PathVariable UUID participantId,
            @Valid @RequestBody VideoMeetingDtos.AdmissionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.decideAdmission(
                meetingId, participantId, false, request, idempotencyKey, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/start")
    public ApiResponse<VideoMeetingDtos.MeetingDetailResponse> start(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingDtos.VersionedCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.start(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/token")
    public ApiResponse<VideoMeetingDtos.ParticipantTokenResponse> token(
            @PathVariable UUID meetingId,
            @Valid @RequestBody(required = false) VideoMeetingDtos.IssueTokenCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.token(meetingId, request, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/connected")
    public ApiResponse<VideoMeetingDtos.ParticipantResponse> connected(
            @PathVariable UUID meetingId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.connected(meetingId, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/leave")
    public ApiResponse<VideoMeetingDtos.ParticipantResponse> leave(
            @PathVariable UUID meetingId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.leave(meetingId, correlationId));
    }

    @PostMapping("/meetings/{meetingId}/end")
    public ApiResponse<VideoMeetingDtos.MeetingDetailResponse> end(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingDtos.VersionedCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.end(
                meetingId, request, idempotencyKey, correlationId));
    }

    @GetMapping("/history")
    public ApiResponse<VideoMeetingDtos.PageResponse<VideoMeetingDtos.HistoryItemResponse>> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize) {
        return ApiResponse.success(service.history(page, pageSize));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<VideoMeetingDtos.AdminOverviewResponse> adminOverview(
            @RequestParam(defaultValue = "UTC") String timeZone) {
        return ApiResponse.success(service.adminOverview(timeZone));
    }

    @GetMapping("/admin/policy")
    public ApiResponse<VideoMeetingDtos.PolicyResponse> policy() {
        return ApiResponse.success(service.policy());
    }

    @PutMapping("/admin/policy")
    public ApiResponse<VideoMeetingDtos.PolicyResponse> updatePolicy(
            @Valid @RequestBody VideoMeetingDtos.TenantPolicyUpdateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updatePolicy(
                request, idempotencyKey, correlationId));
    }
}
