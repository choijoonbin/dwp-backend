package com.dwp.services.synapsex.controller;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto.DocumentLinksDto;
import com.dwp.services.synapsex.dto.entity.Entity360Dto;
import com.dwp.services.synapsex.dto.entity.EntityListRowDto;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemDetailDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.service.document.DocumentQueryService;
import com.dwp.services.synapsex.service.entity.EntityQueryService;
import com.dwp.services.synapsex.service.lineage.LineageQueryService;
import com.dwp.services.synapsex.service.openitem.OpenItemQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 Read API 통합 테스트
 * - tenant isolation (X-Tenant-ID 전달 검증)
 * - pagination 구조 검증
 * - key routes 응답 구조 검증
 */
@WebMvcTest(controllers = {
        DocumentController.class,
        OpenItemController.class,
        EntityController.class,
        LineageController.class
})
@Import(GlobalExceptionHandler.class)
class Phase1ReadApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentQueryService documentQueryService;

    @MockBean
    private OpenItemQueryService openItemQueryService;

    @MockBean
    private EntityQueryService entityQueryService;

    @MockBean
    private LineageQueryService lineageQueryService;

    private static final Long TENANT_ID = 1L;

    @Nested
    @DisplayName("A) Documents API")
    class DocumentsApiTest {

        @Test
        @DisplayName("GET /documents - X-Tenant-ID 필수, pagination 구조 반환")
        void getDocuments_requiresTenant_returnsPageResponse() throws Exception {
            var row = DocumentListRowDto.builder()
                    .bukrs("1000")
                    .belnr("1900000001")
                    .gjahr("2024")
                    .links(DocumentLinksDto.builder().docKey("1000-1900000001-2024").build())
                    .build();
            var pageResponse = PageResponse.of(List.of(row), 1L, 0, 20);

            when(documentQueryService.findDocuments(eq(TENANT_ID), any())).thenReturn(pageResponse);

            mockMvc.perform(get("/synapse/documents")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items[0].links.docKey").value("1000-1900000001-2024"))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.pageInfo.page").value(0))
                    .andExpect(jsonPath("$.data.pageInfo.size").value(20))
                    .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));

            verify(documentQueryService).findDocuments(eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("GET /documents - X-Tenant-ID 없으면 에러 (필수 헤더)")
        void getDocuments_withoutTenant_returnsError() throws Exception {
            mockMvc.perform(get("/synapse/documents"))
                    .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                            result.getResponse().getStatus() >= 400,
                            "Missing X-Tenant-ID should return error status, got " + result.getResponse().getStatus()));
        }

        @Test
        @DisplayName("GET /documents/{bukrs}/{belnr}/{gjahr} - 상세 조회")
        void getDocumentDetail_returnsDetail() throws Exception {
            var detail = DocumentDetailDto.builder()
                    .header(DocumentDetailDto.DocumentHeaderDto.builder()
                            .bukrs("1000")
                            .belnr("1900000001")
                            .gjahr("2024")
                            .build())
                    .build();

            when(documentQueryService.findDocumentDetail(eq(TENANT_ID), eq("1000"), eq("1900000001"), eq("2024")))
                    .thenReturn(Optional.of(detail));

            mockMvc.perform(get("/synapse/documents/1000/1900000001/2024")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.header.bukrs").value("1000"))
                    .andExpect(jsonPath("$.data.header.belnr").value("1900000001"));

            verify(documentQueryService).findDocumentDetail(eq(TENANT_ID), eq("1000"), eq("1900000001"), eq("2024"));
        }
    }

    @Nested
    @DisplayName("B) Open Items API")
    class OpenItemsApiTest {

        @Test
        @DisplayName("GET /open-items - pagination 구조")
        void getOpenItems_returnsPageResponse() throws Exception {
            var row = OpenItemListRowDto.builder()
                    .bukrs("1000")
                    .belnr("1900000001")
                    .gjahr("2024")
                    .buzei("001")
                    .openItemKey("1000-1900000001-2024-001")
                    .amount(BigDecimal.TEN)
                    .build();
            var pageResponse = PageResponse.of(List.of(row), 1L, 0, 20);

            when(openItemQueryService.findOpenItems(eq(TENANT_ID), any())).thenReturn(pageResponse);

            mockMvc.perform(get("/synapse/open-items")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].docLinkKey").value("1000-1900000001-2024"))
                    .andExpect(jsonPath("$.data.pageInfo").exists());

            verify(openItemQueryService).findOpenItems(eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("GET /open-items/{bukrs}/{belnr}/{gjahr}/{buzei} - 상세 조회")
        void getOpenItemDetail_returnsDetail() throws Exception {
            var detail = OpenItemDetailDto.builder()
                    .bukrs("1000")
                    .belnr("1900000001")
                    .gjahr("2024")
                    .buzei("001")
                    .docHeaderSummary(OpenItemDetailDto.DocHeaderSummaryDto.builder()
                            .bukrs("1000")
                            .belnr("1900000001")
                            .gjahr("2024")
                            .xblnr("REF001")
                            .build())
                    .build();

            when(openItemQueryService.findOpenItemDetail(eq(TENANT_ID), eq("1000"), eq("1900000001"), eq("2024"), eq("001")))
                    .thenReturn(Optional.of(detail));

            mockMvc.perform(get("/synapse/open-items/1000/1900000001/2024/001")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bukrs").value("1000"))
                    .andExpect(jsonPath("$.data.docHeaderSummary.xblnr").value("REF001"));
        }
    }

    @Nested
    @DisplayName("C) Entities API")
    class EntitiesApiTest {

        @Test
        @DisplayName("GET /entities - pagination 구조")
        void getEntities_returnsPageResponse() throws Exception {
            var row = EntityListRowDto.builder()
                    .partyId(100L)
                    .type("VENDOR")
                    .name("Test Vendor")
                    .riskScore(0.5)
                    .openItemsCount(2)
                    .build();
            var pageResponse = PageResponse.of(List.of(row), 1L, 0, 20);

            when(entityQueryService.findEntities(eq(TENANT_ID), any())).thenReturn(pageResponse);

            mockMvc.perform(get("/synapse/entities")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].partyId").value(100))
                    .andExpect(jsonPath("$.data.items[0].name").value("Test Vendor"))
                    .andExpect(jsonPath("$.data.pageInfo").exists());

            verify(entityQueryService).findEntities(eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("GET /entities/{partyId} - Entity360 상세")
        void getEntity360_returnsDetail() throws Exception {
            var dto = Entity360Dto.builder()
                    .base(Entity360Dto.EntityBaseDto.builder()
                            .partyId(100L)
                            .partyCode("V001")
                            .nameDisplay("Test Vendor")
                            .build())
                    .build();

            when(entityQueryService.findEntity360(eq(TENANT_ID), eq(100L))).thenReturn(Optional.of(dto));

            mockMvc.perform(get("/synapse/entities/100")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.base.partyId").value(100))
                    .andExpect(jsonPath("$.data.base.partyCode").value("V001"));
        }
    }

    @Nested
    @DisplayName("D) Lineage API")
    class LineageApiTest {

        @Test
        @DisplayName("GET /lineage?caseId=1 - journey 반환")
        void getLineage_withCaseId_returnsJourney() throws Exception {
            var response = LineageResponseDto.builder()
                    .journeyNodes(List.of("SAP Raw Event", "Case Created"))
                    .timestamps(java.util.Map.of("Case Created", Instant.now().toString()))
                    .build();

            when(lineageQueryService.findLineage(eq(TENANT_ID), any())).thenReturn(response);

            mockMvc.perform(get("/synapse/lineage")
                            .param("caseId", "1")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.journeyNodes").isArray())
                    .andExpect(jsonPath("$.data.timestamps").exists());

            verify(lineageQueryService).findLineage(eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("GET /lineage - 파라미터 없으면 400")
        void getLineage_withoutParams_returns400() throws Exception {
            when(lineageQueryService.findLineage(eq(TENANT_ID), any()))
                    .thenThrow(new IllegalArgumentException("최소 1개의 쿼리 파라미터가 필요합니다: caseId, docKey, rawEventId, partyId"));

            mockMvc.perform(get("/synapse/lineage")
                            .header("X-Tenant-ID", TENANT_ID.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("ERROR"));
        }
    }
}
