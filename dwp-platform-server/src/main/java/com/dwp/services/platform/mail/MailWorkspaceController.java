package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

@RestController
@RequestMapping("/v1/mail")
public class MailWorkspaceController {

    private static final Set<String> DOWNLOAD_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "application/pdf",
            "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain");

    private final MailWorkspaceService service;

    public MailWorkspaceController(MailWorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/compose-context")
    public ApiResponse<ComposeContext> composeContext(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId) {
        return ApiResponse.success(service.composeContext(tenantId, userId));
    }

    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Attachment> uploadAttachment(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(service.uploadAttachment(tenantId, userId, file));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResponse<Void> deleteAttachment(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID attachmentId) {
        service.deleteAttachment(tenantId, userId, attachmentId);
        return ApiResponse.success(null);
    }

    @GetMapping("/threads/{threadId}/messages/{messageId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID threadId,
            @PathVariable UUID messageId,
            @PathVariable UUID attachmentId) {
        MailWorkspaceService.AttachmentDownload attachment = service.downloadAttachment(
                tenantId, userId, threadId, messageId, attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(safeContentType(attachment.contentType()))
                .contentLength(attachment.sizeBytes())
                .body(attachment.resource());
    }

    @PostMapping("/messages/advanced")
    public ApiResponse<AdvancedComposeResult> compose(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody AdvancedComposeRequest request) {
        return ApiResponse.success(service.compose(tenantId, userId, correlationId, request));
    }

    @GetMapping("/saved-views")
    public ApiResponse<List<SavedView>> savedViews(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId) {
        return ApiResponse.success(service.savedViews(tenantId, userId));
    }

    @PostMapping("/saved-views")
    public ApiResponse<SavedView> createSavedView(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @Valid @RequestBody SavedViewRequest request) {
        return ApiResponse.success(service.createSavedView(tenantId, userId, request));
    }

    @PutMapping("/saved-views/{savedViewId}")
    public ApiResponse<SavedView> updateSavedView(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID savedViewId,
            @Valid @RequestBody SavedViewRequest request) {
        return ApiResponse.success(service.updateSavedView(tenantId, userId, savedViewId, request));
    }

    @DeleteMapping("/saved-views/{savedViewId}")
    public ApiResponse<Void> deleteSavedView(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID savedViewId,
            @RequestParam long version) {
        service.deleteSavedView(tenantId, userId, savedViewId, version);
        return ApiResponse.success(null);
    }

    @GetMapping("/follow-ups")
    public ApiResponse<List<FollowUp>> followUps(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.followUps(tenantId, userId, status));
    }

    @PostMapping("/threads/{threadId}/follow-up")
    public ApiResponse<FollowUp> createFollowUp(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID threadId,
            @Valid @RequestBody FollowUpRequest request) {
        return ApiResponse.success(service.createFollowUp(tenantId, userId, threadId, request));
    }

    @PutMapping("/follow-ups/{followUpId}")
    public ApiResponse<FollowUp> updateFollowUp(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID followUpId,
            @Valid @RequestBody FollowUpRequest request) {
        return ApiResponse.success(service.updateFollowUp(tenantId, userId, followUpId, request));
    }

    @DeleteMapping("/follow-ups/{followUpId}")
    public ApiResponse<Void> deleteFollowUp(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID followUpId,
            @RequestParam long version) {
        service.deleteFollowUp(tenantId, userId, followUpId, version);
        return ApiResponse.success(null);
    }

    @GetMapping("/deliveries")
    public ApiResponse<DeliveryPage> deliveries(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(defaultValue = "PROCESSING") String bucket,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.success(service.deliveries(
                tenantId, userId, bucket, page, pageSize));
    }

    @GetMapping("/deliveries/{deliveryId}")
    public ApiResponse<DeliveryReceipt> delivery(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID deliveryId) {
        return ApiResponse.success(service.delivery(tenantId, userId, deliveryId));
    }

    @PostMapping("/deliveries/{deliveryId}/reschedule")
    public ApiResponse<DeliveryReceipt> reschedule(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.success(service.reschedule(tenantId, userId, deliveryId, request));
    }

    @PostMapping("/deliveries/{deliveryId}/cancel")
    @Operation(operationId = "cancelMailDelivery")
    public ApiResponse<DeliveryReceipt> cancel(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.cancel(tenantId, userId, deliveryId, request));
    }

    @PostMapping("/deliveries/{deliveryId}/reconcile")
    @Operation(operationId = "reconcileMailDelivery")
    public ApiResponse<DeliveryReceipt> reconcile(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.reconcile(tenantId, userId, deliveryId, request));
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    public ApiResponse<DeliveryReceipt> retry(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.retry(tenantId, userId, deliveryId, request));
    }

    @GetMapping("/writing-assets")
    public ApiResponse<WritingAssets> writingAssets(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ApiResponse.success(service.writingAssets(tenantId, userId, includeArchived));
    }

    @PostMapping("/templates")
    public ApiResponse<Template> createTemplate(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @Valid @RequestBody TemplateRequest request) {
        return ApiResponse.success(service.createTemplate(tenantId, userId, request));
    }

    @PutMapping("/templates/{templateId}")
    public ApiResponse<Template> updateTemplate(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID templateId,
            @Valid @RequestBody TemplateRequest request) {
        return ApiResponse.success(service.updateTemplate(tenantId, userId, templateId, request));
    }

    @DeleteMapping("/templates/{templateId}")
    public ApiResponse<Void> archiveTemplate(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID templateId,
            @RequestParam long version) {
        service.archiveTemplate(tenantId, userId, templateId, version);
        return ApiResponse.success(null);
    }

    @PostMapping("/signatures")
    public ApiResponse<Signature> createSignature(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @Valid @RequestBody SignatureRequest request) {
        return ApiResponse.success(service.createSignature(tenantId, userId, request));
    }

    @PutMapping("/signatures/{signatureId}")
    public ApiResponse<Signature> updateSignature(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID signatureId,
            @Valid @RequestBody SignatureRequest request) {
        return ApiResponse.success(service.updateSignature(tenantId, userId, signatureId, request));
    }

    @DeleteMapping("/signatures/{signatureId}")
    public ApiResponse<Void> archiveSignature(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID signatureId,
            @RequestParam long version) {
        service.archiveSignature(tenantId, userId, signatureId, version);
        return ApiResponse.success(null);
    }

    @GetMapping("/preferences")
    public ApiResponse<Preferences> preferences(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId) {
        return ApiResponse.success(service.preferences(tenantId, userId));
    }

    @PutMapping("/preferences")
    public ApiResponse<Preferences> updatePreferences(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @Valid @RequestBody PreferencesRequest request) {
        return ApiResponse.success(service.updatePreferences(tenantId, userId, request));
    }

    private MediaType safeContentType(String value) {
        if (value == null || !DOWNLOAD_CONTENT_TYPES.contains(value)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return MediaType.parseMediaType(value);
    }
}
