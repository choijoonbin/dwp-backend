package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.integration.IntegrationOutboxEnqueueRequest;
import com.dwp.services.synapsex.dto.integration.IntegrationResultUpdateRequest;
import com.dwp.services.synapsex.entity.IntegrationOutbox;
import com.dwp.services.synapsex.service.integration.IntegrationOutboxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Integration Outbox API.
 * 스펙: INTEGRATION_OUTBOX_ENQUEUE, INTEGRATION_RESULT_UPDATE 감사 이벤트 트리거.
 */
@RestController
@RequestMapping("/synapse/integration")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationOutboxService integrationOutboxService;

    @PostMapping("/outbox")
    public ApiResponse<IntegrationOutbox> enqueue(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody IntegrationOutboxEnqueueRequest request,
            HttpServletRequest httpRequest) {
        IntegrationOutbox outbox = integrationOutboxService.enqueue(
                tenantId,
                request.getTargetSystem(),
                request.getEventType(),
                request.getEventKey(),
                request.getPayload(),
                actorUserId != null ? actorUserId : 0L,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("X-Gateway-Request-Id"));
        return ApiResponse.success("아웃박스에 등록되었습니다.", outbox);
    }

    @PatchMapping("/outbox/{outboxId}/result")
    public ApiResponse<IntegrationOutbox> updateResult(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long outboxId,
            @Valid @RequestBody IntegrationResultUpdateRequest request,
            HttpServletRequest httpRequest) {
        IntegrationOutbox outbox = integrationOutboxService.updateResult(
                tenantId,
                outboxId,
                request.getStatus(),
                request.getResultMessage(),
                actorUserId != null ? actorUserId : 0L,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("X-Gateway-Request-Id"));
        return ApiResponse.success("연동 결과가 반영되었습니다.", outbox);
    }
}
