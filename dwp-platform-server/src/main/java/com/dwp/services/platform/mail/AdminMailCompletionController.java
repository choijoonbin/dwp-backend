package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

@RestController
@RequestMapping("/v1/admin/mail")
public class AdminMailCompletionController {

    private static final String ACTIVE_ACCESS_MODE = "X-DWP-Active-Access-Mode";

    private final AdminMailCompletionService service;

    public AdminMailCompletionController(AdminMailCompletionService service) {
        this.service = service;
    }

    @GetMapping("/operations")
    @Operation(operationId = "listAdminMailOperations")
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
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody ConnectionOperationRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.connectionOperation(
                tenantId, actorId, connectionId, "TEST_SEND", correlationId, request));
    }

    @GetMapping("/shared-inboxes/{inboxId}/members")
    public ApiResponse<SharedInboxAccess> sharedInboxAccess(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @PathVariable UUID inboxId) {
        return ApiResponse.success(service.sharedInboxAccess(tenantId, inboxId));
    }

    @GetMapping("/shared-inboxes/member-candidates")
    public ApiResponse<List<SharedInboxMemberCandidate>> sharedInboxMemberCandidates(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(service.sharedInboxMemberCandidates(tenantId, query, limit));
    }

    @PostMapping("/shared-inboxes/{inboxId}/members")
    public ApiResponse<SharedInboxAccess> addSharedInboxMember(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID inboxId,
            @Valid @RequestBody SharedInboxMemberRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.addSharedInboxMember(
                tenantId, actorId, inboxId, correlationId, request));
    }

    @PutMapping("/shared-inboxes/{inboxId}/members/{memberId}")
    public ApiResponse<SharedInboxAccess> updateSharedInboxMember(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID inboxId,
            @PathVariable UUID memberId,
            @Valid @RequestBody SharedInboxMemberRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.updateSharedInboxMember(
                tenantId, actorId, inboxId, memberId, correlationId, request));
    }

    @PostMapping("/shared-inboxes/{inboxId}/members/{memberId}/revoke")
    public ApiResponse<SharedInboxAccess> revokeSharedInboxMember(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID inboxId,
            @PathVariable UUID memberId,
            @Valid @RequestBody SharedInboxMemberRevokeRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.revokeSharedInboxMember(
                tenantId, actorId, inboxId, memberId, correlationId, request));
    }

    @PostMapping("/shared-inboxes/{inboxId}/members/{memberId}/revoke-preview")
    public ApiResponse<SharedInboxMemberRevokePreview> previewSharedInboxMemberRevoke(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID inboxId,
            @PathVariable UUID memberId,
            @Valid @RequestBody SharedInboxMemberRevokePreviewRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.previewSharedInboxMemberRevoke(
                tenantId, actorId, inboxId, memberId, request));
    }

    @GetMapping("/policy/evidence")
    public ApiResponse<PolicyGovernance> policyGovernance(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId) {
        return ApiResponse.success(service.policyGovernance(tenantId));
    }

    @GetMapping("/retention")
    public ApiResponse<RetentionSnapshot> retention(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions) {
        boolean sensitive = hasPermission(permissions, "ADMIN.MAIL:HOLD_MANAGE")
                || hasPermission(permissions, "ADMIN.MAIL:PURGE_PREVIEW")
                || hasPermission(permissions, "ADMIN.MAIL:PURGE_AUTHORIZE")
                || hasPermission(permissions, "ADMIN.MAIL:PURGE_EXECUTE");
        return ApiResponse.success(service.retention(tenantId, sensitive));
    }

    @PostMapping("/retention/holds")
    public ApiResponse<LegalHold> createLegalHold(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody LegalHoldRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.createLegalHold(
                tenantId, actorId, correlationId, request));
    }

    @PutMapping("/retention/holds/{holdId}")
    public ApiResponse<LegalHold> updateLegalHold(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID holdId,
            @Valid @RequestBody LegalHoldRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.updateLegalHold(
                tenantId, actorId, holdId, correlationId, request));
    }

    @PostMapping("/retention/holds/{holdId}/release-previews")
    public ApiResponse<LegalHoldReleasePreview> previewLegalHoldRelease(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID holdId,
            @Valid @RequestBody LegalHoldReleasePreviewRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.previewLegalHoldRelease(
                tenantId, actorId, holdId, correlationId, request));
    }

    @GetMapping("/retention/hold-release-previews/{previewId}")
    public ApiResponse<LegalHoldReleasePreview> legalHoldReleasePreview(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @PathVariable UUID previewId) {
        return ApiResponse.success(service.legalHoldReleasePreview(tenantId, previewId));
    }

    @PostMapping("/retention/hold-release-previews/{previewId}/approvals")
    public ApiResponse<LegalHoldReleaseApproval> approveLegalHoldRelease(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID previewId,
            @Valid @RequestBody LegalHoldReleaseApprovalRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.approveLegalHoldRelease(
                tenantId, actorId, previewId, correlationId, request));
    }

    @PostMapping("/retention/hold-release-previews/{previewId}/execute")
    public ApiResponse<LegalHoldReleaseExecution> executeLegalHoldRelease(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID previewId,
            @Valid @RequestBody LegalHoldReleaseExecuteRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.executeLegalHoldRelease(
                tenantId, actorId, previewId, correlationId, request));
    }

    @PostMapping("/retention/purge-previews")
    public ApiResponse<PurgePreview> previewPurge(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @Valid @RequestBody PurgePreviewRequest request) {
        return ApiResponse.success(service.previewPurge(tenantId, actorId, request));
    }

    @GetMapping("/retention/purge-previews")
    public ApiResponse<List<PurgePreview>> activePurgePreviews(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions) {
        return ApiResponse.success(service.activePurgePreviews(
                tenantId, hasPermission(permissions, "ADMIN.MAIL:PURGE_PREVIEW")));
    }

    @GetMapping("/retention/purge-previews/{snapshotId}")
    public ApiResponse<PurgePreview> purgePreview(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @PathVariable UUID snapshotId) {
        return ApiResponse.success(service.purgePreview(
                tenantId, snapshotId,
                hasPermission(permissions, "ADMIN.MAIL:PURGE_PREVIEW")));
    }

    @PostMapping("/retention/purges/{snapshotId}/approvals")
    public ApiResponse<PurgeApproval> approvePurge(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID snapshotId,
            @Valid @RequestBody PurgeApprovalRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.approvePurge(
                tenantId, actorId, snapshotId, request));
    }

    @PostMapping("/retention/purges/{snapshotId}/execute")
    public ApiResponse<PurgeJob> executePurge(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID snapshotId,
            @Valid @RequestBody PurgeExecuteRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.executePurge(
                tenantId, actorId, snapshotId, correlationId, request));
    }

    @GetMapping("/retention/purge-jobs/{jobId}")
    public ApiResponse<PurgeJob> purgeJob(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @PathVariable UUID jobId) {
        return ApiResponse.success(service.purgeJob(
                tenantId, jobId,
                hasPermission(permissions, "ADMIN.MAIL:PURGE_PREVIEW")));
    }

    @PostMapping("/retention/evidence-exports")
    public ApiResponse<RetentionEvidenceExport> createRetentionEvidenceExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @Valid @RequestBody RetentionEvidenceExportRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.createRetentionEvidenceExport(
                tenantId, actorId, request));
    }

    @GetMapping("/retention/evidence-exports/{exportId}")
    public ApiResponse<RetentionEvidenceExport> retentionEvidenceExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @PathVariable UUID exportId) {
        return ApiResponse.success(service.retentionEvidenceExport(tenantId, exportId));
    }

    @PostMapping("/retention/evidence-exports/{exportId}/approvals")
    public ApiResponse<RetentionEvidenceExport> approveRetentionEvidenceExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID exportId,
            @Valid @RequestBody EvidenceExportApprovalRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.approveRetentionEvidenceExport(
                tenantId, actorId, exportId, request));
    }

    @GetMapping("/retention/evidence-exports/{exportId}/download")
    public ResponseEntity<byte[]> downloadRetentionEvidenceExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID exportId) {
        requireElevated(accessMode);
        byte[] payload = service.retentionEvidenceExportJson(tenantId, actorId, exportId)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("mail-retention-evidence-" + exportId + ".json")
                        .build().toString())
                .body(payload);
    }

    @GetMapping("/delivery-audit")
    public ApiResponse<DeliveryAuditPage> deliveryAudit(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(name = "correlationId", defaultValue = "") String query,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(defaultValue = "") String provider,
            @RequestParam(defaultValue = "") String command,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        boolean auditRead = hasPermission(permissions, "ADMIN.MAIL:AUDIT_READ");
        return ApiResponse.success(service.deliveryAudit(
                tenantId, state, auditRead ? query : "", auditRead ? accountId : null,
                auditRead ? provider : "", command,
                dateFrom, dateTo, page, pageSize,
                canRevealDeliveryEvidence(permissions)));
    }

    @PostMapping("/delivery-audit/{deliveryId}/reconcile")
    public ApiResponse<DeliveryAuditItem> reconcileDelivery(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryRecoveryRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.recoverDelivery(
                tenantId, actorId, deliveryId, "RECONCILE", correlationId, request,
                canRevealDeliveryEvidence(permissions)));
    }

    @PostMapping("/delivery-audit/{deliveryId}/retry")
    public ApiResponse<DeliveryAuditItem> retryDelivery(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryRecoveryRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.recoverDelivery(
                tenantId, actorId, deliveryId, "RETRY", correlationId, request,
                canRevealDeliveryEvidence(permissions)));
    }

    @PostMapping("/delivery-audit/{deliveryId}/cancel")
    public ApiResponse<DeliveryAuditItem> cancelDelivery(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryRecoveryRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.recoverDelivery(
                tenantId, actorId, deliveryId, "CANCEL", correlationId, request,
                canRevealDeliveryEvidence(permissions)));
    }

    @PostMapping("/delivery-audit/exports")
    public ApiResponse<DeliveryExport> createDeliveryExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @Valid @RequestBody DeliveryExportRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.createDeliveryExport(
                tenantId, actorId, request,
                canRevealDeliveryEvidence(permissions)));
    }

    @GetMapping("/delivery-audit/exports/{exportId}")
    public ApiResponse<DeliveryExport> deliveryExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @PathVariable UUID exportId) {
        return ApiResponse.success(service.deliveryExport(tenantId, exportId));
    }

    @PostMapping("/delivery-audit/exports/{exportId}/approvals")
    public ApiResponse<DeliveryExport> approveDeliveryExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID exportId,
            @Valid @RequestBody EvidenceExportApprovalRequest request) {
        requireElevated(accessMode);
        return ApiResponse.success(service.approveDeliveryExport(
                tenantId, actorId, exportId, request));
    }

    @GetMapping("/delivery-audit/exports/{exportId}/download")
    public ResponseEntity<byte[]> downloadDeliveryExport(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = ACTIVE_ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID exportId) {
        requireElevated(accessMode);
        byte[] payload = service.deliveryExportJson(tenantId, actorId, exportId)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("mail-delivery-audit-" + exportId + ".json")
                        .build().toString())
                .body(payload);
    }

    static void requireElevated(String accessMode) {
        if (!"ELEVATED".equalsIgnoreCase(accessMode)) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "Fresh elevated access is required for this Mail administration command.");
        }
    }

    static boolean canRevealDeliveryEvidence(String permissions) {
        return hasPermission(permissions, "ADMIN.MAIL:AUDIT_READ")
                && hasPermission(permissions, "ADMIN.MAIL:AUDIT_REVEAL");
    }

    private static boolean hasPermission(String values, String expected) {
        if (values == null || values.isBlank()) return false;
        return java.util.Arrays.stream(values.split("[,\\s]+"))
                .map(String::trim).anyMatch(expected::equalsIgnoreCase);
    }
}
