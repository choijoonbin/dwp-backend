package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.OidcService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/auth")
public class LoginController {

    private final AuthService authService;
    private final OidcService oidcService;

    public LoginController(AuthService authService, OidcService oidcService) {
        this.authService = authService;
        this.oidcService = oidcService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(authService.login(request, servletRequest));
    }

    @GetMapping("/oidc/login")
    public RedirectView oidcLogin(
            @RequestHeader(value = "X-Tenant-ID", required = false) Long tenantHeader,
            @RequestParam(value = "tenantId", required = false) Long tenantParameter,
            @RequestParam("providerKey") String providerKey) {
        Long tenantId = tenantHeader != null ? tenantHeader : tenantParameter;
        if (tenantId == null) throw new BaseException(ErrorCode.TENANT_MISSING);
        return new RedirectView(oidcService.getAuthorizationUrl(tenantId, providerKey));
    }

    @GetMapping("/oidc/callback")
    public ApiResponse<LoginResponse> oidcCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest servletRequest) {
        OidcService.OidcExchangeResult result = oidcService.exchange(state, code);
        return ApiResponse.success(authService.loginWithOidc(
                result.tenantId(),
                result.providerKey(),
                result.userInfo(),
                servletRequest));
    }
}
