package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.entity.Entity360Dto;
import com.dwp.services.synapsex.dto.entity.EntityChangeLogDto;
import com.dwp.services.synapsex.dto.entity.EntityListRowDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.dto.case_.CaseListRowDto;
import com.dwp.services.synapsex.service.document.DocumentQueryService;
import com.dwp.services.synapsex.service.entity.EntityQueryService;
import com.dwp.services.synapsex.service.openitem.OpenItemQueryService;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 1 Entities API (bp_party)
 * GET /api/synapse/entities, GET /api/synapse/entities/{partyId}, change-logs, documents, open-items, cases
 */
@RestController
@RequestMapping("/synapse/entities")
@RequiredArgsConstructor
public class EntityController {

    private final EntityQueryService entityQueryService;
    private final DocumentQueryService documentQueryService;
    private final OpenItemQueryService openItemQueryService;
    private final CaseQueryService caseQueryService;

    /**
     * C1) GET /api/synapse/entities
     * C1-alt) GET /api/synapse/entities/parties — 동일 동작 (FE 계약: 경로 충돌 방지용 별칭)
     */
    @GetMapping({"", "/parties"})
    public ApiResponse<PageResponse<EntityListRowDto>> getEntities(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Double riskMin,
            @RequestParam(required = false) Double riskMax,
            @RequestParam(required = false) Boolean hasOpenItems,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = EntityQueryService.EntityListQuery.builder()
                .type(type)
                .bukrs(bukrs)
                .country(country)
                .riskMin(riskMin)
                .riskMax(riskMax)
                .hasOpenItems(hasOpenItems)
                .q(q)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<EntityListRowDto> result = entityQueryService.findEntities(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * C2) GET /api/synapse/entities/{partyId} 또는 /entities/parties/{partyId}
     * partyId는 숫자만 매칭 (fi-doc-headers, cases 등과 경로 충돌 방지)
     */
    @GetMapping({"/{partyId:[0-9]+}", "/parties/{partyId:[0-9]+}"})
    public ApiResponse<Entity360Dto> getEntity360(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long partyId) {

        Entity360Dto dto = entityQueryService.findEntity360(tenantId, partyId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "거래처를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * C3) GET /api/synapse/entities/{partyId}/change-logs 또는 /entities/parties/{partyId}/change-logs
     */
    @GetMapping({"/{partyId:[0-9]+}/change-logs", "/parties/{partyId:[0-9]+}/change-logs"})
    public ApiResponse<PageResponse<EntityChangeLogDto>> getChangeLogs(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<EntityChangeLogDto> result = entityQueryService.findChangeLogs(tenantId, partyId, page, size);
        return ApiResponse.success(result);
    }

    /**
     * C4) GET /api/synapse/entities/{partyId}/documents 또는 /entities/parties/{partyId}/documents
     */
    @GetMapping({"/{partyId:[0-9]+}/documents", "/parties/{partyId:[0-9]+}/documents"})
    public ApiResponse<PageResponse<DocumentListRowDto>> getDocuments(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var query = DocumentQueryService.DocumentListQuery.builder()
                .partyId(partyId)
                .page(page)
                .size(size)
                .build();
        PageResponse<DocumentListRowDto> result = documentQueryService.findDocuments(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * C5) GET /api/synapse/entities/{partyId}/open-items 또는 /entities/parties/{partyId}/open-items
     */
    @GetMapping({"/{partyId:[0-9]+}/open-items", "/parties/{partyId:[0-9]+}/open-items"})
    public ApiResponse<PageResponse<OpenItemListRowDto>> getOpenItems(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var query = OpenItemQueryService.OpenItemListQuery.builder()
                .partyId(partyId)
                .page(page)
                .size(size)
                .build();
        PageResponse<OpenItemListRowDto> result = openItemQueryService.findOpenItems(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * C6) GET /api/synapse/entities/{partyId}/cases 또는 /entities/parties/{partyId}/cases
     */
    @GetMapping({"/{partyId:[0-9]+}/cases", "/parties/{partyId:[0-9]+}/cases"})
    public ApiResponse<PageResponse<CaseListRowDto>> getCases(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var query = CaseQueryService.CaseListQuery.builder()
                .partyId(partyId)
                .page(page)
                .size(size)
                .build();
        PageResponse<CaseListRowDto> result = caseQueryService.findCases(tenantId, query);
        return ApiResponse.success(result);
    }
}
