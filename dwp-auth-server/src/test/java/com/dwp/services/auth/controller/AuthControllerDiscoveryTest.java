package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.LoginOptionsResponse;
import com.dwp.services.auth.service.AuthPolicyService;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.IdentityProviderService;
import com.dwp.services.auth.service.LoginDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerDiscoveryTest {

    private final LoginDiscoveryService loginDiscoveryService =
            mock(LoginDiscoveryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(
                mock(AuthPolicyService.class),
                mock(IdentityProviderService.class),
                loginDiscoveryService,
                mock(AuthService.class)))
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
}
