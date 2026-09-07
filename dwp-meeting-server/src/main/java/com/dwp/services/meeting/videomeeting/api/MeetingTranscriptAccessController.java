package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptAccessService;
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
@RequestMapping("/v1/meetings/{meetingId}/artifacts/{artifactId}/transcript")
public class MeetingTranscriptAccessController {

    private final MeetingTranscriptAccessService service;

    public MeetingTranscriptAccessController(MeetingTranscriptAccessService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public ResponseEntity<ApiResponse<MeetingTranscriptAccessDtos.QueryResponse>> query(
            @PathVariable UUID meetingId,
            @PathVariable UUID artifactId,
            @Valid @RequestBody MeetingTranscriptAccessDtos.QueryCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiResponse.success(service.query(
                        meetingId, artifactId, request, correlationId)));
    }
}
