package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.actionrecon.ActionReconDto;
import com.dwp.services.synapsex.service.actionrecon.ActionReconQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 4 Action Reconciliation API
 */
@RestController
@RequestMapping("/synapse/action-recon")
@RequiredArgsConstructor
public class ActionReconController {

    private final ActionReconQueryService actionReconQueryService;

    @GetMapping
    public ApiResponse<ActionReconDto> getActionRecon(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        ActionReconDto dto = actionReconQueryService.getActionRecon(tenantId);
        return ApiResponse.success(dto);
    }
}
