package com.dwp.services.auth.integration;

import com.dwp.services.auth.testcontainers.TestcontainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * auth-server Smoke 통합 테스트 (C25)
 * <p>
 * 목적: 핵심 API가 정상 작동하는지 최소 확인
 * - ApiResponse envelope 준수
 * - 멀티테넌시 헤더 정책 확인
 * - 기능 테스트가 아닌 "서비스 기동 + 계약 유지" 검증
 * </p>
 */
@AutoConfigureMockMvc
@DisplayName("auth-server Smoke Integration Test")
class AuthSmokeIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("[C25] GET /api/auth/policy - ApiResponse 응답 확인")
    void testPolicyEndpointReturnsApiResponse() throws Exception {
        mockMvc.perform(get("/api/auth/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").isBoolean())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("[C25] GET /api/auth/menus/tree - ApiResponse 응답 확인 (인증 없이)")
    void testMenusTreeEndpointWithoutAuth() throws Exception {
        // 인증 없이 호출 시 401 또는 기본 메뉴 반환 (정책에 따라)
        mockMvc.perform(get("/api/auth/menus/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").isBoolean())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("[C25] GET /actuator/health - Health 엔드포인트 확인 (C33)")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("[C25] GET /actuator/health/readiness - Readiness 확인 (C33)")
    void testReadinessEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("[C30] GET /v3/api-docs - OpenAPI 문서 생성 확인")
    void testOpenApiDocsGenerated() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.0.1"))
                .andExpect(jsonPath("$.info.title").exists());
    }
}
