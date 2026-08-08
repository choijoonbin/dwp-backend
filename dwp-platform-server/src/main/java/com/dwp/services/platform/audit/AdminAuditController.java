package com.dwp.services.platform.audit;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/audit-events")
public class AdminAuditController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";

    private final PlatformAuditService auditService;

    public AdminAuditController(PlatformAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<PlatformAuditService.AuditPage> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(auditService.list(tenantId, page, size));
    }
}
