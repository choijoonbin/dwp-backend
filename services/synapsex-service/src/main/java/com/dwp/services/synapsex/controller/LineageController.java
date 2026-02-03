package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.service.lineage.LineageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Phase 1 Lineage / Evidence Viewer API
 * GET /api/synapse/lineage
 */
@RestController
@RequestMapping("/synapse/lineage")
@RequiredArgsConstructor
public class LineageController {

    private final LineageQueryService lineageQueryService;

    /**
     * D1) GET /api/synapse/lineage
     * Query: caseId OR docKey OR rawEventId OR partyId (최소 1개 필수)
     */
    @GetMapping
    public ApiResponse<LineageResponseDto> getLineage(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long caseId,
            @RequestParam(required = false) String docKey,
            @RequestParam(required = false) Long rawEventId,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {

        var query = LineageQueryService.LineageQuery.builder()
                .caseId(caseId)
                .docKey(docKey)
                .rawEventId(rawEventId)
                .partyId(partyId)
                .asOf(asOf)
                .build();

        LineageResponseDto result = lineageQueryService.findLineage(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * D2) GET /api/synapse/lineage/time-travel
     * Query: partyId, asOf(datetime) — Phase1 MVP: change log 기반 추정
     */
    @GetMapping("/time-travel")
    public ApiResponse<LineageResponseDto> getTimeTravel(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {

        var query = LineageQueryService.LineageQuery.builder()
                .partyId(partyId)
                .asOf(asOf)
                .build();

        LineageResponseDto result = lineageQueryService.findLineage(tenantId, query);
        return ApiResponse.success(result);
    }
}
