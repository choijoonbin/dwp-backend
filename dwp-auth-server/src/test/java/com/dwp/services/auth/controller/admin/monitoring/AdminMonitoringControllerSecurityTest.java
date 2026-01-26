package com.dwp.services.auth.controller.admin.monitoring;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.auth.config.AdminEndpointPolicyRegistry;
import com.dwp.services.auth.dto.MonitoringSummaryResponse;
import com.dwp.services.auth.service.MonitoringService;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.service.monitoring.AdminMonitoringService;
import com.dwp.services.auth.service.rbac.AdminGuardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import com.dwp.services.auth.controller.admin.monitoring.WithMockJwt;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PR-01D: AdminMonitoringController 보안 테스트
 * 
 * 검증 항목:
 * 1. 토큰 없음 → 401 (Spring Security에서 처리)
 * 2. admin 아닌 토큰 → 403 (AdminGuardInterceptor에서 처리)
 * 3. admin 토큰 + tenant 일치 → 200
 * 
 * Note: 실제 JWT 검증은 Spring Security Filter Chain에서 처리되므로,
 * 여기서는 Interceptor 레벨의 권한 검증을 테스트합니다.
 */
@WebMvcTest(value = AdminMonitoringController.class, excludeAutoConfiguration = RedisAutoConfiguration.class)
@ContextConfiguration(classes = com.dwp.services.auth.SliceTestApplication.class)
@Import(GlobalExceptionHandler.class)
@SuppressWarnings({"null", "removal"})
class AdminMonitoringControllerSecurityTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private MonitoringService monitoringService;
    
    @MockBean
    private AdminMonitoringService adminMonitoringService;
    
    @MockBean
    private AdminGuardService adminGuardService;
    
    @MockBean
    private AdminEndpointPolicyRegistry endpointPolicyRegistry;
    
    @MockBean
    private AuditLogService auditLogService;
    
    @Test
    @DisplayName("PR-01D-1: 토큰 없음 → 401")
    void testNoToken_Returns401() throws Exception {
        // Given: 토큰 없음 (인증되지 않은 요청)
        // When & Then: 401 Unauthorized (Spring Security에서 처리)
        mockMvc.perform(get("/admin/monitoring/summary")
                        .header("X-Tenant-ID", "1"))
                .andExpect(status().isUnauthorized());
    }
    
    @Test
    @DisplayName("PR-01D-2: admin 아닌 토큰 → 403")
    @WithMockJwt(userId = 100L, tenantId = 1L)  // 일반 사용자
    void testNonAdminToken_Returns403() throws Exception {
        // Given: 일반 사용자 (admin 아님)
        // AdminGuardService가 requireAdmin()에서 예외 발생하도록 설정
        doThrow(new com.dwp.core.exception.BaseException(ErrorCode.ADMIN_FORBIDDEN, "관리자 권한이 필요합니다."))
                .when(adminGuardService).requireAdmin(eq(1L), eq(100L));
        
        // When & Then: 403 Forbidden
        mockMvc.perform(get("/admin/monitoring/summary")
                        .header("X-Tenant-ID", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.ADMIN_FORBIDDEN.getCode()));
    }
    
    @Test
    @DisplayName("PR-01D-3: admin 토큰 + tenant 일치 → 200")
    @WithMockJwt(userId = 1L, tenantId = 1L)  // Admin 사용자
    void testAdminTokenWithMatchingTenant_Returns200() throws Exception {
        // Given: Admin 사용자 + tenant 일치
        doNothing().when(adminGuardService).requireAdmin(eq(1L), eq(1L));
        
        // MonitoringService Mock 설정
        MonitoringSummaryResponse response = MonitoringSummaryResponse.builder()
                .pv(1000L)
                .uv(500L)
                .events(200L)
                .apiErrorRate(0.01)
                .pvDeltaPercent(5.0)
                .uvDeltaPercent(3.0)
                .eventDeltaPercent(2.0)
                .apiErrorDeltaPercent(-1.0)
                .build();
        
        when(monitoringService.getSummary(eq(1L), any(), any(), any(), any()))
                .thenReturn(response);
        
        // When & Then: 200 OK
        mockMvc.perform(get("/admin/monitoring/summary")
                        .header("X-Tenant-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pv").value(1000))
                .andExpect(jsonPath("$.data.uv").value(500))
                .andExpect(jsonPath("$.data.events").value(200));
    }
    
    @Test
    @DisplayName("PR-01D-4: admin 토큰 + tenant 불일치 → 403")
    @WithMockJwt(userId = 1L, tenantId = 1L)  // JWT tenant=1, 헤더 X-Tenant-ID=2 로 불일치
    void testAdminTokenWithMismatchedTenant_Returns403() throws Exception {
        // Given: Admin 사용자 + tenant 불일치
        // JWT의 tenant_id는 1L, 헤더의 X-Tenant-ID는 2L
        // AdminGuardInterceptor에서 TENANT_MISMATCH 예외 발생
        
        // When & Then: 403 Forbidden (TENANT_MISMATCH)
        // Note: 실제로는 JWT의 tenant_id와 헤더의 X-Tenant-ID를 비교하지만,
        // @WithMockUser는 JWT를 생성하지 않으므로 Interceptor에서 처리되지 않습니다.
        // 이 테스트는 Interceptor의 tenant 검증 로직이 정상 동작함을 보장하기 위한 것입니다.
        mockMvc.perform(get("/admin/monitoring/summary")
                        .header("X-Tenant-ID", "2"))  // tenant 불일치
                .andExpect(status().isForbidden());
    }
}
