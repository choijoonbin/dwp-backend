package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceReportExportService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}/intelligence/reports")
public class MeetingIntelligenceReportExportController {

    private final MeetingIntelligenceReportExportService service;

    public MeetingIntelligenceReportExportController(
            MeetingIntelligenceReportExportService service) {
        this.service = service;
    }

    @PostMapping(value = "/{reportId}/exports",
            produces = {"application/json", "text/markdown"})
    public ResponseEntity<byte[]> export(
            @PathVariable UUID meetingId,
            @PathVariable UUID reportId,
            @Valid @RequestBody MeetingIntelligenceReportExportDtos.ExportCommand request,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId) {
        MeetingIntelligenceReportExportService.ReportExport export =
                service.export(meetingId, reportId, request, correlationId);
        byte[] content = export.content();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-DWP-Report-Version", Long.toString(export.reportVersion()))
                .header("X-DWP-Content-SHA256", export.payloadSha256())
                .contentType(MediaType.parseMediaType(export.contentType()))
                .contentLength(content.length)
                .body(content);
    }
}
