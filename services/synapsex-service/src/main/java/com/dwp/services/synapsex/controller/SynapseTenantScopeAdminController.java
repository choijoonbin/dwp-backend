package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.*;
import com.dwp.services.synapsex.service.admin.ProfileScopeService;
import com.dwp.services.synapsex.service.admin.TenantScopeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Synapse Admin - Tenant Scope (Company Codes, Currencies, SoD Rules).
 * Base: /api/synapse/admin/tenant-scope
 * Profile-scoped API: /company-codes, /currencies, /sod-rules (profileId 기준)
 */
@RestController
@RequestMapping("/synapse/admin/tenant-scope")
@RequiredArgsConstructor
public class SynapseTenantScopeAdminController {

    private final TenantScopeService tenantScopeService;
    private final ProfileScopeService profileScopeService;

    /**
     * GET /api/synapse/admin/tenant-scope
     */
    @GetMapping
    public ApiResponse<TenantScopeResponseDto> getTenantScope(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        return ApiResponse.success(tenantScopeService.getTenantScope(tenantId));
    }

    // --- Profile-scoped API (spec: /api/synapse/admin/tenant-scope/...) ---

    /**
     * GET /api/synapse/admin/tenant-scope/company-codes?profileId=
     */
    @GetMapping("/company-codes")
    public ApiResponse<TenantScopeCompanyCodesResponseDto> getCompanyCodes(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long profileId) {
        return ApiResponse.success(profileScopeService.getCompanyCodes(tenantId, profileId));
    }

    /**
     * PUT /api/synapse/admin/tenant-scope/company-codes/bulk
     */
    @PutMapping("/company-codes/bulk")
    public ApiResponse<TenantScopeCompanyCodesResponseDto> bulkUpdateCompanyCodes(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkCompanyCodesProfileRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeCompanyCodesResponseDto dto = profileScopeService.bulkUpdateCompanyCodes(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                request,
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * GET /api/synapse/admin/tenant-scope/currencies?profileId=
     */
    @GetMapping("/currencies")
    public ApiResponse<TenantScopeCurrenciesResponseDto> getCurrencies(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long profileId) {
        return ApiResponse.success(profileScopeService.getCurrencies(tenantId, profileId));
    }

    /**
     * PUT /api/synapse/admin/tenant-scope/currencies/bulk
     */
    @PutMapping("/currencies/bulk")
    public ApiResponse<TenantScopeCurrenciesResponseDto> bulkUpdateCurrencies(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkCurrenciesProfileRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeCurrenciesResponseDto dto = profileScopeService.bulkUpdateCurrencies(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                request,
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * GET /api/synapse/admin/tenant-scope/sod-rules?profileId=
     */
    @GetMapping("/sod-rules")
    public ApiResponse<TenantScopeSodRulesResponseDto> getSodRules(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long profileId) {
        return ApiResponse.success(profileScopeService.getSodRules(tenantId, profileId));
    }

    /**
     * PUT /api/synapse/admin/tenant-scope/sod-rules/bulk
     */
    @PutMapping("/sod-rules/bulk")
    public ApiResponse<TenantScopeSodRulesResponseDto> bulkUpdateSodRules(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkSodRulesProfileRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeSodRulesResponseDto dto = profileScopeService.bulkUpdateSodRules(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                request,
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * PATCH /api/synapse/admin/tenant-scope/company-codes/{bukrs}
     */
    @PatchMapping("/company-codes/{bukrs}")
    public ApiResponse<TenantScopeResponseDto> patchCompanyCode(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable String bukrs,
            @Valid @RequestBody PatchCompanyCodeRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeResponseDto dto = tenantScopeService.toggleCompanyCode(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                bukrs,
                request.getEnabled(),
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * PATCH /api/synapse/admin/tenant-scope/currencies/{waers}
     */
    @PatchMapping("/currencies/{waers}")
    public ApiResponse<TenantScopeResponseDto> patchCurrency(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable String waers,
            @Valid @RequestBody PatchCurrencyRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeResponseDto dto = tenantScopeService.toggleCurrency(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                waers,
                request.getEnabled(),
                request.getFxControlMode(),
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * PATCH /api/synapse/admin/tenant-scope/sod-rules/{ruleKey}
     */
    @PatchMapping("/sod-rules/{ruleKey}")
    public ApiResponse<TenantScopeResponseDto> patchSodRule(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable String ruleKey,
            @Valid @RequestBody PatchSodRuleRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeResponseDto dto = tenantScopeService.toggleSodRule(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                ruleKey,
                request.getEnabled(),
                request.getSeverity(),
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * POST /api/synapse/admin/tenant-scope/company-codes/bulk
     */
    @PostMapping("/company-codes/bulk")
    public ApiResponse<TenantScopeResponseDto> bulkUpdateCompanyCodes(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkUpdateCompanyCodesRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeResponseDto dto = tenantScopeService.bulkUpdateCompanyCodes(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                request,
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * POST /api/synapse/admin/tenant-scope/currencies/bulk
     */
    @PostMapping("/currencies/bulk")
    public ApiResponse<TenantScopeResponseDto> bulkUpdateCurrencies(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkUpdateCurrenciesRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeResponseDto dto = tenantScopeService.bulkUpdateCurrencies(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                request,
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }

    /**
     * POST /api/synapse/admin/tenant-scope/sod-rules/bulk
     */
    @PostMapping("/sod-rules/bulk")
    public ApiResponse<TenantScopeResponseDto> bulkUpdateSodRules(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkUpdateSodRulesRequest request,
            HttpServletRequest httpRequest) {
        TenantScopeResponseDto dto = tenantScopeService.bulkUpdateSodRules(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                request,
                httpRequest != null ? httpRequest.getRemoteAddr() : null,
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                httpRequest != null ? httpRequest.getHeader("X-Gateway-Request-Id") : null);
        return ApiResponse.success(dto);
    }
}
