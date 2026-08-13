package com.dwp.services.platform.savedview;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
            @RequestHeader(PERMISSIONS) String permissions,
            @Valid @RequestBody SavedViewDtos.OwnershipPlanRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.preview(tenantId, request));
    }

    @PostMapping("/transfers")
    public ApiResponse<SavedViewDtos.OwnershipTransfer> transfer(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody SavedViewDtos.OwnershipTransferRequest request) {
        access.manage(permissions);
        return ApiResponse.success(coordinator.transfer(
                tenantId, actorId, correlationId, request));
    }

    @GetMapping("/orphaned")
    public ApiResponse<List<SavedViewDtos.OrphanedView>> orphaned(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions) {
        access.view(permissions);
        return ApiResponse.success(coordinator.orphaned(tenantId));
    }

    @GetMapping("/transfers")
    public ApiResponse<List<SavedViewDtos.OwnershipTransferSummary>> transfers(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "50") int limit) {
        access.view(permissions);
        return ApiResponse.success(coordinator.transfers(tenantId, limit));
    }
}
