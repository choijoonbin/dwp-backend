package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.service.AuthenticatedSession;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.OidcService;
import com.dwp.services.auth.service.OidcStateStore;
import com.dwp.services.auth.service.LoginDiscoveryService;
import com.dwp.services.auth.service.SessionCookieService;
import com.dwp.services.auth.service.StepUpBrowserBindingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class LoginController {

    private final AuthService authService;
    private final OidcService oidcService;
    private final LoginDiscoveryService loginDiscoveryService;
    private final SessionCookieService sessionCookieService;
    private final StepUpBrowserBindingService stepUpBrowserBindingService;

    public LoginController(
            AuthService authService,
            OidcService oidcService,
            LoginDiscoveryService loginDiscoveryService,
            SessionCookieService sessionCookieService,
            StepUpBrowserBindingService stepUpBrowserBindingService) {
        this.authService = authService;
        this.oidcService = oidcService;
        this.loginDiscoveryService = loginDiscoveryService;
        this.sessionCookieService = sessionCookieService;
        this.stepUpBrowserBindingService = stepUpBrowserBindingService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        AuthenticatedSession session = authService.login(request, servletRequest);
        sessionCookieService.write(
                servletResponse,
                session.accessToken(),
                session.response().getExpiresIn());
        return ApiResponse.success(session.response());
    }

    @GetMapping("/oidc/login")
    public RedirectView oidcLogin(
            @RequestHeader(value = "X-Tenant-ID", required = false) Long tenantHeader,
            @RequestParam(value = "tenantId", required = false) Long tenantParameter) {
        Long tenantId = tenantHeader != null ? tenantHeader : tenantParameter;
        if (tenantId == null) throw new BaseException(ErrorCode.TENANT_MISSING);
        String providerKey = loginDiscoveryService.requireSsoProviderKey(tenantId);
        try {
            return new RedirectView(oidcService.getAuthorizationUrl(tenantId, providerKey));
        } catch (BaseException exception) {
            // Keep unavailable, unknown, and incomplete tenant IdP configurations indistinguishable.
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    @GetMapping("/oidc/callback")
    public ApiResponse<LoginResponse> oidcCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        OidcService.OidcExchangeResult result = oidcService.exchange(state, code);
        AuthenticatedSession session = result.context().purpose() == OidcStateStore.Purpose.STEP_UP
                ? completeStepUp(result, authentication, servletRequest, servletResponse)
                : authService.loginWithOidc(
                        result.tenantId(), result.providerKey(), result.userInfo(), servletRequest);
        sessionCookieService.write(
                servletResponse,
                session.accessToken(),
                session.response().getExpiresIn());
        return ApiResponse.success(session.response());
    }

    private AuthenticatedSession completeStepUp(
            OidcService.OidcExchangeResult result,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        OidcStateStore.StateContext context = result.context();
        long actorId;
        UUID familyId;
        try {
            actorId = Long.parseLong(jwt.getSubject());
            familyId = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
        if (!Objects.equals(context.actorId(), actorId)
                || !Objects.equals(context.tenantId(), result.tenantId())
                || !Objects.equals(context.tokenId(), jwt.getId())
                || !Objects.equals(context.sessionFamilyId(), familyId)) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        stepUpBrowserBindingService.require(request, context.browserBinding());
        AuthenticatedSession session = authService.completeOidcStepUp(
                result.tenantId(), result.providerKey(), result.userInfo(), jwt,
                context.sessionFamilyId(), request);
        stepUpBrowserBindingService.clear(response);
        response.setHeader("X-DWP-Step-Up-Flow-ID", context.flowRef());
        response.setHeader("X-DWP-Step-Up-Return-To", context.returnPath());
        return session;
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            Authentication authentication,
            HttpServletResponse servletResponse) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            authService.revokeSession(jwt.getId());
        }
        sessionCookieService.clear(servletResponse);
        return ApiResponse.success();
    }
}
