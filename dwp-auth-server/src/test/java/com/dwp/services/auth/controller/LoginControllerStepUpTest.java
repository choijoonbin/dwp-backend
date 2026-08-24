package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.dto.OidcUserInfo;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.AuthenticatedSession;
import com.dwp.services.auth.service.OidcService;
import com.dwp.services.auth.service.OidcStateStore;
import com.dwp.services.auth.service.SessionCookieService;
import com.dwp.services.auth.service.StepUpBrowserBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginControllerStepUpTest {

    private static final UUID FAMILY_ID =
            UUID.fromString("ba2cd67b-c893-44ef-b95b-ec8355268da0");

    private AuthService authService;
    private OidcService oidcService;
    private SessionCookieService cookieService;
    private StepUpBrowserBindingService browserBindingService;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        oidcService = mock(OidcService.class);
        cookieService = mock(SessionCookieService.class);
        browserBindingService = mock(StepUpBrowserBindingService.class);
        controller = new LoginController(
                authService, oidcService, cookieService, browserBindingService);
    }

    @Test
    void callbackReturnsTheSameOpaqueFlowOnlyForTheBoundSessionAndBrowser() {
        OidcStateStore.StateContext context = context(FAMILY_ID, "token-id", 19L);
        when(oidcService.exchange("state", "code")).thenReturn(exchange(context));
        when(authService.completeOidcStepUp(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuthenticatedSession(
                        "elevated-token", LoginResponse.builder().expiresIn(600L).build()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.oidcCallback(
                "code", "state", new JwtAuthenticationToken(jwt(FAMILY_ID, "token-id", 19L)),
                new MockHttpServletRequest(), response);

        assertThat(response.getHeader("X-DWP-Step-Up-Flow-ID"))
                .isEqualTo("3c78d2dd-bb75-47e5-bcec-d70e2a2867ce");
        assertThat(response.getHeader("X-DWP-Step-Up-Return-To")).isEqualTo("/approvals");
        verify(browserBindingService).require(any(), org.mockito.ArgumentMatchers.eq("browser-hash"));
        verify(browserBindingService).clear(response);
        verify(cookieService).write(response, "elevated-token", 600L);
    }

    @Test
    void wrongActorFamilyOrPresentedSessionFailsBeforeElevation() {
        when(oidcService.exchange("state", "code")).thenReturn(
                exchange(context(FAMILY_ID, "token-id", 19L)));

        assertThatThrownBy(() -> controller.oidcCallback(
                "code", "state",
                new JwtAuthenticationToken(jwt(UUID.randomUUID(), "token-id", 19L)),
                new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.oidcCallback(
                "code", "state",
                new JwtAuthenticationToken(jwt(FAMILY_ID, "wrong-token", 19L)),
                new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.oidcCallback(
                "code", "state",
                new JwtAuthenticationToken(jwt(FAMILY_ID, "token-id", 20L)),
                new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class);
        verify(authService, never()).completeOidcStepUp(any(), any(), any(), any(), any(), any());
    }

    @Test
    void wrongBrowserBindingFailsBeforeElevation() {
        when(oidcService.exchange("state", "code")).thenReturn(
                exchange(context(FAMILY_ID, "token-id", 19L)));
        doThrow(new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS))
                .when(browserBindingService).require(any(), any());

        assertThatThrownBy(() -> controller.oidcCallback(
                "code", "state", new JwtAuthenticationToken(jwt(FAMILY_ID, "token-id", 19L)),
                new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class);
        verify(authService, never()).completeOidcStepUp(any(), any(), any(), any(), any(), any());
    }

    private OidcService.OidcExchangeResult exchange(OidcStateStore.StateContext context) {
        return new OidcService.OidcExchangeResult(
                7L, "corp",
                new OidcUserInfo(
                        "https://idp.example.com", "subject", "actor@example.com", true,
                        "Actor", Instant.now(), "urn:dwp:acr:mfa",
                        List.of("mfa", "otp", "pwd")),
                context);
    }

    private OidcStateStore.StateContext context(
            UUID familyId,
            String tokenId,
            long actorId) {
        Instant now = Instant.now();
        return new OidcStateStore.StateContext(
                OidcStateStore.Purpose.STEP_UP,
                "3c78d2dd-bb75-47e5-bcec-d70e2a2867ce",
                7L, "corp", "nonce", "verifier", actorId, familyId, tokenId,
                "browser-hash", "urn:dwp:acr:mfa", List.of("pwd", "otp"), 600,
                "/approvals", "command-digest", "source-revision",
                now.minusSeconds(10), now.plusSeconds(600));
    }

    private Jwt jwt(UUID familyId, String tokenId, long actorId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("session")
                .header("alg", "HS256")
                .subject(Long.toString(actorId))
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(600))
                .claim("jti", tokenId)
                .claim("sid", familyId.toString())
                .claim("tenant_id", "7")
                .build();
    }
}
