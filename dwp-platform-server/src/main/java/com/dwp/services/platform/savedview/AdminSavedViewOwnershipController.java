package com.dwp.services.platform.savedview;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/saved-view-ownership")
public class AdminSavedViewOwnershipController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String CORRELATION = "X-Correlation-ID";

    private final SavedViewOwnershipCoordinator coordinator;
    private final SavedViewCustodyAccessGuard access;

    public AdminSavedViewOwnershipController(
            SavedViewOwnershipCoordinator coordinator,
            SavedViewCustodyAccessGuard access) {
        this.coordinator = coordinator;
        this.access = access;
    }

    @PostMapping("/preview")
    public ApiResponse<SavedViewDtos.OwnershipPreview> preview(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @Valid @RequestBody SavedViewDtos.OwnershipPlanRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.preview(tenantId, actorId, request));
    }

    @PostMapping("/transfers")
    public ApiResponse<SavedViewDtos.OwnershipTransfer> transfer(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody SavedViewDtos.OwnershipTransferRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.transfer(
                tenantId, actorId, correlationId, request));
    }

    @GetMapping("/orphaned")
    public ApiResponse<List<SavedViewDtos.OrphanedView>> orphaned(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions) {
        access.view(permissions);
        return ApiResponse.success(coordinator.orphaned(tenantId));
    }

    @GetMapping("/orphaned/actions")
    public ApiResponse<List<SavedViewDtos.OrphanLifecycleResult>> orphanActions(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestParam(defaultValue = "50") int limit) {
        access.view(permissions);
        return ApiResponse.success(coordinator.orphanActions(tenantId, limit));
    }

    @PostMapping("/orphaned/{savedViewId}/reassign")
    public ApiResponse<SavedViewDtos.OrphanLifecycleResult> reassignOrphan(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID savedViewId,
            @Valid @RequestBody SavedViewDtos.OrphanReassignRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.reassignOrphan(
                tenantId, actorId, correlationId, savedViewId, request));
    }

    @PostMapping("/orphaned/{savedViewId}/extend-retention")
    public ApiResponse<SavedViewDtos.OrphanLifecycleResult> extendOrphanRetention(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID savedViewId,
            @Valid @RequestBody SavedViewDtos.OrphanRetentionRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.extendOrphanRetention(
                tenantId, actorId, correlationId, savedViewId, request));
    }

    @PostMapping("/orphaned/{savedViewId}/archive")
    public ApiResponse<SavedViewDtos.OrphanLifecycleResult> archiveOrphanNow(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID savedViewId,
            @Valid @RequestBody SavedViewDtos.OrphanArchiveRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.archiveOrphanNow(
                tenantId, actorId, correlationId, savedViewId, request));
    }

    @GetMapping("/users")
    public ApiResponse<List<SavedViewDtos.CustodyCandidate>> users(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) Long sourceOwnerUserId,
            @RequestParam(required = false) UUID savedViewId) {
        access.view(permissions);
        return ApiResponse.success(coordinator.users(
                tenantId, actorId, query, activeOnly, limit,
                sourceOwnerUserId, savedViewId));
    }

    @GetMapping("/transfers")
    public ApiResponse<List<SavedViewDtos.OwnershipTransferSummary>> transfers(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestParam(defaultValue = "50") int limit) {
        access.view(permissions);
        return ApiResponse.success(coordinator.transfers(tenantId, limit));
    }
}
