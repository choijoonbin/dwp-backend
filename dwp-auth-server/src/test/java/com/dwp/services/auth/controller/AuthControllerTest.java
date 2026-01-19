package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.dto.IdentityProviderResponse;
import com.dwp.services.auth.service.AuthPolicyService;
import com.dwp.services.auth.service.IdentityProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 테스트
 */
@WebMvcTest(AuthController.class)
@SuppressWarnings("null")
class AuthControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0 but still functional
    @MockBean
    private AuthPolicyService authPolicyService;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0 but still functional
    @MockBean
    private IdentityProviderService identityProviderService;
    
    @Test
    void getAuthPolicy_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        AuthPolicyResponse response = AuthPolicyResponse.builder()
                .tenantId(tenantId)
                .defaultLoginType("LOCAL")
                .allowedLoginTypes(Arrays.asList("LOCAL"))
                .localLoginEnabled(true)
                .ssoLoginEnabled(false)
                .ssoProviderKey(null)
                .requireMfa(false)
                .build();
        
        when(authPolicyService.getAuthPolicy(tenantId))
                .thenReturn(response);
        
        // When & Then
        mockMvc.perform(get("/api/auth/policy")
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(tenantId))
                .andExpect(jsonPath("$.data.defaultLoginType").value("LOCAL"))
                .andExpect(jsonPath("$.data.allowedLoginTypes[0]").value("LOCAL"))
                .andExpect(jsonPath("$.data.localLoginEnabled").value(true))
                .andExpect(jsonPath("$.data.ssoLoginEnabled").value(false));
    }
    
    @Test
    void getAuthPolicy_DefaultPolicy_WhenNotFound() throws Exception {
        // Given
        Long tenantId = 999L;
        AuthPolicyResponse defaultResponse = AuthPolicyResponse.builder()
                .tenantId(tenantId)
                .defaultLoginType("LOCAL")
                .allowedLoginTypes(Arrays.asList("LOCAL"))
                .localLoginEnabled(true)
                .ssoLoginEnabled(false)
                .requireMfa(false)
                .build();
        
        when(authPolicyService.getAuthPolicy(tenantId))
                .thenReturn(defaultResponse);
        
        // When & Then
        mockMvc.perform(get("/api/auth/policy")
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.defaultLoginType").value("LOCAL"));
    }
    
    @Test
    void getIdentityProviders_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        IdentityProviderResponse idp1 = IdentityProviderResponse.builder()
                .tenantId(tenantId)
                .enabled(true)
                .providerType("OIDC")
                .providerKey("AZURE_AD")
                .authUrl("https://login.microsoftonline.com/.../authorize")
                .metadataUrl("https://login.microsoftonline.com/.../.well-known/openid-configuration")
                .clientId("client-123")
                .build();
        
        List<IdentityProviderResponse> providers = Arrays.asList(idp1);
        
        when(identityProviderService.getIdentityProviders(tenantId))
                .thenReturn(providers);
        
        // When & Then
        mockMvc.perform(get("/api/auth/idp")
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].providerKey").value("AZURE_AD"));
    }
    
    @Test
    void getIdentityProviders_EmptyList_WhenNoEnabled() throws Exception {
        // Given
        Long tenantId = 1L;
        
        when(identityProviderService.getIdentityProviders(tenantId))
                .thenReturn(Collections.emptyList());
        
        // When & Then
        mockMvc.perform(get("/api/auth/idp")
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
    
    @Test
    void getIdentityProviderByKey_Success() throws Exception {
        // Given
        Long tenantId = 1L;
        String providerKey = "AZURE_AD";
        IdentityProviderResponse response = IdentityProviderResponse.builder()
                .tenantId(tenantId)
                .enabled(true)
                .providerType("OIDC")
                .providerKey(providerKey)
                .authUrl("https://login.microsoftonline.com/.../authorize")
                .build();
        
        when(identityProviderService.getIdentityProviderByKey(tenantId, providerKey))
                .thenReturn(response);
        
        // When & Then
        mockMvc.perform(get("/api/auth/idp/{providerKey}", providerKey)
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.providerKey").value(providerKey));
    }
    
    @Test
    void getIdentityProviderByKey_NotFound() throws Exception {
        // Given
        Long tenantId = 1L;
        String providerKey = "UNKNOWN";
        
        when(identityProviderService.getIdentityProviderByKey(tenantId, providerKey))
                .thenReturn(null);
        
        // When & Then
        mockMvc.perform(get("/api/auth/idp/{providerKey}", providerKey)
                        .header("X-Tenant-ID", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").exists());
    }
}
