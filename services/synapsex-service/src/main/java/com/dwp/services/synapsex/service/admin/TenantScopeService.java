package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.BulkUpdateCompanyCodesRequest;
import com.dwp.services.synapsex.dto.admin.BulkUpdateCurrenciesRequest;
import com.dwp.services.synapsex.dto.admin.BulkUpdateSodRulesRequest;
import com.dwp.services.synapsex.dto.admin.TenantScopeResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant Scope 파사드 (tenant-level V8 테이블).
 * Query/Command 분리로 350라인 제한 준수.
 */
@Service
@RequiredArgsConstructor
public class TenantScopeService {

    private final TenantScopeQueryService queryService;
    private final TenantScopeCommandService commandService;

    /**
     * Tenant Scope 전체 조회. 비어있으면 idempotent 시드 후 반환.
     */
    @Transactional
    public TenantScopeResponseDto getTenantScope(Long tenantId) {
        commandService.ensureSeeded(tenantId);
        return queryService.buildResponse(tenantId);
    }

    @Transactional
    public TenantScopeResponseDto toggleCompanyCode(Long tenantId, Long actorUserId, String bukrs,
                                                    boolean enabled, String ipAddress, String userAgent, String gatewayRequestId) {
        return commandService.toggleCompanyCode(tenantId, actorUserId, bukrs, enabled, ipAddress, userAgent, gatewayRequestId);
    }

    @Transactional
    public TenantScopeResponseDto toggleCurrency(Long tenantId, Long actorUserId, String waers,
                                                 Boolean enabled, String fxControlMode, String ipAddress, String userAgent, String gatewayRequestId) {
        return commandService.toggleCurrency(tenantId, actorUserId, waers, enabled, fxControlMode, ipAddress, userAgent, gatewayRequestId);
    }

    @Transactional
    public TenantScopeResponseDto toggleSodRule(Long tenantId, Long actorUserId, String ruleKey,
                                                Boolean enabled, String severity, String ipAddress, String userAgent, String gatewayRequestId) {
        return commandService.toggleSodRule(tenantId, actorUserId, ruleKey, enabled, severity, ipAddress, userAgent, gatewayRequestId);
    }

    @Transactional
    public TenantScopeResponseDto bulkUpdateCompanyCodes(Long tenantId, Long actorUserId,
                                                         BulkUpdateCompanyCodesRequest request,
                                                         String ipAddress, String userAgent, String gatewayRequestId) {
        return commandService.bulkUpdateCompanyCodes(tenantId, actorUserId, request, ipAddress, userAgent, gatewayRequestId);
    }

    @Transactional
    public TenantScopeResponseDto bulkUpdateCurrencies(Long tenantId, Long actorUserId,
                                                       BulkUpdateCurrenciesRequest request,
                                                       String ipAddress, String userAgent, String gatewayRequestId) {
        return commandService.bulkUpdateCurrencies(tenantId, actorUserId, request, ipAddress, userAgent, gatewayRequestId);
    }

    @Transactional
    public TenantScopeResponseDto bulkUpdateSodRules(Long tenantId, Long actorUserId,
                                                     BulkUpdateSodRulesRequest request,
                                                     String ipAddress, String userAgent, String gatewayRequestId) {
        return commandService.bulkUpdateSodRules(tenantId, actorUserId, request, ipAddress, userAgent, gatewayRequestId);
    }
}
