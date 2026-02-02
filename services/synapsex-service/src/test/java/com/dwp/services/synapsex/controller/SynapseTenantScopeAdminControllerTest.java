package com.dwp.services.synapsex.controller;

import com.dwp.services.synapsex.dto.admin.*;
import com.dwp.services.synapsex.service.admin.TenantScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SynapseTenantScopeAdminController 테스트
 */
@WebMvcTest(SynapseTenantScopeAdminController.class)
class SynapseTenantScopeAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantScopeService tenantScopeService;

    @Autowired
    private ObjectMapper objectMapper;

    private TenantScopeResponseDto sampleResponse() {
        return TenantScopeResponseDto.builder()
                .companyCodes(List.of(
                        TenantScopeResponseDto.CompanyCodeDto.builder().bukrs("1000").enabled(true).source("SEED").build()))
                .currencies(List.of(
                        TenantScopeResponseDto.CurrencyDto.builder().waers("KRW").enabled(true).fxControlMode("ALLOW").build()))
                .sodRules(List.of(
                        TenantScopeResponseDto.SodRuleDto.builder()
                                .ruleKey("NO_SELF_APPROVE")
                                .title("Requester cannot approve own action")
                                .enabled(true)
                                .severity("WARN")
                                .appliesTo(List.of())
                                .build()))
                .meta(TenantScopeResponseDto.TenantScopeMetaDto.builder()
                        .tenantId(1L)
                        .lastUpdatedAt(Instant.now())
                        .seeded(true)
                        .build())
                .build();
    }

    @Test
    @DisplayName("GET tenant-scope - 시드 후 응답")
    void getTenantScope_SeedsAndReturns() throws Exception {
        when(tenantScopeService.getTenantScope(1L)).thenReturn(sampleResponse());

        mockMvc.perform(get("/synapse/admin/tenant-scope")
                        .header("X-Tenant-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.companyCodes[0].bukrs").value("1000"))
                .andExpect(jsonPath("$.data.currencies[0].waers").value("KRW"))
                .andExpect(jsonPath("$.data.sodRules[0].ruleKey").value("NO_SELF_APPROVE"))
                .andExpect(jsonPath("$.data.meta.seeded").value(true));
    }

    @Test
    @DisplayName("PATCH company-code - 토글 저장")
    void patchCompanyCode_PersistsAndReturns() throws Exception {
        when(tenantScopeService.toggleCompanyCode(eq(1L), anyLong(), eq("1000"), eq(false), any(), any(), any()))
                .thenReturn(sampleResponse());

        mockMvc.perform(patch("/synapse/admin/tenant-scope/company-codes/1000")
                        .header("X-Tenant-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("POST company-codes/bulk - Bulk 업데이트")
    void bulkUpdateCompanyCodes_WritesAudit() throws Exception {
        when(tenantScopeService.bulkUpdateCompanyCodes(eq(1L), anyLong(), any(BulkUpdateCompanyCodesRequest.class), any(), any(), any()))
                .thenReturn(sampleResponse());

        BulkUpdateCompanyCodesRequest req = BulkUpdateCompanyCodesRequest.builder()
                .items(List.of(
                        BulkUpdateCompanyCodesRequest.CompanyCodeItemDto.builder().bukrs("1000").enabled(true).build(),
                        BulkUpdateCompanyCodesRequest.CompanyCodeItemDto.builder().bukrs("2000").enabled(false).build()))
                .build();

        mockMvc.perform(post("/synapse/admin/tenant-scope/company-codes/bulk")
                        .header("X-Tenant-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
