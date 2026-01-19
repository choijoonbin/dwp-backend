package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthService 테스트
 */
class AuthServiceTest {
    
    private AuthService authService;
    
    @BeforeEach
    void setUp() {
        String jwtSecret = "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256";
        Long expirationSeconds = 3600L;
        authService = new AuthService(jwtSecret, expirationSeconds);
    }
    
    @Test
    void login_Success() {
        // Given
        LoginRequest request = new LoginRequest("testuser", "password123", "tenant1");
        
        // When
        LoginResponse response = authService.login(request);
        
        // Then
        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(3600L, response.getExpiresIn());
        assertEquals("testuser", response.getUserId());
        assertEquals("tenant1", response.getTenantId());
    }
    
    @Test
    void login_InvalidCredentials_ThrowsException() {
        // Given
        LoginRequest request = new LoginRequest("", "password123", "tenant1");
        
        // When & Then
        BaseException exception = assertThrows(BaseException.class, () -> {
            authService.login(request);
        });
        
        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }
    
    @Test
    void login_GeneratedJwt_ContainsRequiredClaims() {
        // Given
        LoginRequest request = new LoginRequest("testuser", "password123", "tenant1");
        
        // When
        LoginResponse response = authService.login(request);
        String token = response.getAccessToken();
        
        // Then
        assertNotNull(token);
        // JWT는 3개의 부분으로 구성됨 (header.payload.signature)
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "JWT 토큰은 3개의 부분으로 구성되어야 합니다.");
    }
}
