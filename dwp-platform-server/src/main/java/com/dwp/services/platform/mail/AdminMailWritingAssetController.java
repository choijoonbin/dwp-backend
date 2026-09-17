package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.AdminMailWritingAssetDtos.*;

@RestController
@RequestMapping("/v1/admin/mail/writing-assets")
public class AdminMailWritingAssetController {

    private final AdminMailWritingAssetService service;

    public AdminMailWritingAssetController(AdminMailWritingAssetService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<OrganizationAsset>> assets(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestParam(required = false) AssetKind kind,
            @RequestParam(required = false, defaultValue = "") String state) {
        return ApiResponse.success(service.assets(tenantId, kind, state));
    }

    @PostMapping("/{kind}/drafts")
    @Operation(operationId = "createAdminMailWritingAssetDraft")
    public ApiResponse<OrganizationAsset> createDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Active-Access-Mode") String accessMode,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable AssetKind kind,
            @Valid @RequestBody DraftRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.createDraft(
                tenantId, userId, kind, correlationId, idempotencyKey, request));
    }

    @PutMapping("/{kind}/drafts/{assetId}")
    @Operation(operationId = "updateAdminMailWritingAssetDraft")
    public ApiResponse<OrganizationAsset> updateDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Active-Access-Mode") String accessMode,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable AssetKind kind,
            @PathVariable UUID assetId,
            @Valid @RequestBody DraftRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.updateDraft(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, request));
    }

    @PostMapping("/{kind}/{assetId}/submit")
    @Operation(operationId = "submitAdminMailWritingAsset")
    public ApiResponse<OrganizationAsset> submit(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Active-Access-Mode") String accessMode,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable AssetKind kind,
            @PathVariable UUID assetId,
            @Valid @RequestBody TransitionRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.submit(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, request));
    }

    @PostMapping("/{kind}/{assetId}/approve")
    @Operation(operationId = "approveAdminMailWritingAsset")
    public ApiResponse<OrganizationAsset> approve(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Active-Access-Mode") String accessMode,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable AssetKind kind,
            @PathVariable UUID assetId,
            @Valid @RequestBody TransitionRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.approve(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, request));
    }

    @PostMapping("/{kind}/{assetId}/publish")
    @Operation(operationId = "publishAdminMailWritingAsset")
    public ApiResponse<OrganizationAsset> publish(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Active-Access-Mode") String accessMode,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable AssetKind kind,
            @PathVariable UUID assetId,
            @Valid @RequestBody TransitionRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.publish(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, request));
    }

    @PostMapping("/{kind}/{assetId}/retire")
    @Operation(operationId = "retireAdminMailWritingAsset")
    public ApiResponse<OrganizationAsset> retire(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Active-Access-Mode") String accessMode,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable AssetKind kind,
            @PathVariable UUID assetId,
            @Valid @RequestBody TransitionRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.retire(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, request));
    }
}
