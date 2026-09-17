package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceFulfillmentController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;

@RestController
public class WorkplaceServiceOrderCollaborationController {
    private final WorkplaceServicesService services;
    private final WorkplaceServiceOrderQueryService queries;
    private final WorkplaceServiceAttachmentService attachments;

    public WorkplaceServiceOrderCollaborationController(
            WorkplaceServicesService services,
            WorkplaceServiceOrderQueryService queries,
            WorkplaceServiceAttachmentService attachments) {
        this.services = services;
        this.queries = queries;
        this.attachments = attachments;
    }

    @GetMapping("/v1/workplace/service-orders/{orderId}/events")
    public ApiResponse<RequesterServiceOrderEventsPage> userEvents(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(queries.ownEvents(
                tenantId, actorUserId, orderId, cursor, limit));
    }

    @GetMapping("/v1/workplace/service-orders/{orderId}/messages")
    public ApiResponse<RequesterServiceOrderMessagesPage> userMessages(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(queries.ownMessages(
                tenantId, actorUserId, orderId, cursor, limit));
    }

    @GetMapping("/v1/workplace/service-orders/{orderId}/attachments")
    public ApiResponse<ServiceOrderAttachmentsPage> userAttachments(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(queries.ownAttachments(
                tenantId, actorUserId, orderId, cursor, limit));
    }

    @GetMapping("/v1/admin/workplace/service-orders/{orderId}/events")
    public ApiResponse<ServiceOrderEventsPage> adminEvents(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(queries.adminEvents(tenantId, orderId, cursor, limit));
    }

    @GetMapping("/v1/admin/workplace/service-orders/{orderId}/messages")
    public ApiResponse<ServiceOrderMessagesPage> adminMessages(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(queries.adminMessages(tenantId, orderId, cursor, limit));
    }

    @GetMapping("/v1/admin/workplace/service-orders/{orderId}/attachments")
    public ApiResponse<ServiceOrderAttachmentsPage> adminAttachments(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(queries.adminAttachments(tenantId, orderId, cursor, limit));
    }

    @PostMapping("/v1/workplace/service-orders/{orderId}/messages")
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> userMessage(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody MessageRequest request) {
        requirePermission(permissions, UPDATE);
        return accepted(services.addMessage(tenantId, actorUserId, orderId, false,
                idempotencyKey, request, correlationId));
    }

    @PostMapping("/v1/admin/workplace/service-orders/{orderId}/messages")
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> adminMessage(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody MessageRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        return accepted(services.addMessage(tenantId, actorUserId, orderId, true,
                idempotencyKey, request, correlationId));
    }

    @PostMapping("/v1/workplace/service-orders/{orderId}:reconfirm")
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> reconfirm(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody ReconfirmRequest request) {
        requirePermission(permissions, UPDATE);
        return accepted(services.reconfirm(tenantId, actorUserId, orderId,
                idempotencyKey, request, correlationId));
    }

    @PostMapping(
            path = "/v1/workplace/service-orders/{orderId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> userAttachment(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @RequestParam long expectedVersion,
            @RequestParam String reason,
            @RequestParam boolean explicitConfirmation,
            @RequestPart("file") MultipartFile file) {
        requirePermission(permissions, UPDATE);
        requireConfirmation(explicitConfirmation);
        return accepted(attachments.upload(tenantId, actorUserId, orderId, false,
                idempotencyKey, expectedVersion, reason, file, correlationId));
    }

    @PostMapping(
            path = "/v1/admin/workplace/service-orders/{orderId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> adminAttachment(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @RequestParam long expectedVersion,
            @RequestParam String reason,
            @RequestParam boolean explicitConfirmation,
            @RequestPart("file") MultipartFile file) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        requireConfirmation(explicitConfirmation);
        return accepted(attachments.upload(tenantId, actorUserId, orderId, true,
                idempotencyKey, expectedVersion, reason, file, correlationId));
    }

    @GetMapping("/v1/workplace/service-orders/{orderId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> userAttachmentContent(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID attachmentId) {
        requirePermission(permissions, VIEW);
        return attachmentContent(attachments.download(
                tenantId, actorUserId, orderId, attachmentId, false, correlationId));
    }

    @GetMapping("/v1/admin/workplace/service-orders/{orderId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> adminAttachmentContent(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID attachmentId) {
        requirePermission(permissions, ADMIN_VIEW);
        requireElevated(accessMode);
        return attachmentContent(attachments.download(
                tenantId, actorUserId, orderId, attachmentId, true, correlationId));
    }

    @PostMapping("/v1/admin/workplace/service-orders/{orderId}/attachments/{attachmentId}/scan-result")
    public ResponseEntity<ApiResponse<AttachmentScanCommandResult>> recordAttachmentScan(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID attachmentId,
            @Valid @RequestBody AttachmentScanRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        AttachmentScanCommandResult result = attachments.recordScan(
                tenantId, actorUserId, orderId, attachmentId,
                idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/v1/admin/workplace/service-orders/{orderId}/attachments/{attachmentId}/scan-status")
    public ApiResponse<ServiceOrderAttachment> attachmentScanStatus(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @PathVariable UUID orderId,
            @PathVariable UUID attachmentId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(attachments.scanStatus(tenantId, orderId, attachmentId));
    }

    private static ResponseEntity<ApiResponse<ServiceOrderCommandResult>> accepted(
            ServiceOrderCommandResult result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    private static ResponseEntity<Resource> attachmentContent(
            WorkplaceServiceAttachmentService.AttachmentContent content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.ETAG, '"' + content.checksumSha256() + '"')
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .body(content.resource());
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Explicit confirmation is required to attach a file.");
        }
    }
}
