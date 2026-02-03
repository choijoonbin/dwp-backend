package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.document.DocumentReversalChainDto;
import com.dwp.services.synapsex.service.document.DocumentQueryService;
import com.dwp.services.synapsex.util.DocKeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase 1 Documents API
 * GET /api/synapse/documents, GET /api/synapse/documents/{docKey}, GET /api/synapse/documents/{docKey}/reversal-chain
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromBudat,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toBudat,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) String belnr,
            @RequestParam(required = false) String gjahr,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) String usnam,
            @RequestParam(required = false) String tcode,
            @RequestParam(required = false) String xblnr,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) String integrityStatus,
            @RequestParam(required = false) String lifnr,
            @RequestParam(required = false) String kunnr,
            @RequestParam(required = false) Boolean hasReversal,
            @RequestParam(required = false) Boolean hasCase,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = DocumentQueryService.DocumentListQuery.builder()
                .dateFrom(fromBudat)
                .dateTo(toBudat)
                .bukrs(bukrs)
                .belnr(belnr)
                .gjahr(gjahr)
                .partyId(partyId)
                .usnam(usnam)
                .tcode(tcode)
                .xblnr(xblnr)
                .statusCode(statusCode)
                .integrityStatus(integrityStatus)
                .lifnr(lifnr)
                .kunnr(kunnr)
                .hasReversal(hasReversal)
                .hasCase(hasCase)
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
     * A2a) GET /api/synapse/documents/{docKey} — docKey 형식: bukrs-belnr-gjahr
     */
    @GetMapping("/{docKey}")
    public ApiResponse<DocumentDetailDto> getDocumentDetailByDocKey(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable String docKey) {

        DocKeyUtil.ParsedDocKey parsed = DocKeyUtil.parse(docKey);
        if (parsed == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "docKey 형식이 올바르지 않습니다. (예: bukrs-belnr-gjahr)");
        }
        DocumentDetailDto dto = documentQueryService.findDocumentDetail(
                        tenantId, parsed.getBukrs().toUpperCase(), parsed.getBelnr(), parsed.getGjahr())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * A2b) GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr} — 레거시 경로 (호환용)
     */
    @GetMapping("/{bukrs}/{belnr}/{gjahr}")
    public ApiResponse<DocumentDetailDto> getDocumentDetailByPath(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable String bukrs,
            @PathVariable String belnr,
            @PathVariable String gjahr) {

        DocumentDetailDto dto = documentQueryService.findDocumentDetail(tenantId, bukrs.toUpperCase(), belnr, gjahr)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * A3) GET /api/synapse/documents/{docKey}/reversal-chain
     */
    @GetMapping("/{docKey}/reversal-chain")
    public ApiResponse<DocumentReversalChainDto> getReversalChain(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable String docKey) {

        DocumentReversalChainDto dto = documentQueryService.findReversalChain(tenantId, docKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
