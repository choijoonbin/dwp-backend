package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/v1/conversations/{conversationId}/attachments")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping("/uploads")
    public ApiResponse<AttachmentDtos.UploadSession> createUpload(
            @PathVariable UUID conversationId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody AttachmentDtos.CreateUploadRequest request) {
        return ApiResponse.success(service.createUpload(conversationId, request, correlationId));
    }

    @PutMapping(value = "/{attachmentId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<AttachmentDtos.AttachmentSummary> upload(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId,
            @RequestParam String token,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestBody byte[] content) {
        return ApiResponse.success(
                service.upload(conversationId, attachmentId, token, content, correlationId));
    }

    @GetMapping("/{attachmentId}")
    public ApiResponse<AttachmentDtos.AttachmentSummary> metadata(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId) {
        return ApiResponse.success(service.metadata(conversationId, attachmentId));
    }

    @PostMapping("/{attachmentId}/download-grants")
    public ApiResponse<AttachmentDtos.DownloadGrant> downloadGrant(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(
                service.createDownloadGrant(conversationId, attachmentId, correlationId));
    }

    @GetMapping(value = "/{attachmentId}/content", params = "downloadToken")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId,
            @RequestParam String downloadToken,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        AttachmentService.DownloadedAttachment result = service.download(
                conversationId, attachmentId, downloadToken, correlationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(result.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.content().length)
                .body(result.content());
    }
}
