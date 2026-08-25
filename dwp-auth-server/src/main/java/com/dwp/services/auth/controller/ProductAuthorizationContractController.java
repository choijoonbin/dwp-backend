package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/product-authorization/bundles")
public class ProductAuthorizationContractController {

    private final ProductAuthorizationContractService service;

    public ProductAuthorizationContractController(ProductAuthorizationContractService service) {
        this.service = service;
    }

    @GetMapping("/{bundleKey}/active")
    @Operation(operationId = "getActiveProductAuthorizationBundleInternal")
    public ProductAuthorizationContractDtos.BundleView active(@PathVariable String bundleKey) {
        return service.active(bundleKey);
    }

    @GetMapping("/{bundleKey}/versions/{version}")
    @Operation(operationId = "getProductAuthorizationBundleVersionInternal")
    public ProductAuthorizationContractDtos.BundleView version(
            @PathVariable String bundleKey,
            @PathVariable long version) {
        return service.version(bundleKey, version);
    }
}
