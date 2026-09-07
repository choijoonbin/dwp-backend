package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingPreparationMaterialAccessService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}/materials")
public class MeetingPreparationMaterialAccessController {

    private final MeetingPreparationMaterialAccessService service;

    public MeetingPreparationMaterialAccessController(
            MeetingPreparationMaterialAccessService service) {
        this.service = service;
    }

    @PostMapping("/{materialId}/access-ticket")
    public ResponseEntity<ApiResponse<VideoMeetingPreparationDtos.MaterialAccessTicketResponse>>
            issueAccessTicket(
            @PathVariable UUID meetingId,
            @PathVariable UUID materialId,
            @Valid @RequestBody VideoMeetingPreparationDtos.MaterialAccessRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.success(service.issueAccessTicket(
                        meetingId, materialId, request, correlationId)));
    }
}
