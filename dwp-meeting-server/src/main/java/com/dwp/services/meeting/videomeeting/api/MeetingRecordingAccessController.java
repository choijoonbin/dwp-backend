package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}/artifacts")
public class MeetingRecordingAccessController {

    private final MeetingRecordingAccessService service;

    public MeetingRecordingAccessController(MeetingRecordingAccessService service) {
        this.service = service;
    }

    @PostMapping("/{artifactId}/access-ticket")
    public ResponseEntity<ApiResponse<MeetingRecordingAccessDtos.AccessTicketResponse>>
            issueAccessTicket(
            @PathVariable UUID meetingId,
            @PathVariable UUID artifactId,
            @Valid @RequestBody MeetingRecordingAccessDtos.AccessTicketCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.success(service.issueAccessTicket(
                        meetingId, artifactId, request, correlationId)));
    }
}
