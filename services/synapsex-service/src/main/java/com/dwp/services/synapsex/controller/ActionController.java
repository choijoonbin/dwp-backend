package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.dto.action.ActionListRowDto;
import com.dwp.services.synapsex.dto.action.CreateActionRequest;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.service.action.ActionCommandService;
import com.dwp.services.synapsex.service.action.ActionQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Phase 2 Actions API
 */
@RestController
@RequestMapping("/synapse/actions")
@RequiredArgsConstructor
public class ActionController {

    private final ActionQueryService actionQueryService;
    private final ActionCommandService actionCommandService;

    /**
     * C1) GET /api/synapse/actions
     */
    @GetMapping
    public ApiResponse<PageResponse<ActionListRowDto>> getActions(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long caseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = ActionQueryService.ActionListQuery.builder()
                .status(status)
                .type(type)
                .caseId(caseId)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<ActionListRowDto> result = actionQueryService.findActions(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * C2) POST /api/synapse/actions
     */
    @PostMapping
    public ApiResponse<ActionDetailDto> createAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody CreateActionRequest request) {

        ActionDetailDto dto = actionCommandService.createAction(tenantId, request.getCaseId(), request.getActionType(),
                request.getPayload(), actorUserId, null, null, null);
        return ApiResponse.success(dto);
    }

    /**
     * C3) POST /api/synapse/actions/{actionId}/approve
     */
    @PostMapping("/{actionId}/approve")
    public ApiResponse<ActionListRowDto> approveAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long actionId) {

        var action = actionCommandService.approveAction(tenantId, actionId, actorUserId, null, null, null);
        return ApiResponse.success(ActionListRowDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus())
                .createdAt(action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt())
                .executedAt(action.getExecutedAt())
                .build());
    }

    /**
     * C4) POST /api/synapse/actions/{actionId}/execute
     */
    @PostMapping("/{actionId}/execute")
    public ApiResponse<ActionListRowDto> executeAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long actionId) {

        var action = actionCommandService.executeAction(tenantId, actionId, actorUserId, null, null, null);
        return ApiResponse.success(ActionListRowDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus())
                .createdAt(action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt())
                .executedAt(action.getExecutedAt())
                .outcome("EXECUTED".equals(action.getStatus()) ? "SUCCESS" : "FAILED")
                .failureReason(action.getFailureReason())
                .build());
    }
}
