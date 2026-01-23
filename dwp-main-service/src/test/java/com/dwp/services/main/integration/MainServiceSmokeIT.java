package com.dwp.services.main.integration;

import com.dwp.services.main.testcontainers.TestcontainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * main-service Smoke 통합 테스트 (C26)
 * <p>
 * 목적: AgentTask/HITL 핵심 API가 정상 작동하는지 최소 확인
 * - ApiResponse envelope 준수
 * - Flyway 마이그레이션 정상 적용
 * - 기능 테스트가 아닌 "서비스 기동 + 계약 유지" 검증
 * </p>
 */
@AutoConfigureMockMvc
@DisplayName("main-service Smoke Integration Test")
class MainServiceSmokeIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("[C26] POST /main/agent/tasks - AgentTask 생성 API 존재 확인")
    void testCreateAgentTaskEndpointExists() throws Exception {
        String requestBody = """
                {
                    "userId": "test-user",
                    "tenantId": "1",
                    "taskType": "data_analysis",
                    "description": "Test task",
                    "inputData": "{\\"test\\": true}"
                }
                """;

        mockMvc.perform(post("/main/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").isBoolean())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.taskId").exists())
                .andExpect(jsonPath("$.data.status").exists());
    }

    @Test
    @DisplayName("[C26] GET /main/agent/tasks - AgentTask 목록 조회 API 존재 확인")
    void testListAgentTasksEndpointExists() throws Exception {
        mockMvc.perform(get("/main/agent/tasks")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").isBoolean())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @DisplayName("[C26] GET /actuator/health - Health 엔드포인트 확인 (C33)")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("[C26] GET /actuator/health/readiness - Readiness 확인 (C33)")
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

    @Test
    @DisplayName("[C26] GET /main/health - Main service health check")
    void testMainServiceHealthCheck() throws Exception {
        mockMvc.perform(get("/main/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").exists());
    }
}
