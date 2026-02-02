package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.ThresholdDto;
import com.dwp.services.synapsex.dto.admin.UpsertThresholdRequest;
import com.dwp.services.synapsex.service.admin.ThresholdCommandService;
import com.dwp.services.synapsex.service.admin.ThresholdQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * Synapse Admin - Currency/Threshold Policy (rule_threshold).
 * Gateway: /api/synapse/admin/** → /synapse/admin/**
 */
@RestController
@RequestMapping("/synapse/admin/thresholds")
@RequiredArgsConstructor
public class AdminThresholdController {

    private final ThresholdQueryService queryService;
    private final ThresholdCommandService commandService;

    @GetMapping
    public ApiResponse<Page<ThresholdDto>> search(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String waers,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ThresholdDto> page = queryService.search(tenantId, profileId, dimension, waers, q, pageable);
        return ApiResponse.success(page);
    }

    @PostMapping
    public ApiResponse<ThresholdDto> upsert(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody UpsertThresholdRequest request) {
        ThresholdDto dto = commandService.upsert(tenantId, actorUserId != null ? actorUserId : 0L, request);
        return ApiResponse.success("한도 정책이 저장되었습니다.", dto);
    }

    @DeleteMapping("/{thresholdId}")
    public ApiResponse<Void> delete(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long thresholdId) {
        commandService.delete(tenantId, thresholdId);
        return ApiResponse.success();
    }
}
