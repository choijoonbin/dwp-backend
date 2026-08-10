package com.dwp.services.provider;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

@RestController
@RequestMapping("/v1/admin")
public class ProviderControlPlaneController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final ProviderControlPlaneService service;

    public ProviderControlPlaneController(ProviderControlPlaneService service) {
        this.service = service;
    }

    @GetMapping("/tenants")
    public ApiResponse<ProviderDtos.PageResult<ProviderDtos.TenantSummary>> tenants(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.tenants(query, state, page, size));
    }

    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<ProviderDtos.TenantSummary> tenant(@PathVariable UUID tenantId) {
        return ApiResponse.success(service.tenant(tenantId));
    }

    @PatchMapping("/tenants/{tenantId}/lifecycle")
    public ApiResponse<ProviderDtos.TenantSummary> lifecycle(
            @PathVariable UUID tenantId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.LifecycleRequest request) {
        return ApiResponse.success(service.lifecycle(tenantId, correlationId, request));
    }

    @PutMapping("/tenants/{tenantId}/entitlements")
    public ApiResponse<ProviderDtos.TenantSummary> replaceEntitlements(
            @PathVariable UUID tenantId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.ReplaceEntitlementsRequest request) {
        return ApiResponse.success(service.replaceEntitlements(tenantId, correlationId, request));
    }

    @GetMapping("/entitlements")
    public ApiResponse<List<ProviderDtos.EntitlementSummary>> entitlements() {
        return ApiResponse.success(service.entitlementCatalog());
    }

    @PostMapping("/onboarding-plans")
    public ApiResponse<ProviderDtos.OperationSummary> previewOnboarding(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.OnboardingPlanRequest request) {
        return ApiResponse.success(service.previewOnboarding(
                idempotencyKey, correlationId, request));
    }

    @PostMapping("/operations/{operationId}/execute")
    public ApiResponse<ProviderDtos.OperationSummary> execute(
            @PathVariable UUID operationId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.ExecuteOperationRequest request) {
        return ApiResponse.success(service.execute(operationId, correlationId, request));
    }

    @GetMapping("/operations")
    public ApiResponse<ProviderDtos.PageResult<ProviderDtos.OperationSummary>> operations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.operations(page, size));
    }
}
