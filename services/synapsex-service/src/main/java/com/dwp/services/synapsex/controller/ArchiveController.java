package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
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
     * D1) GET /api/synapse/archive
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
}
