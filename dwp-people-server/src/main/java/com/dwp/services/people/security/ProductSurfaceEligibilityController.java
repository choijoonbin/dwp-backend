package com.dwp.services.people.security;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/people/v1/product-surface-eligibility")
public class ProductSurfaceEligibilityController {

    private final ProductSurfaceEligibilityService service;

    public ProductSurfaceEligibilityController(ProductSurfaceEligibilityService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(operationId = "evaluateProductSurfaceEligibilityInternal")
    public ProductSurfaceEligibilityDtos.EligibilityResult evaluate(
            @Valid @RequestBody ProductSurfaceEligibilityDtos.EvaluateRequest request) {
        return service.evaluate(request);
    }
}
