package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.openitem.OpenItemDetailDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.service.openitem.OpenItemQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Phase 1 Open Items API
 * GET /api/synapse/open-items, GET /api/synapse/open-items/{bukrs}/{belnr}/{gjahr}/{buzei}
 */
@RestController
@RequestMapping("/synapse/open-items")
@RequiredArgsConstructor
public class OpenItemController {

    private final OpenItemQueryService openItemQueryService;

    /**
     * B1) GET /api/synapse/open-items
     */
    @GetMapping
    public ApiResponse<PageResponse<OpenItemListRowDto>> getOpenItems(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false) Boolean cleared,
            @RequestParam(required = false) Boolean paymentBlock,
            @RequestParam(required = false) Boolean disputeFlag,
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) String lifnr,
            @RequestParam(required = false) String kunnr,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = OpenItemQueryService.OpenItemListQuery.builder()
                .dueFrom(dueFrom)
                .dueTo(dueTo)
                .cleared(cleared)
                .paymentBlock(paymentBlock)
                .disputeFlag(disputeFlag)
                .itemType(itemType)
                .bukrs(bukrs)
                .partyId(partyId)
                .lifnr(lifnr)
                .kunnr(kunnr)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<OpenItemListRowDto> result = openItemQueryService.findOpenItems(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * B2) GET /api/synapse/open-items/{bukrs}/{belnr}/{gjahr}/{buzei}
     */
    @GetMapping("/{bukrs}/{belnr}/{gjahr}/{buzei}")
    public ApiResponse<OpenItemDetailDto> getOpenItemDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable String bukrs,
            @PathVariable String belnr,
            @PathVariable String gjahr,
            @PathVariable String buzei) {

        OpenItemDetailDto dto = openItemQueryService.findOpenItemDetail(
                tenantId, bukrs.toUpperCase(), belnr, gjahr, buzei)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "오픈아이템을 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
