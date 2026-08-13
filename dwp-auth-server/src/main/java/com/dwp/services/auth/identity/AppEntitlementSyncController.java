package com.dwp.services.auth.identity;

import com.dwp.services.auth.service.AppEntitlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/identity/v1/tenants/{tenantId}/app-entitlements")
public class AppEntitlementSyncController {

    private final AppEntitlementService service;

    public AppEntitlementSyncController(AppEntitlementService service) {
        this.service = service;
    }

    @PutMapping("/{sourceRef}")
    public AppEntitlementDtos.SyncResult synchronize(
            @PathVariable Long tenantId,
            @PathVariable String sourceRef,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody AppEntitlementDtos.SyncRequest request) {
        return service.synchronize(tenantId, sourceRef, correlationId, request);
    }
}
