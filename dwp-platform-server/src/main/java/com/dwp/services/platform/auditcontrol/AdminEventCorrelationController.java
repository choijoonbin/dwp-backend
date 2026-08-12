package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/audit-control/event-correlations")
public class AdminEventCorrelationController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";

    private final EventCorrelationService service;
    private final AuditAccessGuard guard;

    public AdminEventCorrelationController(
            EventCorrelationService service, AuditAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public ApiResponse<EventEnvelopeDtos.CorrelationPage> correlations(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "D7") AuditWindow window,
            @RequestParam(defaultValue = "ALL") String domain,
            @RequestParam(defaultValue = "ALL") String classification,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        guard.view(permissions);
        return ApiResponse.success(service.correlations(
                tenantId, window, domain, classification, query, page, size));
    }

    @GetMapping("/detail")
    public ApiResponse<EventEnvelopeDtos.CorrelationDetail> detail(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam String correlationId) {
        guard.view(permissions);
        return ApiResponse.success(service.detail(tenantId, correlationId));
    }
}
