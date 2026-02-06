package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.audit.AuditRequestContext;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.document.DocumentReversalChainDto;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.document.DocumentQueryService;
import com.dwp.services.synapsex.util.DocKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 1 Documents API
 * GET /api/synapse/documents, GET /api/synapse/documents/{docKey}, GET /api/synapse/documents/{docKey}/reversal-chain
 */
@RestController
@RequestMapping("/synapse/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentQueryService documentQueryService;
    private final AuditWriter auditWriter;

    /**
     * A1) GET /api/synapse/documents
     */
    @GetMapping
    public ApiResponse<PageResponse<DocumentListRowDto>> getDocuments(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
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
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {

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
        Map<String, Object> filters = new HashMap<>();
        if (bukrs != null && !bukrs.isBlank()) filters.put("bukrs", bukrs);
        if (statusCode != null && !statusCode.isBlank()) filters.put("statusCode", statusCode);
        Map<String, Object> tags = AuditRequestContext.listTags(page, size, sort, null, filters);
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_DOCUMENT_VIEW_LIST,
                AuditEventConstants.RESOURCE_DOCUMENT, null, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(result);
    }

    /**
     * A2a) GET /api/synapse/documents/{docKey} — docKey 형식: bukrs-belnr-gjahr
     */
    @GetMapping("/{docKey}")
    public ApiResponse<DocumentDetailDto> getDocumentDetailByDocKey(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable String docKey,
            HttpServletRequest httpRequest) {

        DocKeyUtil.ParsedDocKey parsed = DocKeyUtil.parse(docKey);
        if (parsed == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "docKey 형식이 올바르지 않습니다. (예: bukrs-belnr-gjahr)");
        }
        DocumentDetailDto dto = documentQueryService.findDocumentDetail(
                        tenantId, parsed.getBukrs().toUpperCase(), parsed.getBelnr(), parsed.getGjahr())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        Map<String, Object> tags = Map.of("docKey", docKey);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_DOCUMENT_VIEW_DETAIL,
                AuditEventConstants.RESOURCE_DOCUMENT, docKey, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(dto);
    }

    /**
     * A2b) GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr} — 레거시 경로 (호환용)
     */
    @GetMapping("/{bukrs}/{belnr}/{gjahr}")
    public ApiResponse<DocumentDetailDto> getDocumentDetailByPath(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable String bukrs,
            @PathVariable String belnr,
            @PathVariable String gjahr,
            HttpServletRequest httpRequest) {

        DocumentDetailDto dto = documentQueryService.findDocumentDetail(tenantId, bukrs.toUpperCase(), belnr, gjahr)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        String docKey = bukrs + "-" + belnr + "-" + gjahr;
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        Map<String, Object> tags = Map.of("docKey", docKey);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_DOCUMENT_VIEW_DETAIL,
                AuditEventConstants.RESOURCE_DOCUMENT, docKey, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(dto);
    }

    /**
     * A3) GET /api/synapse/documents/{docKey}/reversal-chain
     */
    @GetMapping("/{docKey}/reversal-chain")
    public ApiResponse<DocumentReversalChainDto> getReversalChain(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable String docKey,
            HttpServletRequest httpRequest) {

        DocumentReversalChainDto dto = documentQueryService.findReversalChain(tenantId, docKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        Map<String, Object> tags = Map.of("docKey", docKey);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_DOCUMENT_VIEW_DETAIL,
                AuditEventConstants.RESOURCE_DOCUMENT, docKey, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(dto);
    }
}
