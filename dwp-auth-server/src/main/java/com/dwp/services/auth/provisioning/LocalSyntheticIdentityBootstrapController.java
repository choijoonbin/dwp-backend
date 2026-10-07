package com.dwp.services.auth.provisioning;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "dwp.synthetic-identity-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticIdentityBootstrapController {

    private final LocalSyntheticIdentityBootstrapService service;

    public LocalSyntheticIdentityBootstrapController(
            LocalSyntheticIdentityBootstrapService service) {
        this.service = service;
    }

    @PostMapping("/internal/synthetic/v1/identity/activate")
    public LocalSyntheticIdentityBootstrapDtos.ActivateResponse activate(
            @Valid @RequestBody LocalSyntheticIdentityBootstrapDtos.ActivateRequest request) {
        return service.activate(request);
    }
}
