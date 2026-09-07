package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingScheduleDraftService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/schedule-draft")
public class MeetingScheduleDraftController {

    private final MeetingScheduleDraftService service;

    public MeetingScheduleDraftController(MeetingScheduleDraftService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MeetingScheduleDraftDtos.ScheduleDraftSlotResponse> read(
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.read());
    }

    @PutMapping
    public ApiResponse<MeetingScheduleDraftDtos.ScheduleDraftResponse> save(
            @Valid @RequestBody MeetingScheduleDraftDtos.SaveScheduleDraftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.save(request, idempotencyKey, correlationId));
    }

    @PostMapping("/recurrence-preview")
    public ApiResponse<VideoMeetingScheduleDtos.SeriesPreviewResponse> preview(
            @Valid @RequestBody MeetingScheduleDraftDtos.DraftVersionRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.preview(request));
    }

    @PostMapping("/commit")
    public ApiResponse<VideoMeetingDtos.MeetingCreatedResponse> commit(
            @Valid @RequestBody MeetingScheduleDraftDtos.CommitScheduleDraftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.commit(request, idempotencyKey, correlationId));
    }

    @PostMapping("/discard")
    public ApiResponse<MeetingScheduleDraftDtos.DiscardScheduleDraftResponse> discard(
            @Valid @RequestBody MeetingScheduleDraftDtos.DraftVersionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.discard(request, idempotencyKey, correlationId));
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
