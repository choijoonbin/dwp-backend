package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AuthSessionResponse;
import com.dwp.services.auth.dto.SessionRotationResponse;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.AuthSessionService;
import com.dwp.services.auth.service.SessionCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class SessionController {

    private final AuthSessionService authSessionService;
    private final AuthService authService;
    private final SessionCookieService sessionCookieService;

    public SessionController(
            AuthSessionService authSessionService,
            AuthService authService,
            SessionCookieService sessionCookieService) {
        this.authSessionService = authSessionService;
        this.authService = authService;
        this.sessionCookieService = sessionCookieService;
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AuthSessionResponse>> getSessions(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        Jwt jwt = requireJwt(authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(authSessionService.list(jwt.getId(), userId, tenantId));
    }

    @PostMapping("/session/refresh")
    public ApiResponse<SessionRotationResponse> refresh(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        Jwt jwt = requireJwt(authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        AuthSessionService.RotationResult result = authSessionService.rotate(
                jwt,
                userId,
                tenantId,
                authService.getRoleCodes(userId, tenantId),
                request);
        if (result.accessToken() != null) {
            sessionCookieService.write(
                    response,
                    result.accessToken(),
                    result.cookieMaxAgeSeconds());
        }
        return ApiResponse.success(result.response());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> revoke(
            @PathVariable UUID sessionId,
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader,
            HttpServletResponse response) {
        Jwt jwt = requireJwt(authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        boolean current = authSessionService.revokeFamily(
                sessionId,
                jwt.getId(),
                userId,
                tenantId);
        if (current) sessionCookieService.clear(response);
        return ApiResponse.success();
    }

    @PostMapping("/sessions/logout-others")
    public ApiResponse<Void> logoutOthers(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        Jwt jwt = requireJwt(authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        authSessionService.revokeOthers(jwt.getId(), userId, tenantId);
        return ApiResponse.success();
    }

    private static Jwt requireJwt(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        return jwt;
    }
}
