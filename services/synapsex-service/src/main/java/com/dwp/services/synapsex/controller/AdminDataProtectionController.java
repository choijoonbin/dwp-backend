package com.dwp.services.synapsex.controller;

import io.swagger.v3.oas.annotations.Operation;
import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.DataProtectionDto;
import com.dwp.services.synapsex.dto.admin.UpdateDataProtectionRequest;
import com.dwp.services.synapsex.service.admin.DataProtectionCommandService;
import com.dwp.services.synapsex.service.admin.DataProtectionQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Synapse Admin - Data Protection (Encryption & Retention).
 * GET /api/synapse/admin/data-protection?profileId=
 * PUT /api/synapse/admin/data-protection
 */
@RestController
@RequestMapping("/synapse/admin")
@RequiredArgsConstructor
public class AdminDataProtectionController {

    private final DataProtectionQueryService queryService;
    private final DataProtectionCommandService commandService;

    @Operation(summary = "데이터 보호 정책 조회", description = "tenant+profileId 기준 조회, 없으면 기본 행 생성")
    @GetMapping("/data-protection")
    public ApiResponse<DataProtectionDto> get(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam Long profileId) {
        DataProtectionDto dto = queryService.getOrCreateByProfile(tenantId, profileId);
        return ApiResponse.success(dto);
    }

    @Operation(summary = "데이터 보호 정책 저장", description = "auditRetentionYears 1~20, keyProvider KMS_MOCK|KMS|HSM, exportMode ZIP|CSV")
    @PutMapping("/data-protection")
    public ApiResponse<DataProtectionDto> put(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody UpdateDataProtectionRequest request) {
        DataProtectionDto dto = commandService.upsert(tenantId, actorUserId != null ? actorUserId : 0L, request);
        return ApiResponse.success("데이터 보호 정책이 저장되었습니다.", dto);
    }
}
