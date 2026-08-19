package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
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
    public ApiResponse<MailDtos.AdminOverview> overview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.adminOverview(tenantId));
    }

    @PutMapping("/policy")
    public ApiResponse<MailDtos.TenantPolicy> updatePolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailDtos.TenantPolicyRequest request) {
        return ApiResponse.success(service.updatePolicy(
                tenantId, userId, correlationId, request));
    }

    @PutMapping("/connections/{connectionId}")
    public ApiResponse<MailDtos.ConnectionSummary> updateConnection(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody MailDtos.ConnectionUpdateRequest request) {
        return ApiResponse.success(service.updateConnection(
                tenantId, userId, connectionId, correlationId, request));
    }

    @PutMapping("/shared-inboxes/{sharedInboxId}")
    public ApiResponse<MailDtos.SharedInboxSummary> updateSharedInbox(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID sharedInboxId,
            @Valid @RequestBody MailDtos.SharedInboxUpdateRequest request) {
        return ApiResponse.success(service.updateSharedInbox(
                tenantId, userId, sharedInboxId, correlationId, request));
    }
}
