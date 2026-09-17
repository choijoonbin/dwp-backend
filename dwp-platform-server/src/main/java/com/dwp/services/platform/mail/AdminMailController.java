package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/mail")
public class AdminMailController {

    private final MailService service;

    public AdminMailController(MailService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(operationId = "getAdminMailOverview")
    public ApiResponse<MailDtos.AdminOverview> overview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.adminOverview(tenantId));
    }

    @PutMapping("/policy")
    @Operation(operationId = "updateAdminMailPolicy")
    public ApiResponse<MailDtos.TenantPolicy> updatePolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Active-Access-Mode", required = false)
            String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody MailDtos.TenantPolicyRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.updatePolicy(
                tenantId, userId, correlationId, idempotencyKey, request));
    }

    @PutMapping("/connections/{connectionId}")
    public ApiResponse<MailDtos.ConnectionSummary> updateConnection(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Active-Access-Mode", required = false)
            String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @PathVariable UUID connectionId,
            @Valid @RequestBody MailDtos.ConnectionUpdateRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.updateConnection(
                tenantId, userId, connectionId, correlationId,
                idempotencyKey, request));
    }

    @PutMapping("/shared-inboxes/{sharedInboxId}")
    public ApiResponse<MailDtos.SharedInboxSummary> updateSharedInbox(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Active-Access-Mode", required = false)
            String accessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @PathVariable UUID sharedInboxId,
            @Valid @RequestBody MailDtos.SharedInboxUpdateRequest request) {
        AdminMailCompletionController.requireElevated(accessMode);
        return ApiResponse.success(service.updateSharedInbox(
                tenantId, userId, sharedInboxId, correlationId,
                idempotencyKey, request));
    }
}
