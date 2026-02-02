package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.CatalogDto;
import com.dwp.services.synapsex.service.admin.TenantScopeCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Synapse Admin - Catalog (BUKRS, WAERS 카탈로그).
 * Base: /api/synapse/admin/catalog
 */
@RestController
@RequestMapping("/synapse/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController {

    private final TenantScopeCatalogService catalogService;

    /**
     * GET /api/synapse/admin/catalog/company-codes
     */
    @GetMapping("/company-codes")
    public ApiResponse<CatalogDto> getCompanyCodesCatalog(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        return ApiResponse.success(catalogService.getCompanyCodesCatalog(tenantId));
    }

    /**
     * GET /api/synapse/admin/catalog/currencies
     */
    @GetMapping("/currencies")
    public ApiResponse<CatalogDto> getCurrenciesCatalog(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        return ApiResponse.success(catalogService.getCurrenciesCatalog(tenantId));
    }

    /**
     * GET /api/synapse/admin/catalog (전체)
     */
    @GetMapping
    public ApiResponse<CatalogDto> getFullCatalog(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        return ApiResponse.success(catalogService.getFullCatalog(tenantId));
    }
}
