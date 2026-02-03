package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.archive.ArchiveListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.service.archive.ArchiveQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Phase 2 Archive API
 */
@RestController
@RequestMapping("/synapse/archive")
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveQueryService archiveQueryService;

    /**
     * D1) GET /api/synapse/archive (alias: GET /archive/actions)
     */
    @GetMapping
    public ApiResponse<PageResponse<ArchiveListRowDto>> getArchive(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = ArchiveQueryService.ArchiveListQuery.builder()
                .outcome(outcome)
                .type(type)
                .from(from)
                .to(to)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<ArchiveListRowDto> result = archiveQueryService.findArchivedActions(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * GET /api/synapse/archive/actions (alias)
     */
    @GetMapping("/actions")
    public ApiResponse<PageResponse<ArchiveListRowDto>> getArchiveActions(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = ArchiveQueryService.ArchiveListQuery.builder()
                .outcome(outcome)
                .type(type)
                .from(from)
                .to(to)
                .page(page)
                .size(size)
                .sort(sort)
                .build();
        return ApiResponse.success(archiveQueryService.findArchivedActions(tenantId, query));
    }

    /**
     * GET /api/synapse/archive/actions/{actionId}
     */
    @GetMapping("/actions/{actionId}")
    public ApiResponse<ArchiveListRowDto> getArchiveActionDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long actionId) {

        ArchiveListRowDto dto = archiveQueryService.findArchivedActionDetail(tenantId, actionId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "아카이브 액션을 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
