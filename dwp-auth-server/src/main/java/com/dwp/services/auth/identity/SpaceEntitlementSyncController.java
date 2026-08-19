package com.dwp.services.auth.identity;

import com.dwp.services.auth.service.SpaceEntitlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/identity/v1/tenants/{tenantId}/space-entitlements")
public class SpaceEntitlementSyncController {

    private final SpaceEntitlementService service;

    public SpaceEntitlementSyncController(SpaceEntitlementService service) {
        this.service = service;
    }

    @PutMapping("/{sourceRef}")
    public SpaceEntitlementDtos.SyncResult synchronize(
            @PathVariable Long tenantId,
            @PathVariable String sourceRef,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody SpaceEntitlementDtos.SyncRequest request) {
        return service.synchronize(tenantId, sourceRef, correlationId, request);
    }

    @PostMapping("/principal-validations")
    public SpaceEntitlementDtos.PrincipalValidationResult validatePrincipal(
            @PathVariable Long tenantId,
            @Valid @RequestBody SpaceEntitlementDtos.PrincipalValidationRequest request) {
        return service.validatePrincipal(tenantId, request);
    }
}
