package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.LoginOptionsResponse;
import com.dwp.services.auth.dto.MeResponse;
import com.dwp.services.auth.service.AuthPolicyService;
import com.dwp.services.auth.service.AuthSessionService;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.IdentityProviderService;
import com.dwp.services.auth.service.LoginDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerDiscoveryTest {

    private final LoginDiscoveryService loginDiscoveryService =
            mock(LoginDiscoveryService.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);
    private AuthController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new AuthController(
                mock(AuthPolicyService.class),
                mock(IdentityProviderService.class),
                loginDiscoveryService,
                authService,
                authSessionService,
                "urn:dwp:acr:mfa",
                600);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .build();
    }

    @Test
    void publicPolicySerializesOnlyTheThreeSignInAffordanceFields() throws Exception {
        when(loginDiscoveryService.getLoginOptions(7L)).thenReturn(LoginOptionsResponse.builder()
                .localLoginAvailable(true)
                .ssoLoginAvailable(true)
                .preferredLoginType("SSO")
                .build());

        mockMvc.perform(get("/auth/policy").header("X-Tenant-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localLoginAvailable").value(true))
                .andExpect(jsonPath("$.data.ssoLoginAvailable").value(true))
                .andExpect(jsonPath("$.data.preferredLoginType").value("SSO"))
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.allowedLoginTypes").doesNotExist())
                .andExpect(jsonPath("$.data.ssoProviderKey").doesNotExist())
                .andExpect(jsonPath("$.data.providerKey").doesNotExist())
                .andExpect(jsonPath("$.data.clientId").doesNotExist())
                .andExpect(jsonPath("$.data.issuer").doesNotExist())
                .andExpect(jsonPath("$.data.requireMfa").doesNotExist());
    }

    @Test
    void meProjectsOnlyTheDatabaseVerifiedActiveAccessMode() {
        UUID familyId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("7")
                .claim("jti", UUID.randomUUID().toString())
                .claim("tenant_id", "3")
                .claim("sid", familyId.toString())
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(authService.getMe(7L, 3L, null)).thenReturn(MeResponse.builder()
                .userId(7L)
                .tenantId(3L)
                .identityPlane("TENANT")
                .build());
        when(authSessionService.resolveActiveAccessMode(
                eq(jwt), eq(7L), eq(3L), eq("urn:dwp:acr:mfa"), eq(600L)))
                .thenReturn(AuthSessionService.ActiveAccessMode.ELEVATED);

        MeResponse response = controller.getMe(
                new JwtAuthenticationToken(jwt), null, null).getData();

        assertThat(response.getSessionFamilyId()).isEqualTo(familyId);
        assertThat(response.getActiveAccessMode()).isEqualTo("ELEVATED");
    }
}
