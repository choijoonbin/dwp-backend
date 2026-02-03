package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.entity.Entity360Dto;
import com.dwp.services.synapsex.dto.entity.EntityListRowDto;
import com.dwp.services.synapsex.service.entity.EntityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 1 Entities API (bp_party)
 * GET /api/synapse/entities, GET /api/synapse/entities/{partyId}
 *
 * <p>FiDocumentScopeController와 동일 base path(/synapse/entities)를 사용하나,
 * 하위 경로 fi-doc-headers, fi-open-items, cases, actions는 FiDocumentScopeController가 처리.
 * 본 컨트롤러는 목록(/) 및 상세(/{partyId})만 담당.
 */
@RestController
@RequestMapping("/synapse/entities")
@RequiredArgsConstructor
public class EntityController {

    private final EntityQueryService entityQueryService;

    /**
     * C1) GET /api/synapse/entities
     */
    @GetMapping
    public ApiResponse<PageResponse<EntityListRowDto>> getEntities(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String type,
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
     * C2) GET /api/synapse/entities/{partyId}
     * partyId는 숫자만 매칭 (fi-doc-headers, cases 등과 경로 충돌 방지)
     */
    @GetMapping("/{partyId:[0-9]+}")
    public ApiResponse<Entity360Dto> getEntity360(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long partyId) {

        Entity360Dto dto = entityQueryService.findEntity360(tenantId, partyId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "거래처를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
