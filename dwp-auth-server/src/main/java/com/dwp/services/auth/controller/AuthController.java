package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.dto.IdentityProviderResponse;
import com.dwp.services.auth.dto.MeResponse;
import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.service.AuthPolicyService;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.IdentityProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 인증 관련 공개 API 컨트롤러
 * 
 * 프론트엔드가 로그인 UI를 자동 분기하기 위한 정책 조회 API를 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthPolicyService authPolicyService;
    private final IdentityProviderService identityProviderService;
    private final AuthService authService;
    
    /**
     * 테넌트별 로그인 정책 조회
     * GET /api/auth/policy
     * 
     * 프론트엔드가 로그인 UI를 자동 분기하기 위해 사용합니다.
     */
    @GetMapping("/policy")
    public ApiResponse<AuthPolicyResponse> getAuthPolicy(
            @RequestHeader("X-Tenant-ID") Long tenantId) {
        return ApiResponse.success(authPolicyService.getAuthPolicy(tenantId));
    }
    
    /**
     * 테넌트별 Identity Provider 정보 조회
     * GET /api/auth/idp
     * 
     * 활성화된 SSO Identity Provider 목록을 반환합니다.
     */
    @GetMapping("/idp")
    public ApiResponse<List<IdentityProviderResponse>> getIdentityProviders(
            @RequestHeader("X-Tenant-ID") Long tenantId) {
        return ApiResponse.success(identityProviderService.getIdentityProviders(tenantId));
    }
    
    /**
     * 특정 Provider Key의 Identity Provider 조회
     * GET /api/auth/idp/{providerKey}
     */
    @GetMapping("/idp/{providerKey}")
    public ApiResponse<IdentityProviderResponse> getIdentityProviderByKey(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable String providerKey) {
        IdentityProviderResponse response = identityProviderService.getIdentityProviderByKey(tenantId, providerKey);
        if (response == null) {
            return ApiResponse.error(com.dwp.core.common.ErrorCode.ENTITY_NOT_FOUND, "Identity Provider를 찾을 수 없습니다.");
        }
        return ApiResponse.success(response);
    }
    
    /**
     * 내 정보 조회
     * GET /api/auth/me
     * 
     * JWT 토큰에서 사용자 정보를 추출하여 반환합니다.
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> getMe(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId = Long.parseLong(jwt.getSubject());
        Long tenantId = parseTenantId(tenantIdHeader, jwt);
        
        MeResponse response = authService.getMe(userId, tenantId);
        return ApiResponse.success(response);
    }
    
    /**
     * 내 권한 목록 조회
     * GET /api/auth/permissions
     * 
     * 현재 사용자가 가진 모든 권한을 반환합니다.
     */
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionDTO>> getMyPermissions(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId = Long.parseLong(jwt.getSubject());
        Long tenantId = parseTenantId(tenantIdHeader, jwt);
        
        List<PermissionDTO> permissions = authService.getMyPermissions(userId, tenantId);
        return ApiResponse.success(permissions);
    }
    
    /**
     * 테넌트 ID 파싱 헬퍼 메서드
     * 헤더 → JWT 클레임 순서로 조회
     */
    private Long parseTenantId(String header, Jwt jwt) {
        if (header != null && !header.trim().isEmpty()) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                // 헤더 파싱 실패 시 JWT 클레임 사용
            }
        }
        // JWT 클레임에서 tenant_id 추출
        Object tid = jwt.getClaim("tenant_id");
        if (tid != null) {
            return Long.parseLong(tid.toString());
        }
        throw new IllegalArgumentException("X-Tenant-ID 헤더 또는 JWT tenant_id 클레임이 필요합니다");
    }
}
