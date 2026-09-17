package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
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

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

@RestController
@RequestMapping("/v1/admin/mail")
public class AdminMailCompletionController {

    private final AdminMailCompletionService service;

    public AdminMailCompletionController(AdminMailCompletionService service) {
        this.service = service;
    }

    @GetMapping("/operations")
    public ApiResponse<AdminOperationsSnapshot> operations(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId) {
        return ApiResponse.success(service.operations(tenantId));
    }

    @PostMapping("/connections/{connectionId}/diagnostics")
    public ApiResponse<ConnectionOperation> diagnose(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody ConnectionOperationRequest request) {
        return ApiResponse.success(service.connectionOperation(
                tenantId, actorId, connectionId, "DIAGNOSTIC", correlationId, request));
    }

    @PostMapping("/connections/{connectionId}/sync")
    public ApiResponse<ConnectionOperation> synchronize(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody ConnectionOperationRequest request) {
        return ApiResponse.success(service.connectionOperation(
                tenantId, actorId, connectionId, "SYNC", correlationId, request));
    }

    @PostMapping("/connections/{connectionId}/test-send")
    public ApiResponse<ConnectionOperation> testSend(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody ConnectionOperationRequest request) {
        return ApiResponse.success(service.connectionOperation(
                tenantId, actorId, connectionId, "TEST_SEND", correlationId, request));
    }

    @GetMapping("/shared-inboxes/{inboxId}/members")
    public ApiResponse<SharedInboxAccess> sharedInboxAccess(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @PathVariable UUID inboxId) {
        return ApiResponse.success(service.sharedInboxAccess(tenantId, inboxId));
    }

    @PostMapping("/shared-inboxes/{inboxId}/members")
    public ApiResponse<SharedInboxAccess> addSharedInboxMember(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID inboxId,
            @Valid @RequestBody SharedInboxMemberRequest request) {
        return ApiResponse.success(service.addSharedInboxMember(
                tenantId, actorId, inboxId, correlationId, request));
    }

    @PutMapping("/shared-inboxes/{inboxId}/members/{memberId}")
    public ApiResponse<SharedInboxAccess> updateSharedInboxMember(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID inboxId,
            @PathVariable UUID memberId,
            @Valid @RequestBody SharedInboxMemberRequest request) {
        return ApiResponse.success(service.updateSharedInboxMember(
                tenantId, actorId, inboxId, memberId, correlationId, request));
    }

    @PostMapping("/shared-inboxes/{inboxId}/members/{memberId}/revoke")
    public ApiResponse<SharedInboxAccess> revokeSharedInboxMember(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID inboxId,
            @PathVariable UUID memberId,
            @Valid @RequestBody SharedInboxMemberRevokeRequest request) {
        return ApiResponse.success(service.revokeSharedInboxMember(
                tenantId, actorId, inboxId, memberId, correlationId, request));
    }

    @GetMapping("/policy/evidence")
    public ApiResponse<PolicyGovernance> policyGovernance(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId) {
        return ApiResponse.success(service.policyGovernance(tenantId));
    }

    @GetMapping("/retention")
    public ApiResponse<RetentionSnapshot> retention(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId) {
        return ApiResponse.success(service.retention(tenantId));
    }

    @PostMapping("/retention/holds")
    public ApiResponse<LegalHold> createLegalHold(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody LegalHoldRequest request) {
        return ApiResponse.success(service.createLegalHold(
                tenantId, actorId, correlationId, request));
    }

    @PutMapping("/retention/holds/{holdId}")
    public ApiResponse<LegalHold> updateLegalHold(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID holdId,
            @Valid @RequestBody LegalHoldRequest request) {
        return ApiResponse.success(service.updateLegalHold(
                tenantId, actorId, holdId, correlationId, request));
    }

    @PostMapping("/retention/holds/{holdId}/release")
    public ApiResponse<LegalHold> releaseLegalHold(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID holdId,
            @Valid @RequestBody LegalHoldReleaseRequest request) {
        return ApiResponse.success(service.releaseLegalHold(
                tenantId, actorId, holdId, correlationId, request));
    }

    @PostMapping("/retention/purge-previews")
    public ApiResponse<PurgePreview> previewPurge(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @Valid @RequestBody PurgePreviewRequest request) {
        return ApiResponse.success(service.previewPurge(tenantId, actorId, request));
    }

    @PostMapping("/retention/purges/{snapshotId}/approvals")
    public ApiResponse<PurgeApproval> approvePurge(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @PathVariable UUID snapshotId,
            @Valid @RequestBody PurgeApprovalRequest request) {
        return ApiResponse.success(service.approvePurge(
                tenantId, actorId, snapshotId, request));
    }

    @PostMapping("/retention/purges/{snapshotId}/execute")
    public ApiResponse<PurgeJob> executePurge(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID snapshotId,
            @Valid @RequestBody PurgeExecuteRequest request) {
        return ApiResponse.success(service.executePurge(
                tenantId, actorId, snapshotId, correlationId, request));
    }

    @GetMapping("/retention/purge-jobs/{jobId}")
    public ApiResponse<PurgeJob> purgeJob(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @PathVariable UUID jobId) {
        return ApiResponse.success(service.purgeJob(tenantId, jobId));
    }

    @GetMapping("/delivery-audit")
    public ApiResponse<DeliveryAuditPage> deliveryAudit(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(name = "correlationId", defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.success(service.deliveryAudit(
                tenantId, state, query, page, pageSize));
    }

    @PostMapping("/delivery-audit/{deliveryId}/reconcile")
    public ApiResponse<DeliveryAuditItem> reconcileDelivery(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryRecoveryRequest request) {
        return ApiResponse.success(service.recoverDelivery(
                tenantId, actorId, deliveryId, "RECONCILE", correlationId, request));
    }

    @PostMapping("/delivery-audit/{deliveryId}/retry")
    public ApiResponse<DeliveryAuditItem> retryDelivery(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryRecoveryRequest request) {
        return ApiResponse.success(service.recoverDelivery(
                tenantId, actorId, deliveryId, "RETRY", correlationId, request));
    }

    @PostMapping("/delivery-audit/{deliveryId}/cancel")
    public ApiResponse<DeliveryAuditItem> cancelDelivery(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryRecoveryRequest request) {
        return ApiResponse.success(service.recoverDelivery(
                tenantId, actorId, deliveryId, "CANCEL", correlationId, request));
    }

    @PostMapping("/delivery-audit/exports")
    public ApiResponse<DeliveryExport> createDeliveryExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @Valid @RequestBody DeliveryExportRequest request) {
        return ApiResponse.success(service.createDeliveryExport(tenantId, actorId, request));
    }

    @GetMapping("/delivery-audit/exports/{exportId}/download")
    public ResponseEntity<byte[]> downloadDeliveryExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @PathVariable UUID exportId) {
        byte[] payload = service.deliveryExportJson(tenantId, actorId, exportId)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("mail-delivery-audit-" + exportId + ".json")
                        .build().toString())
                .body(payload);
    }
}
