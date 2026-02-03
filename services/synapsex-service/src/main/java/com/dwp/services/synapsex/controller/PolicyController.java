package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.policy.EffectivePolicyDto;
import com.dwp.services.synapsex.dto.policy.PolicyProfileDetailDto;
import com.dwp.services.synapsex.dto.policy.PolicyProfileListDto;
import com.dwp.services.synapsex.service.policy.PolicyQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 3 Policies read-optimized API
 */
@RestController
@RequestMapping("/synapse/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyQueryService policyQueryService;

    @GetMapping("/profiles")
    public ApiResponse<PolicyProfileListDto> listProfiles(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        PolicyProfileListDto result = policyQueryService.listProfiles(tenantId);
        return ApiResponse.success(result);
    }

    @GetMapping("/profiles/{profileId}")
    public ApiResponse<PolicyProfileDetailDto> getProfileDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long profileId) {
        PolicyProfileDetailDto dto = policyQueryService.getProfileDetail(tenantId, profileId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    @GetMapping("/effective")
    public ApiResponse<EffectivePolicyDto> getEffectivePolicy(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String bukrs) {
        EffectivePolicyDto dto = policyQueryService.getEffectivePolicy(tenantId, profileId, bukrs)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "유효한 정책을 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
