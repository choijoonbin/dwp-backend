package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.ProductSurfaceAuthorityService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/product-surface-authority")
public class ProductSurfaceAuthorityController {

    private final ProductSurfaceAuthorityService service;

    public ProductSurfaceAuthorityController(ProductSurfaceAuthorityService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(operationId = "evaluateProductSurfaceAuthorityInternal")
    public ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            @Valid @RequestBody ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        return service.evaluate(request);
    }
}
