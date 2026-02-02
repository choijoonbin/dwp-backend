package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.admin.ConfigProfileDto;
import com.dwp.services.synapsex.dto.admin.CreateProfileRequest;
import com.dwp.services.synapsex.dto.admin.UpdateProfileRequest;
import com.dwp.services.synapsex.service.admin.ConfigProfileCommandService;
import com.dwp.services.synapsex.service.admin.ConfigProfileQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Synapse Admin - Config Profile API.
 * Gateway: /api/synapse/admin/** → /synapse/admin/**
 */
@RestController
@RequestMapping("/synapse/admin/profiles")
@RequiredArgsConstructor
public class AdminProfileController {

    private final ConfigProfileQueryService queryService;
    private final ConfigProfileCommandService commandService;

    @GetMapping
    public ApiResponse<List<ConfigProfileDto>> list(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        List<ConfigProfileDto> list = queryService.listByTenant(tenantId);
        return ApiResponse.success(list);
    }

    @PostMapping
    public ApiResponse<ConfigProfileDto> create(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody CreateProfileRequest request) {
        ConfigProfileDto dto = commandService.create(tenantId, actorUserId != null ? actorUserId : 0L, request);
        return ApiResponse.success("프로파일이 생성되었습니다.", dto);
    }

    @PutMapping("/{profileId}")
    public ApiResponse<ConfigProfileDto> update(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long profileId,
            @Valid @RequestBody UpdateProfileRequest request) {
        ConfigProfileDto dto = commandService.update(tenantId, actorUserId != null ? actorUserId : 0L, profileId, request);
        return ApiResponse.success("프로파일이 수정되었습니다.", dto);
    }

    @PutMapping("/{profileId}/default")
    public ApiResponse<ConfigProfileDto> setDefault(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long profileId) {
        commandService.setDefault(tenantId, actorUserId != null ? actorUserId : 0L, profileId);
        ConfigProfileDto dto = queryService.getByTenantAndId(tenantId, profileId);
        return ApiResponse.success("기본 프로파일로 설정되었습니다.", dto);
    }

    @DeleteMapping("/{profileId}")
    public ApiResponse<Void> delete(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long profileId) {
        commandService.delete(tenantId, profileId);
        return ApiResponse.success();
    }
}
