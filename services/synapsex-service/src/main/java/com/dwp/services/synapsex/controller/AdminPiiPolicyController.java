package com.dwp.services.synapsex.controller;

import io.swagger.v3.oas.annotations.Operation;
import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.BulkPiiPolicyRequest;
import com.dwp.services.synapsex.dto.admin.PiiFieldCatalogDto;
import com.dwp.services.synapsex.dto.admin.PiiPolicyDto;
import com.dwp.services.synapsex.service.admin.PiiFieldCatalogService;
import com.dwp.services.synapsex.service.admin.PiiPolicyCommandService;
import com.dwp.services.synapsex.service.admin.PiiPolicyQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Synapse Admin - PII Policy (policy_pii_field).
 * Gateway: /api/synapse/admin/** → /synapse/admin/**
 */
@RestController
@RequestMapping("/synapse/admin")
@RequiredArgsConstructor
public class AdminPiiPolicyController {

    private final PiiPolicyQueryService queryService;
    private final PiiPolicyCommandService commandService;
    private final PiiFieldCatalogService catalogService;

    /** GET /pii-policies?profileId= — tenant+profileId 기준 PII 정책 조회 */
    @GetMapping("/pii-policies")
    public ApiResponse<List<PiiPolicyDto>> list(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam Long profileId) {
        List<PiiPolicyDto> list = queryService.listByProfile(tenantId, profileId);
        return ApiResponse.success(list);
    }

    @Operation(summary = "PII 정책 일괄 저장", description = "profileId 기준 upsert, handling 검증")
    @PutMapping("/pii-policies/bulk")
    public ApiResponse<List<PiiPolicyDto>> bulkSave(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody BulkPiiPolicyRequest request) {
        List<PiiPolicyDto> list = commandService.bulkSave(tenantId, actorUserId != null ? actorUserId : 0L, request);
        return ApiResponse.success("PII 정책이 일괄 저장되었습니다.", list);
    }

    /** GET /pii-fields/catalog — tenant-agnostic 필드 카탈로그 */
    @GetMapping("/pii-fields/catalog")
    public ApiResponse<PiiFieldCatalogDto> catalog() {
        return ApiResponse.success(catalogService.getCatalog());
    }
}
