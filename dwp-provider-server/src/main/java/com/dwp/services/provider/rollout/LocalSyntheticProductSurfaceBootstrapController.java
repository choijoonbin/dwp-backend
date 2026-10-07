package com.dwp.services.provider.rollout;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "dwp.provider.synthetic-product-surface-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticProductSurfaceBootstrapController {
    private final LocalSyntheticProductSurfaceBootstrapService service;

    public LocalSyntheticProductSurfaceBootstrapController(
            LocalSyntheticProductSurfaceBootstrapService service) {
        this.service = service;
    }

    @PostMapping("/internal/synthetic/v1/product-surface/bootstrap")
    public LocalSyntheticProductSurfaceBootstrapDtos.BootstrapResponse bootstrap(
            @Valid @RequestBody
                    LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest request) {
        return service.bootstrap(request);
    }
}
