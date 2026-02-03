package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.dto.action.ActionListRowDto;
import com.dwp.services.synapsex.dto.action.CreateActionRequest;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.service.action.ActionCommandService;
import com.dwp.services.synapsex.service.action.ActionQueryService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
    private final AuditWriter auditWriter;

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
     * POST /api/synapse/actions/{actionId}/simulate
     * Pre-execution Simulation (before/after diff + 예상 outcome)
     */
    @PostMapping("/{actionId}/simulate")
    public ResponseEntity<ApiResponse<ActionDetailDto>> simulateAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long actionId,
            HttpServletRequest httpRequest) {
        try {
            ActionDetailDto dto = actionQueryService.findActionDetail(tenantId, actionId)
                    .orElseThrow(() -> new com.dwp.core.exception.BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션을 찾을 수 없습니다."));
            auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_SIMULATE, actionId, dto.getCaseId(),
                    actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                    null, java.util.Map.of("actionId", actionId, "caseId", dto.getCaseId()),
                    null, null, null);
            return ResponseEntity.ok(ApiResponse.success(dto));
        } catch (com.dwp.core.exception.BaseException e) {
            Long auditId = auditWriter.logActionEventFailure(tenantId, AuditEventConstants.TYPE_SIMULATE,
                    actionId, null, actorUserId, e.getMessage(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID));
            return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                    .body(ApiResponse.error(e.getErrorCode(), e.getMessage(),
                            auditId != null ? String.valueOf(auditId) : null,
                            httpRequest.getHeader(HeaderConstants.X_TRACE_ID),
                            httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID)));
        } catch (Exception e) {
            Long auditId = auditWriter.logActionEventFailure(tenantId, AuditEventConstants.TYPE_SIMULATE,
                    actionId, null, actorUserId, e.getMessage(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID));
            return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                    .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, e.getMessage(),
                            auditId != null ? String.valueOf(auditId) : null,
                            httpRequest.getHeader(HeaderConstants.X_TRACE_ID),
                            httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID)));
        }
    }

    /**
     * C3) POST /api/synapse/actions/{actionId}/approve
     */
    @PostMapping("/{actionId}/approve")
    public ResponseEntity<ApiResponse<ActionListRowDto>> approveAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long actionId,
            HttpServletRequest httpRequest) {
        try {
            var action = actionCommandService.approveAction(tenantId, actionId, actorUserId, null, null, null);
            return ResponseEntity.ok(ApiResponse.success(ActionListRowDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus())
                .createdAt(action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt())
                .executedAt(action.getExecutedAt())
                .build()));
        } catch (com.dwp.core.exception.BaseException e) {
            Long auditId = auditWriter.logActionEventFailure(tenantId, AuditEventConstants.TYPE_APPROVE,
                    actionId, null, actorUserId, e.getMessage(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID));
            return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                    .body(ApiResponse.error(e.getErrorCode(), e.getMessage(),
                            auditId != null ? String.valueOf(auditId) : null,
                            httpRequest.getHeader(HeaderConstants.X_TRACE_ID),
                            httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID)));
        } catch (Exception e) {
            Long auditId = auditWriter.logActionEventFailure(tenantId, AuditEventConstants.TYPE_APPROVE,
                    actionId, null, actorUserId, e.getMessage(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID));
            return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                    .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, e.getMessage(),
                            auditId != null ? String.valueOf(auditId) : null,
                            httpRequest.getHeader(HeaderConstants.X_TRACE_ID),
                            httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID)));
        }
    }

    /**
     * POST /api/synapse/actions/{actionId}/reject
     * 액션 반려 (CANCELED)
     */
    @PostMapping("/{actionId}/reject")
    public ApiResponse<ActionListRowDto> rejectAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long actionId) {

        var action = actionCommandService.rejectAction(tenantId, actionId, actorUserId, null, null, null);
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
    public ResponseEntity<ApiResponse<ActionListRowDto>> executeAction(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long actionId,
            HttpServletRequest httpRequest) {
        try {
            var action = actionCommandService.executeAction(tenantId, actionId, actorUserId, null, null, null);
            return ResponseEntity.ok(ApiResponse.success(ActionListRowDto.builder()
                    .actionId(action.getActionId())
                    .caseId(action.getCaseId())
                    .actionType(action.getActionType())
                    .status(action.getStatus())
                    .createdAt(action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt())
                    .executedAt(action.getExecutedAt())
                    .outcome("EXECUTED".equals(action.getStatus()) ? "SUCCESS" : "FAILED")
                    .failureReason(action.getFailureReason())
                    .build()));
        } catch (com.dwp.core.exception.BaseException e) {
            Long auditId = auditWriter.logActionEventFailure(tenantId, AuditEventConstants.TYPE_EXECUTE,
                    actionId, null, actorUserId, e.getMessage(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID));
            return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                    .body(ApiResponse.error(e.getErrorCode(), e.getMessage(),
                            auditId != null ? String.valueOf(auditId) : null,
                            httpRequest.getHeader(HeaderConstants.X_TRACE_ID),
                            httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID)));
        } catch (Exception e) {
            Long auditId = auditWriter.logActionEventFailure(tenantId, AuditEventConstants.TYPE_EXECUTE,
                    actionId, null, actorUserId, e.getMessage(),
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID));
            return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                    .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, e.getMessage(),
                            auditId != null ? String.valueOf(auditId) : null,
                            httpRequest.getHeader(HeaderConstants.X_TRACE_ID),
                            httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID)));
        }
    }
}
