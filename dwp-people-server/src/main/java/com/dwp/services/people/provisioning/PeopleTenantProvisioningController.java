package com.dwp.services.people.provisioning;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/provider/v1/tenants")
public class PeopleTenantProvisioningController {

    private final PeopleTenantProvisioningService service;

    public PeopleTenantProvisioningController(PeopleTenantProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public PeopleTenantProvisioningDtos.ProvisionTenantResponse provision(
            @Valid @RequestBody PeopleTenantProvisioningDtos.ProvisionTenantRequest request) {
        return service.provision(request);
    }

    @PatchMapping("/{providerTenantId}/lifecycle")
    public PeopleTenantProvisioningDtos.ProvisionTenantResponse lifecycle(
            @PathVariable UUID providerTenantId,
            @Valid @RequestBody PeopleTenantProvisioningDtos.UpdateLifecycleRequest request) {
        return service.lifecycle(providerTenantId, request);
    }
}
