package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.recon.ReconRunDetailDto;
import com.dwp.services.synapsex.dto.recon.ReconRunListDto;
import com.dwp.services.synapsex.dto.recon.StartReconRequest;
import com.dwp.services.synapsex.service.recon.ReconRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 4 Reconciliation API
 */
@RestController
@RequestMapping("/synapse/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconRunService reconRunService;

    @PostMapping("/runs")
    public ApiResponse<ReconRunDetailDto> startRecon(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody StartReconRequest request) {
        ReconRunDetailDto dto = reconRunService.startRecon(tenantId, request);
        return ApiResponse.success(dto);
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<ReconRunListDto>> listRuns(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String runType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<ReconRunListDto> result = reconRunService.listRuns(tenantId, runType, page, size, sort);
        return ApiResponse.success(result);
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<ReconRunDetailDto> getRunDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long runId) {
        ReconRunDetailDto dto = reconRunService.getRunDetail(tenantId, runId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Reconciliation run을 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
