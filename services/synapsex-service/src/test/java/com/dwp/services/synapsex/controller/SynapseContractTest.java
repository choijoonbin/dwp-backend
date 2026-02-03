package com.dwp.services.synapsex.controller;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.synapsex.dto.action.ActionListRowDto;
import com.dwp.services.synapsex.dto.archive.ArchiveListRowDto;
import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.case_.CaseListRowDto;
import com.dwp.services.synapsex.dto.anomaly.AnomalyListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.optimization.OptimizationArApDto;
import com.dwp.services.synapsex.service.action.ActionCommandService;
import com.dwp.services.synapsex.service.action.ActionQueryService;
import com.dwp.services.synapsex.service.anomaly.AnomalyQueryService;
import com.dwp.services.synapsex.service.archive.ArchiveQueryService;
import com.dwp.services.synapsex.service.case_.CaseCommandService;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.optimization.OptimizationQueryService;
import com.dwp.services.synapsex.service.scope.ScopeEnforcementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Synapse 전 메뉴 Contract Test.
 * - status code / ApiResponse 래퍼 / pagination schema / 필수 필드 검증
 * - X-Tenant-ID 누락 시 400 정책 검증
 */
@WebMvcTest(controllers = {
        CaseController.class,
        AnomalyController.class,
        OptimizationController.class,
        ActionController.class,
        ArchiveController.class
})
@Import(GlobalExceptionHandler.class)
class SynapseContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaseQueryService caseQueryService;
    @MockBean
    private CaseCommandService caseCommandService;
    @MockBean
    private AnomalyQueryService anomalyQueryService;
    @MockBean
    private OptimizationQueryService optimizationQueryService;
    @MockBean
    private ActionQueryService actionQueryService;
    @MockBean
    private ActionCommandService actionCommandService;
    @MockBean
    private ArchiveQueryService archiveQueryService;
    @MockBean
    private AuditWriter auditWriter;
    @MockBean
    private ScopeEnforcementService scopeEnforcementService;

    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        when(scopeEnforcementService.resolveCompanyFilter(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Nested
    @DisplayName("Cases API - Contract")
    class CasesContractTest {

        @Test
        @DisplayName("GET /synapse/cases - items/total/pageInfo 스키마, ApiResponse 래퍼")
        void getCases_returnsPageResponseSchema() throws Exception {
            var row = CaseListRowDto.builder()
                    .caseId(1L)
                    .status("OPEN")
                    .severity("HIGH")
                    .build();
            var page = PageResponse.of(List.of(row), 1L, 0, 20);

            when(caseQueryService.findCases(eq(TENANT_ID), any())).thenReturn(page);

            mockMvc.perform(get("/synapse/cases").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.pageInfo.page").value(1))
                    .andExpect(jsonPath("$.data.pageInfo.size").value(20))
                    .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));
        }

        @Test
        @DisplayName("GET /synapse/cases - X-Tenant-ID 없으면 400")
        void getCases_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/cases"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("ERROR"));
        }

        @Test
        @DisplayName("GET /synapse/cases/{id} - 상세 조회")
        void getCaseDetail_returnsDetail() throws Exception {
            var detail = CaseDetailDto.builder().caseId(1L).status("OPEN").build();
            when(caseQueryService.findCaseDetail(eq(TENANT_ID), eq(1L))).thenReturn(Optional.of(detail));

            mockMvc.perform(get("/synapse/cases/1").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.caseId").value(1))
                    .andExpect(jsonPath("$.data.status").value("OPEN"));
        }
    }

    @Nested
    @DisplayName("Anomalies API - Contract")
    class AnomaliesContractTest {

        @Test
        @DisplayName("GET /synapse/anomalies - items/total/pageInfo 스키마")
        void getAnomalies_returnsPageResponseSchema() throws Exception {
            var row = AnomalyListRowDto.builder().anomalyId(1L).severity("HIGH").build();
            var page = PageResponse.of(List.of(row), 1L, 0, 20);

            when(anomalyQueryService.findAnomalies(eq(TENANT_ID), any())).thenReturn(page);

            mockMvc.perform(get("/synapse/anomalies").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.pageInfo").exists());
        }

        @Test
        @DisplayName("GET /synapse/anomalies - X-Tenant-ID 없으면 400")
        void getAnomalies_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/anomalies"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Optimization API - Contract")
    class OptimizationContractTest {

        @Test
        @DisplayName("GET /synapse/optimization/ar - ApiResponse 래퍼")
        void getArOptimization_returnsApiResponse() throws Exception {
            var dto = OptimizationArApDto.builder()
                    .type("AR")
                    .buckets(List.of())
                    .overdueSummary(null)
                    .alertRecommendations(List.of())
                    .build();
            when(optimizationQueryService.getArOptimization(eq(TENANT_ID))).thenReturn(dto);

            mockMvc.perform(get("/synapse/optimization/ar").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.buckets").isArray());
        }

        @Test
        @DisplayName("GET /synapse/optimization/ar - X-Tenant-ID 없으면 400")
        void getArOptimization_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/optimization/ar"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Actions API - Contract")
    class ActionsContractTest {

        @Test
        @DisplayName("GET /synapse/actions - items/total/pageInfo 스키마")
        void getActions_returnsPageResponseSchema() throws Exception {
            var row = ActionListRowDto.builder()
                    .actionId(1L)
                    .caseId(1L)
                    .status("PLANNED")
                    .createdAt(Instant.now())
                    .build();
            var page = PageResponse.of(List.of(row), 1L, 0, 20);

            when(actionQueryService.findActions(eq(TENANT_ID), any())).thenReturn(page);

            mockMvc.perform(get("/synapse/actions").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.pageInfo").exists());
        }

        @Test
        @DisplayName("GET /synapse/actions - X-Tenant-ID 없으면 400")
        void getActions_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/actions"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Archive API - Contract")
    class ArchiveContractTest {

        @Test
        @DisplayName("GET /synapse/archive - items/total/pageInfo 스키마")
        void getArchive_returnsPageResponseSchema() throws Exception {
            var row = ArchiveListRowDto.builder()
                    .actionId(1L)
                    .caseId(1L)
                    .outcome("SUCCESS")
                    .build();
            var page = PageResponse.of(List.of(row), 1L, 0, 20);

            when(archiveQueryService.findArchivedActions(eq(TENANT_ID), any())).thenReturn(page);

            mockMvc.perform(get("/synapse/archive").header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.pageInfo").exists());
        }

        @Test
        @DisplayName("GET /synapse/archive - X-Tenant-ID 없으면 400")
        void getArchive_withoutTenant_returns400() throws Exception {
            mockMvc.perform(get("/synapse/archive"))
                    .andExpect(status().isBadRequest());
        }
    }
}
