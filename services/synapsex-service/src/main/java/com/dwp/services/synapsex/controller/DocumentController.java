package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.service.document.DocumentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase 1 Documents API
 * GET /api/synapse/documents, GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}
 */
@RestController
@RequestMapping("/synapse/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentQueryService documentQueryService;

    /**
     * A1) GET /api/synapse/documents
     */
    @GetMapping
    public ApiResponse<PageResponse<DocumentListRowDto>> getDocuments(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) String belnr,
            @RequestParam(required = false) String gjahr,
            @RequestParam(required = false) String usnam,
            @RequestParam(required = false) String tcode,
            @RequestParam(required = false) String xblnr,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) String lifnr,
            @RequestParam(required = false) String kunnr,
            @RequestParam(required = false) Boolean hasReversal,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = DocumentQueryService.DocumentListQuery.builder()
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .bukrs(bukrs)
                .belnr(belnr)
                .gjahr(gjahr)
                .usnam(usnam)
                .tcode(tcode)
                .xblnr(xblnr)
                .statusCode(statusCode)
                .lifnr(lifnr)
                .kunnr(kunnr)
                .hasReversal(hasReversal)
                .amountMin(amountMin)
                .amountMax(amountMax)
                .q(q)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<DocumentListRowDto> result = documentQueryService.findDocuments(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * A2) GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}
     */
    @GetMapping("/{bukrs}/{belnr}/{gjahr}")
    public ApiResponse<DocumentDetailDto> getDocumentDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable String bukrs,
            @PathVariable String belnr,
            @PathVariable String gjahr) {

        DocumentDetailDto dto = documentQueryService.findDocumentDetail(tenantId, bukrs.toUpperCase(), belnr, gjahr)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
