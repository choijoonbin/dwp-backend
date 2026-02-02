package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.SodEvaluateRequest;
import com.dwp.services.synapsex.dto.admin.SodEvaluateResponse;
import com.dwp.services.synapsex.service.admin.SodEvaluateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Synapse Admin - SoD 평가 (future Governance actions용).
 * Base: /api/synapse/admin/sod
 */
@RestController
@RequestMapping("/synapse/admin/sod")
@RequiredArgsConstructor
public class AdminSodController {

    private final SodEvaluateService sodEvaluateService;

    /**
     * POST /api/synapse/admin/sod/evaluate
     */
    @PostMapping("/evaluate")
    public ApiResponse<SodEvaluateResponse> evaluate(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody SodEvaluateRequest request) {
        return ApiResponse.success(sodEvaluateService.evaluate(tenantId, request));
    }
}
