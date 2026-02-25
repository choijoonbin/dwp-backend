package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 내부 통신용 사용자 API
 *
 * Synapsex 등에서 Team Snapshot display_name 조회 시 Feign으로 호출합니다.
 * /internal/** 는 permitAll (서비스 간 내부 통신).
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 사용자 display_name 배치 조회
     * GET /internal/users/display-names?ids=1,2,3
     *
     * @param tenantId X-Tenant-ID 헤더 (필수)
     * @param ids      쉼표 구분 user_id 목록
     * @return userId → displayName 맵 (해당 tenant 내 사용자만)
     */
    @GetMapping("/display-names")
    public ApiResponse<Map<String, String>> getDisplayNames(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String ids) {

        if (ids == null || ids.isBlank()) {
            return ApiResponse.success(Map.of());
        }

        List<Long> userIdList = java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(id -> id != null)
                .distinct()
                .limit(200)
                .toList();

        if (userIdList.isEmpty()) {
            return ApiResponse.success(Map.of());
        }

        List<User> users = userRepository.findByTenantIdAndUserIdIn(tenantId, userIdList);
        Map<String, String> result = users.stream()
                .collect(Collectors.toMap(u -> String.valueOf(u.getUserId()), User::getDisplayName, (a, b) -> a));

        return ApiResponse.success(result);
    }

    /**
     * 로그인 식별자(principal 또는 email)로 user_id 조회
     * GET /internal/users/resolve-id?loginId=synapsex_operator
     */
    @GetMapping("/resolve-id")
    public ApiResponse<Long> resolveUserId(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam("loginId") String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return ApiResponse.success(null);
        }
        String normalized = loginId.trim();
        return userAccountRepository.findFirstByTenantIdAndPrincipalIgnoreCase(tenantId, normalized)
                .map(ua -> ApiResponse.success(ua.getUserId()))
                .or(() -> userRepository.findByTenantIdAndEmail(tenantId, normalized)
                        .map(User::getUserId)
                        .map(ApiResponse::success))
                .orElseGet(() -> ApiResponse.success(null));
    }
}
