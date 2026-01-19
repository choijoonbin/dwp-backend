package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import com.dwp.services.auth.util.CodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AuthPolicyService 테스트
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class AuthPolicyServiceTest {
    
    @Mock
    private AuthPolicyRepository authPolicyRepository;
    
    @Mock
    private CodeResolver codeResolver;
    
    @InjectMocks
    private AuthPolicyService authPolicyService;
    
    private Long tenantId;
    
    @BeforeEach
    void setUp() {
        tenantId = 1L;
    }
    
    @Test
    void getAuthPolicy_Success() {
        // Given
        AuthPolicy policy = AuthPolicy.builder()
                .authPolicyId(1L)
                .tenantId(tenantId)
                .defaultLoginType("LOCAL")
                .allowedLoginTypes("LOCAL")
                .localLoginEnabled(true)
                .ssoLoginEnabled(false)
                .requireMfa(false)
                .build();
        
        when(authPolicyRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(policy));
        doNothing().when(codeResolver).require(eq("LOGIN_TYPE"), any());
        
        // When
        AuthPolicyResponse response = authPolicyService.getAuthPolicy(tenantId);
        
        // Then
        assertNotNull(response);
        assertEquals(tenantId, response.getTenantId());
        assertEquals("LOCAL", response.getDefaultLoginType());
        assertEquals(1, response.getAllowedLoginTypes().size());
        assertEquals("LOCAL", response.getAllowedLoginTypes().get(0));
        assertTrue(response.getLocalLoginEnabled());
        assertFalse(response.getSsoLoginEnabled());
    }
    
    @Test
    void getAuthPolicy_DefaultPolicy_WhenNotFound() {
        // Given
        when(authPolicyRepository.findByTenantId(tenantId))
                .thenReturn(Optional.empty());
        doNothing().when(codeResolver).require(eq("LOGIN_TYPE"), any());
        
        // When
        AuthPolicyResponse response = authPolicyService.getAuthPolicy(tenantId);
        
        // Then
        assertNotNull(response);
        assertEquals(tenantId, response.getTenantId());
        assertEquals("LOCAL", response.getDefaultLoginType());
        assertEquals(1, response.getAllowedLoginTypes().size());
        assertTrue(response.getLocalLoginEnabled());
        assertFalse(response.getSsoLoginEnabled());
    }
    
    @Test
    void getAuthPolicy_MultipleLoginTypes() {
        // Given
        AuthPolicy policy = AuthPolicy.builder()
                .authPolicyId(1L)
                .tenantId(tenantId)
                .defaultLoginType("LOCAL")
                .allowedLoginTypes("LOCAL,SSO")
                .localLoginEnabled(true)
                .ssoLoginEnabled(true)
                .ssoProviderKey("AZURE_AD")
                .requireMfa(false)
                .build();
        
        when(authPolicyRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(policy));
        doNothing().when(codeResolver).require(eq("LOGIN_TYPE"), any());
        
        // When
        AuthPolicyResponse response = authPolicyService.getAuthPolicy(tenantId);
        
        // Then
        assertNotNull(response);
        assertEquals(2, response.getAllowedLoginTypes().size());
        assertTrue(response.getAllowedLoginTypes().contains("LOCAL"));
        assertTrue(response.getAllowedLoginTypes().contains("SSO"));
        assertEquals("AZURE_AD", response.getSsoProviderKey());
    }
    
    @Test
    void getAuthPolicy_InvalidLoginType_ThrowsException() {
        // Given
        AuthPolicy policy = AuthPolicy.builder()
                .authPolicyId(1L)
                .tenantId(tenantId)
                .defaultLoginType("INVALID")
                .allowedLoginTypes("LOCAL")
                .build();
        
        when(authPolicyRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(policy));
        doThrow(new BaseException(ErrorCode.INVALID_CODE, "Invalid code"))
                .when(codeResolver).require(eq("LOGIN_TYPE"), eq("INVALID"));
        
        // When & Then
        assertThrows(BaseException.class, () -> {
            authPolicyService.getAuthPolicy(tenantId);
        });
    }
}
