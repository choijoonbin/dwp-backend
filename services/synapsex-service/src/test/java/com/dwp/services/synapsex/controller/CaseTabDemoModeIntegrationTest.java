package com.dwp.services.synapsex.controller;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.case_.CaseTabProxyService;
import com.dwp.services.synapsex.service.scope.ScopeEnforcementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P1.1: Case 탭 DEMO 모드 Non-Empty 샘플 응답 계약 검증
 * - Mock이 DEMO ON 시 반환하는 샘플 구조 검증
 * - @ActiveProfiles("test"): SynapseAdminGuardFilter 비활성화
 */
@WebMvcTest(controllers = CaseController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class CaseTabDemoModeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaseTabProxyService caseTabProxyService;
    @MockBean
    private com.dwp.services.synapsex.service.case_.CaseQueryService caseQueryService;
    @MockBean
    private com.dwp.services.synapsex.service.case_.CaseCommandService caseCommandService;
    @MockBean
    private AuditWriter auditWriter;
    @MockBean
    private ScopeEnforcementService scopeEnforcementService;

    private static final Long TENANT_ID = 1L;
    private static final Long CASE_ID = 85116L;

    @BeforeEach
    void setUp() {
        when(scopeEnforcementService.resolveCompanyFilter(any(), any(), any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("DEMO ON - Non-Empty 샘플 계약")
    class DemoModeOnContractTest {

        @Test
        @DisplayName("GET /analysis - summary, keyFindings, recommendations")
        void getAnalysis_returnsNonEmpty() throws Exception {
            when(caseTabProxyService.getAnalysis(eq(TENANT_ID), eq(CASE_ID), any(), any()))
                    .thenReturn(Map.of(
                            "summary", "정책 위반 가능성이 있는 전표 조합입니다.",
                            "keyFindings", List.of("중복 지급 의심", "벤더 계좌 변경 직후 지급"),
                            "recommendations", List.of(
                                    Map.of("action", "HOLD_PAYMENT", "reason", "계좌 변경 72시간 룰 위반 가능"),
                                    Map.of("action", "REQUEST_DUAL_APPROVAL", "reason", "고액 지급 승인 필요")
                            )
                    ));

            mockMvc.perform(get("/synapse/cases/" + CASE_ID + "/analysis").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summary").isNotEmpty())
                    .andExpect(jsonPath("$.data.keyFindings.length()").value(2))
                    .andExpect(jsonPath("$.data.recommendations.length()").value(2));
        }

        @Test
        @DisplayName("GET /confidence - score, factors 3개")
        void getConfidence_returnsNonEmpty() throws Exception {
            when(caseTabProxyService.getConfidence(eq(TENANT_ID), eq(CASE_ID), any(), any()))
                    .thenReturn(Map.of(
                            "score", 72,
                            "severity", "MEDIUM",
                            "factors", List.of(
                                    Map.of("label", "VendorAge", "weight", 0.3, "reason", "신규 거래처(7일 이내)"),
                                    Map.of("label", "AmountSpike", "weight", 0.4, "reason", "평균 대비 3.2배"),
                                    Map.of("label", "BankChanged", "weight", 0.3, "reason", "계좌 변경 이력 존재")
                            )
                    ));

            mockMvc.perform(get("/synapse/cases/" + CASE_ID + "/confidence").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.score").value(72))
                    .andExpect(jsonPath("$.data.factors.length()").value(3));
        }

        @Test
        @DisplayName("GET /similar - items 2개")
        void getSimilar_returnsNonEmpty() throws Exception {
            when(caseTabProxyService.getSimilar(eq(TENANT_ID), eq(CASE_ID), any(), any()))
                    .thenReturn(Map.of("items", List.of(
                            Map.of("caseId", 99901, "score", 0.82, "title", "신규 거래처 고액 지급"),
                            Map.of("caseId", 99902, "score", 0.76, "title", "계좌 변경 직후 지급")
                    )));

            mockMvc.perform(get("/synapse/cases/" + CASE_ID + "/similar").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(2));
        }

        @Test
        @DisplayName("GET /rag/evidence - items 2개")
        void getRagEvidence_returnsNonEmpty() throws Exception {
            when(caseTabProxyService.getRagEvidence(eq(TENANT_ID), eq(CASE_ID), any(), any()))
                    .thenReturn(Map.of("items", List.of(
                            Map.of("title", "지급 통제 정책", "source", "PolicyHub", "relevance", 0.91),
                            Map.of("title", "승인 프로세스 가이드", "source", "PolicyHub", "relevance", 0.87)
                    )));

            mockMvc.perform(get("/synapse/cases/" + CASE_ID + "/rag/evidence").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(2));
        }
    }
}
