package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingPreparationService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}")
public class VideoMeetingPreparationController {
    private final VideoMeetingPreparationService service;

    public VideoMeetingPreparationController(VideoMeetingPreparationService service) { this.service = service; }

    @GetMapping("/preparation")
    public ApiResponse<VideoMeetingPreparationDtos.PreparationResponse> preparation(
            @PathVariable UUID meetingId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.read(meetingId));
    }

    @PutMapping("/agenda")
    public ApiResponse<VideoMeetingPreparationDtos.PreparationResponse> replaceAgenda(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingPreparationDtos.ReplaceAgendaRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.replaceAgenda(meetingId, request, idempotencyKey, correlationId));
    }

    @PutMapping("/invitation-response")
    public ApiResponse<VideoMeetingPreparationDtos.PreparationResponse> respond(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingPreparationDtos.InvitationResponseRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.respond(meetingId, request, idempotencyKey, correlationId));
    }

    @PutMapping("/my-preparation")
    public ApiResponse<VideoMeetingPreparationDtos.PreparationResponse> updateMyPreparation(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingPreparationDtos.UpdateMyPreparationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.updateMyPreparation(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/materials")
    public ApiResponse<VideoMeetingPreparationDtos.PreparationResponse> registerMaterial(
            @PathVariable UUID meetingId,
            @Valid @RequestBody VideoMeetingPreparationDtos.RegisterMaterialRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.registerMaterial(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/materials/{materialId}/remove")
    public ApiResponse<VideoMeetingPreparationDtos.PreparationResponse> removeMaterial(
            @PathVariable UUID meetingId,
            @PathVariable UUID materialId,
            @Valid @RequestBody VideoMeetingPreparationDtos.RemoveMaterialRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.success(service.removeMaterial(
                meetingId, materialId, request, idempotencyKey, correlationId));
    }
}
