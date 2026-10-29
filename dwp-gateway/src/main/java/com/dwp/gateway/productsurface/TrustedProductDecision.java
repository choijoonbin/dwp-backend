package com.dwp.gateway.productsurface;

/** Read-only trusted PRODUCT decision DTO shared by aggregation and forwarding boundaries. */
public interface TrustedProductDecision {
    ProductSurfaceContextDtos.ProductEvaluationData data();

    String contextKey();

    ProductSurfaceContextDtos.EffectiveScope scope();

    ProductSurfaceContextDtos.SourceRevisions revisions();

    boolean authRouteProductNotRegistered();
}
