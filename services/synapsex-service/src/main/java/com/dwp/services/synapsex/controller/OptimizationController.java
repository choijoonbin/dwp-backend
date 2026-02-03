package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.optimization.OptimizationArApDto;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.optimization.OptimizationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 2 Optimization API - AR/AP 버킷/연체예측
 */
@RestController
@RequestMapping("/synapse/optimization")
@RequiredArgsConstructor
public class OptimizationController {

    private final OptimizationQueryService optimizationQueryService;
    private final AuditWriter auditWriter;

    /**
     * GET /api/synapse/optimization/ar
     */
    @GetMapping("/ar")
    public ApiResponse<OptimizationArApDto> getArOptimization(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {

        OptimizationArApDto result = optimizationQueryService.getArOptimization(tenantId);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_OPTIMIZATION_VIEW,
                "OPTIMIZATION_AR", null, AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, null, null, null, null, null, null);
        return ApiResponse.success(result);
    }

    /**
     * GET /api/synapse/optimization/ap
     */
    @GetMapping("/ap")
    public ApiResponse<OptimizationArApDto> getApOptimization(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {

        OptimizationArApDto result = optimizationQueryService.getApOptimization(tenantId);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_OPTIMIZATION_VIEW,
                "OPTIMIZATION_AP", null, AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, null, null, null, null, null, null);
        return ApiResponse.success(result);
    }
}
