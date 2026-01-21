package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.sso.OidcService;
import com.dwp.services.auth.service.sso.OidcUserInfo;
import com.dwp.services.auth.service.sso.SamlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.UUID;

/**
 * 로그인 컨트롤러
 * 
 * LOCAL 인증 및 SSO(OIDC/SAML) 인증을 처리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class LoginController {
    
    private final AuthService authService;
    private final OidcService oidcService;
    private final SamlService samlService;
    
    /**
     * PR-10A: 로그인 및 JWT 토큰 발급 (LOCAL)
     * POST /api/auth/login
     * 
     * @param request 로그인 요청 (username, password, tenantId)
     * @return JWT 토큰 및 사용자 정보
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request: username={}, tenantId={}", request.getUsername(), request.getTenantId());
        LoginResponse response = authService.login(request);
        return ApiResponse.success(response);
    }
    
    /**
     * PR-10B: OIDC 로그인 시작 (Authorization URL 리다이렉트)
     * GET /api/auth/oidc/login?providerKey=AZURE_AD
     */
    @GetMapping("/oidc/login")
    public RedirectView oidcLogin(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam("providerKey") String providerKey,
            HttpServletRequest request) {
        // CSRF 방지를 위한 state 생성
        String state = UUID.randomUUID().toString();
        // TODO: state를 세션이나 Redis에 저장하여 검증
        
        String authorizationUrl = oidcService.getAuthorizationUrl(tenantId, providerKey, state);
        if (authorizationUrl == null) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.INTERNAL_SERVER_ERROR, "Failed to generate authorization URL");
        }
        log.info("OIDC login redirect: tenantId={}, providerKey={}, url={}", tenantId, providerKey, authorizationUrl);
        
        return new RedirectView(authorizationUrl);
    }
    
    /**
     * PR-10B: OIDC 콜백 (Authorization Code를 받아서 처리)
     * GET /api/auth/oidc/callback?code=xxx&state=xxx&providerKey=AZURE_AD
     */
    @GetMapping("/oidc/callback")
    public ApiResponse<LoginResponse> oidcCallback(
            @RequestHeader(value = "X-Tenant-ID", required = false) Long tenantId,
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestParam(value = "providerKey", required = false) String providerKey,
            HttpServletRequest request) {
        // TODO: state 검증
        
        // tenantId가 없으면 쿼리 파라미터에서 추출 (리다이렉트 후)
        if (tenantId == null) {
            String tenantIdParam = request.getParameter("tenantId");
            if (tenantIdParam != null) {
                try {
                    tenantId = Long.parseLong(tenantIdParam);
                } catch (NumberFormatException e) {
                    throw new com.dwp.core.exception.BaseException(
                            com.dwp.core.common.ErrorCode.TENANT_MISSING, "테넌트 정보가 필요합니다.");
                }
            } else {
                throw new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.TENANT_MISSING, "테넌트 정보가 필요합니다.");
            }
        }
        
        // providerKey가 없으면 기본 SSO Provider 사용
        if (providerKey == null || providerKey.isEmpty()) {
            // AuthPolicy에서 기본 SSO Provider 조회
            // 간단히 첫 번째 활성화된 OIDC Provider 사용
            providerKey = "AZURE_AD"; // TODO: 동적으로 조회
        }
        
        // Code를 UserInfo로 교환
        OidcUserInfo userInfo = oidcService.exchangeCodeForUserInfo(tenantId, providerKey, code, state);
        
        // 사용자 계정 찾기 또는 생성
        // JWT 토큰 발급 (LOCAL과 동일한 방식)
        LoginResponse response = authService.loginWithSso(tenantId, providerKey, userInfo, request);
        
        return ApiResponse.success(response);
    }
    
    /**
     * PR-10C: SAML 로그인 시작 (Skeleton)
     * GET /api/auth/saml/login?providerKey=SAML_SKT
     */
    @GetMapping("/saml/login")
    public RedirectView samlLogin(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam("providerKey") String providerKey,
            HttpServletRequest request) {
        String samlUrl = samlService.initiateSamlLogin(tenantId, providerKey);
        if (samlUrl == null) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.INTERNAL_SERVER_ERROR, "Failed to generate SAML URL");
        }
        log.info("SAML login redirect: tenantId={}, providerKey={}, url={}", tenantId, providerKey, samlUrl);
        return new RedirectView(samlUrl);
    }
    
    /**
     * PR-10C: SAML 콜백 (SAML Response 처리, Skeleton)
     * POST /api/auth/saml/callback
     */
    @PostMapping("/saml/callback")
    public ApiResponse<LoginResponse> samlCallback(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(value = "SAMLResponse", required = false) String samlResponse,
            @RequestParam(value = "providerKey", required = false) String providerKey,
            HttpServletRequest request) {
        // TODO: SAML Response 파싱 및 사용자 정보 추출
        // 실제 구현은 다음 PR로 분리
        log.info("SAML callback received: tenantId={}, providerKey={} (skeleton)", tenantId, providerKey);
        
        // Skeleton: 에러 반환 (실제 구현 필요)
        throw new com.dwp.core.exception.BaseException(
                com.dwp.core.common.ErrorCode.INTERNAL_SERVER_ERROR, 
                "SAML 연동은 아직 구현되지 않았습니다. 다음 PR에서 구현 예정입니다.");
    }
}
