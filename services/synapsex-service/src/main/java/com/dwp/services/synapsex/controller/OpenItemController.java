package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.audit.AuditRequestContext;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.openitem.OpenItemDetailDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.openitem.OpenItemQueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 1 Open Items API
 * GET /api/synapse/open-items, GET /api/synapse/open-items/{bukrs}/{belnr}/{gjahr}/{buzei}
 */
@RestController
@RequestMapping("/synapse/open-items")
@RequiredArgsConstructor
public class OpenItemController {

    private final OpenItemQueryService openItemQueryService;
    private final AuditWriter auditWriter;

    /**
     * B1) GET /api/synapse/open-items
     */
    @GetMapping
    public ApiResponse<PageResponse<OpenItemListRowDto>> getOpenItems(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) String lifnr,
            @RequestParam(required = false) String kunnr,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDueDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDueDate,
            @RequestParam(required = false) Integer daysPastDueMin,
            @RequestParam(required = false) Integer daysPastDueMax,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean paymentBlock,
            @RequestParam(required = false) Boolean disputeFlag,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {

        var query = OpenItemQueryService.OpenItemListQuery.builder()
                .type(type)
                .bukrs(bukrs)
                .partyId(partyId)
                .lifnr(lifnr)
                .kunnr(kunnr)
                .fromDueDate(fromDueDate)
                .toDueDate(toDueDate)
                .daysPastDueMin(daysPastDueMin)
                .daysPastDueMax(daysPastDueMax)
                .status(status)
                .paymentBlock(paymentBlock)
                .disputeFlag(disputeFlag)
                .q(q)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<OpenItemListRowDto> result = openItemQueryService.findOpenItems(tenantId, query);
        Map<String, Object> filters = new HashMap<>();
        if (type != null && !type.isBlank()) filters.put("type", type);
        if (status != null && !status.isBlank()) filters.put("status", status);
        Map<String, Object> tags = AuditRequestContext.listTags(page, size, sort, null, filters);
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_OPENITEM_VIEW_LIST,
                AuditEventConstants.RESOURCE_OPEN_ITEM, null, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(result);
    }

    /**
     * B2a) GET /api/synapse/open-items/{openItemKey} — openItemKey 형식: bukrs-belnr-gjahr-buzei
     */
    @GetMapping("/{openItemKey}")
    public ApiResponse<OpenItemDetailDto> getOpenItemDetailByKey(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable String openItemKey,
            HttpServletRequest httpRequest) {

        OpenItemDetailDto dto = openItemQueryService.findOpenItemDetailByOpenItemKey(tenantId, openItemKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "오픈아이템을 찾을 수 없습니다."));
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        Map<String, Object> tags = Map.of("openItemKey", openItemKey);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_OPENITEM_VIEW_DETAIL,
                AuditEventConstants.RESOURCE_OPEN_ITEM, openItemKey, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(dto);
    }

    /**
     * B2b) GET /api/synapse/open-items/{bukrs}/{belnr}/{gjahr}/{buzei} — 레거시 경로 (호환용)
     */
    @GetMapping("/{bukrs}/{belnr}/{gjahr}/{buzei}")
    public ApiResponse<OpenItemDetailDto> getOpenItemDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable String bukrs,
            @PathVariable String belnr,
            @PathVariable String gjahr,
            @PathVariable String buzei,
            HttpServletRequest httpRequest) {

        OpenItemDetailDto dto = openItemQueryService.findOpenItemDetail(
                tenantId, bukrs.toUpperCase(), belnr, gjahr, buzei)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "오픈아이템을 찾을 수 없습니다."));
        String openItemKey = bukrs + "-" + belnr + "-" + gjahr + "-" + buzei;
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        Map<String, Object> tags = Map.of("openItemKey", openItemKey);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_OPENITEM_VIEW_DETAIL,
                AuditEventConstants.RESOURCE_OPEN_ITEM, openItemKey, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(dto);
    }
}
