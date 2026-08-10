package com.dwp.services.platform.provisioning;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.UUID;

@RestController
@RequestMapping("/internal/provider/v1/tenants")
public class PlatformTenantProvisioningController {

    private final PlatformTenantProvisioningService service;

    public PlatformTenantProvisioningController(PlatformTenantProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse provision(
            @Valid @RequestBody PlatformTenantProvisioningDtos.ProvisionTenantRequest request) {
        return service.provision(request);
    }

    @PatchMapping("/{providerTenantId}/lifecycle")
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse lifecycle(
            @PathVariable UUID providerTenantId,
            @Valid @RequestBody PlatformTenantProvisioningDtos.UpdateLifecycleRequest request) {
        return service.lifecycle(providerTenantId, request);
    }

    @PutMapping("/{providerTenantId}/entitlements")
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse replaceEntitlements(
            @PathVariable UUID providerTenantId,
            @Valid @RequestBody PlatformTenantProvisioningDtos.ReplaceEntitlementsRequest request) {
        return service.replaceEntitlements(providerTenantId, request);
    }

    @PostMapping("/{providerTenantId}/asset-storage")
    public PlatformTenantProvisioningDtos.ProvisionTenantResponse assetStorage(
            @PathVariable UUID providerTenantId) {
        return service.provisionStorage(providerTenantId);
    }
}
