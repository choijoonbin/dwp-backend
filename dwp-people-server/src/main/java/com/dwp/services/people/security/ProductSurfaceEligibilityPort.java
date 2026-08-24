package com.dwp.services.people.security;

/**
 * Product-owned relationship and target-population evaluator boundary.
 */
public interface ProductSurfaceEligibilityPort {

    ProductSurfaceEligibilityDtos.EligibilityResult evaluate(
            ProductSurfaceEligibilityDtos.EvaluateRequest request);
}
