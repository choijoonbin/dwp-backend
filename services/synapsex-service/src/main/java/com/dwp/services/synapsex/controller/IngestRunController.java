package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import io.swagger.v3.oas.annotations.Operation;
import com.dwp.services.synapsex.dto.ingest.IngestRunDto;
import com.dwp.services.synapsex.service.ingest.IngestRunQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Admin Batch Monitoring — Ingest Run 관제 API.
 * Gateway: /api/synapse/admin/ingest/** → /synapse/admin/ingest/**
 */
@RestController
@RequestMapping("/synapse/admin/ingest")
@RequiredArgsConstructor
public class IngestRunController {

    private final IngestRunQueryService ingestRunQueryService;

    @Operation(summary = "Ingest Run 목록 조회", description = "Run History. from, to, status 필터")
    @GetMapping("/runs")
    public ApiResponse<Page<IngestRunDto>> listRuns(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "startedAt") Pageable pageable) {
        Page<IngestRunDto> page = ingestRunQueryService.search(tenantId, from, to, status, pageable);
        return ApiResponse.success(page);
    }

    @Operation(summary = "Ingest Run 상세 조회")
    @GetMapping("/runs/{runId}")
    public ApiResponse<IngestRunDto> getRun(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long runId) {
        IngestRunDto dto = ingestRunQueryService.getById(tenantId, runId);
        return ApiResponse.success(dto);
    }
}
