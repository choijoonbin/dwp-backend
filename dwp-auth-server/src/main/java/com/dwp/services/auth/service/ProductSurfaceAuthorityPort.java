package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

/**
 * Adapter boundary for the immutable product-authorization registry and its evaluators.
 */
public interface ProductSurfaceAuthorityPort {

    ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request);
}
