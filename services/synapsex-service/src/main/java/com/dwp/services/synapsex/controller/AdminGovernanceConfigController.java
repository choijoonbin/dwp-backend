package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.GovernanceConfigItemDto;
import com.dwp.services.synapsex.dto.admin.UpdateGovernanceConfigRequest;
import com.dwp.services.synapsex.service.admin.GovernanceConfigCommandService;
import com.dwp.services.synapsex.service.admin.GovernanceConfigQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Synapse Admin - 거버넌스 설정 (RBAC, SoD, Saved Views Scope, PII Handling).
 * ADMIN 전용. Gateway에서 menu.admin 또는 동등 권한 검증 후 호출.
 * Base: /api/synapse/admin/governance-config
 */
@RestController
@RequestMapping("/synapse/admin/governance-config")
@RequiredArgsConstructor
public class AdminGovernanceConfigController {

    private final GovernanceConfigQueryService queryService;
    private final GovernanceConfigCommandService commandService;

    /**
     * 거버넌스 설정 목록 (현재값 + 선택지).
     * GET /api/synapse/admin/governance-config
     */
    @GetMapping
    public ApiResponse<List<GovernanceConfigItemDto>> list(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        List<GovernanceConfigItemDto> list = queryService.listGovernanceConfig(tenantId);
        return ApiResponse.success(list);
    }

    /**
     * 거버넌스 설정 값 업데이트 (프론트에서 항목 클릭 시).
     * PATCH /api/synapse/admin/governance-config/{configKey}
     * Body: { "value": "RBAC" } (해당 그룹의 코드 값)
     */
    @PatchMapping("/{configKey}")
    public ApiResponse<Void> update(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable String configKey,
            @Valid @RequestBody UpdateGovernanceConfigRequest request) {
        commandService.updateConfigValue(
                tenantId,
                actorUserId != null ? actorUserId : 0L,
                configKey,
                request);
        return ApiResponse.success();
    }
}
