package com.dwp.services.auth.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.config.AdminEndpointPolicyRegistry.RequiredPermission;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.service.rbac.AdminGuardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * AdminGuardInterceptor 테스트 (BE P1-5 Enhanced)
 * 
 * 검증 항목:
 * - ADMIN 아닌 유저로 /api/admin/** 접근 시 403
 * - ADMIN 유저는 정상 통과
 * - /api/admin/** 경로가 아닌 경우 통과
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminGuardInterceptor 테스트")
@SuppressWarnings("null")
class AdminGuardInterceptorTest {

    @Mock
    private AdminGuardService adminGuardService;
    
    @Mock
    private AdminEndpointPolicyRegistry endpointPolicyRegistry;
    
    @Mock
    private AuditLogService auditLogService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Object handler;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    @Mock
    private SecurityContext securityContext;

    @InjectMocks
    private AdminGuardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("ADMIN 아닌 유저로 /api/admin/** 접근 시 403")
    void testNonAdminUserAccessDenied() throws Exception {
        // Given: ADMIN 아닌 유저
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getHeader("X-Tenant-ID")).thenReturn(null);  // 헤더 없음
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("100");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);
        
        doThrow(new BaseException(ErrorCode.FORBIDDEN, "관리자 권한이 필요합니다."))
                .when(adminGuardService).requireAdminRole(1L, 100L);

        // When/Then: 403 예외 발생
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("ADMIN 유저는 정상 통과")
    void testAdminUserAccessAllowed() throws Exception {
        // Given: ADMIN 유저
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getHeader("X-Tenant-ID")).thenReturn("1");  // 헤더와 JWT 일치
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("1");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);
        doNothing().when(adminGuardService).requireAdminRole(1L, 1L);

        // When
        boolean result = interceptor.preHandle(request, response, handler);

        // Then: 정상 통과
        assertThat(result).isTrue();
        verify(adminGuardService).requireAdminRole(1L, 1L);
    }

    @Test
    @DisplayName("/api/admin/** 경로가 아닌 경우 통과")
    void testNonAdminPathPassesThrough() throws Exception {
        // Given: /api/admin/** 경로가 아님
        when(request.getRequestURI()).thenReturn("/api/auth/me");

        // When
        boolean result = interceptor.preHandle(request, response, handler);

        // Then: 통과 (검증 안 함)
        assertThat(result).isTrue();
        verify(adminGuardService, never()).requireAdminRole(any(), any());
    }

    @Test
    @DisplayName("/admin/** 경로도 체크")
    void testAdminPathWithoutApiPrefix() throws Exception {
        // Given: /admin/** 경로
        when(request.getRequestURI()).thenReturn("/admin/users");
        when(request.getHeader("X-Tenant-ID")).thenReturn("1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("1");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);
        doNothing().when(adminGuardService).requireAdminRole(1L, 1L);

        // When
        boolean result = interceptor.preHandle(request, response, handler);

        // Then: 검증 수행
        assertThat(result).isTrue();
        verify(adminGuardService).requireAdminRole(1L, 1L);
    }

    @Test
    @DisplayName("인증 정보 없으면 AUTH_REQUIRED (401)")
    void testUnauthenticatedAccessDenied() {
        // Given: 인증 정보 없음
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(securityContext.getAuthentication()).thenReturn(null);

        // When/Then: AUTH_REQUIRED 예외 발생
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_REQUIRED);
    }

    @Test
    @DisplayName("tenant_id 없으면 TENANT_MISSING (400)")
    void testMissingTenantIdDenied() {
        // Given: tenant_id 없음
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("1");
        when(jwt.getClaim("tenant_id")).thenReturn(null);

        // When/Then: TENANT_MISSING 예외 발생
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TENANT_MISSING);
    }

    @Test
    @DisplayName("JWT subject가 유효하지 않으면 TOKEN_INVALID (401)")
    void testInvalidJwtSubject() {
        // Given: JWT subject가 숫자가 아님
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("invalid");

        // When/Then: TOKEN_INVALID 예외 발생
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("JWT tenant_id와 헤더 X-Tenant-ID 불일치 시 TENANT_MISMATCH (403)")
    void testTenantIdMismatch() throws Exception {
        // Given: JWT tenant_id와 헤더 tenant_id 불일치
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getHeader("X-Tenant-ID")).thenReturn("2");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("1");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);

        // When/Then: TENANT_MISMATCH 예외 발생
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TENANT_MISMATCH);
    }
    
    @Test
    @DisplayName("Policy enforced: 일반 사용자 접근 -> 403 (권한 없음)")
    void testPolicyEnforced_NonAdminUserAccessDenied() throws Exception {
        // Given: Policy 있음, 일반 사용자 (권한 없음)
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Tenant-ID")).thenReturn("1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("100");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);
        
        RequiredPermission policy = RequiredPermission.builder()
                .resourceKey("menu.admin.users")
                .permissionCode("VIEW")
                .build();
        when(endpointPolicyRegistry.findPolicies("GET", "/api/admin/users"))
                .thenReturn(Arrays.asList(policy));
        when(adminGuardService.canAccess(100L, 1L, "menu.admin.users", "VIEW"))
                .thenReturn(false);
        
        // When/Then: 403 예외 발생
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        
        // 감사로그 기록 확인 (Map 기반 오버로드)
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = any(Map.class);
        verify(auditLogService, times(1)).recordAuditLog(
                eq(1L), eq(100L), eq("RBAC_DENY"), eq("RBAC"), eq(null), metadataMap, eq(request));
    }
    
    @Test
    @DisplayName("Policy enforced: 권한 부여 후 -> 200")
    void testPolicyEnforced_WithPermissionAccessAllowed() throws Exception {
        // Given: Policy 있음, 권한 부여된 사용자
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Tenant-ID")).thenReturn("1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("200");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);
        
        RequiredPermission policy = RequiredPermission.builder()
                .resourceKey("menu.admin.users")
                .permissionCode("VIEW")
                .build();
        when(endpointPolicyRegistry.findPolicies("GET", "/api/admin/users"))
                .thenReturn(Arrays.asList(policy));
        when(adminGuardService.canAccess(200L, 1L, "menu.admin.users", "VIEW"))
                .thenReturn(true);
        
        // When
        boolean result = interceptor.preHandle(request, response, handler);
        
        // Then: 정상 통과
        assertThat(result).isTrue();
        verify(adminGuardService).canAccess(200L, 1L, "menu.admin.users", "VIEW");
        verify(auditLogService, never()).recordAuditLog(any(), any(), any(), any(), any(), any(), any(), any());
    }
    
    @Test
    @DisplayName("Policy 없는 endpoint -> RELAX 정책 확인 (admin만 통과)")
    void testPolicyNotFound_RelaxMode() throws Exception {
        // Given: Policy 없음, RELAX 모드
        when(request.getRequestURI()).thenReturn("/api/admin/unknown");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Tenant-ID")).thenReturn("1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("1");
        when(jwt.getClaim("tenant_id")).thenReturn(1L);
        
        when(endpointPolicyRegistry.findPolicies("GET", "/api/admin/unknown"))
                .thenReturn(Collections.emptyList());
        when(endpointPolicyRegistry.getMode())
                .thenReturn(AdminEndpointPolicyRegistry.PolicyMode.RELAX);
        doNothing().when(adminGuardService).requireAdminRole(1L, 1L);
        
        // When
        boolean result = interceptor.preHandle(request, response, handler);
        
        // Then: admin만 통과
        assertThat(result).isTrue();
        verify(adminGuardService).requireAdminRole(1L, 1L);
        verify(adminGuardService, never()).canAccess(any(), any(), any(), any());
        verify(auditLogService, never()).recordAuditLog(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
