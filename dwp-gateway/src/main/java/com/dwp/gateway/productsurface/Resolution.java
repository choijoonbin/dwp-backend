package com.dwp.gateway.productsurface;

import java.time.OffsetDateTime;
import java.util.List;

/** Package-level result shared by product-surface aggregation collaborators. */
record Resolution(
        ProductSurfaceContextDtos.Decision decision,
        String reasonCode,
        ProductSurfaceContextDtos.AuthorityResult authority,
        ProductSurfaceContextDtos.EffectiveContext context,
        ProductSurfaceContextDtos.SourceRevisions revisions,
        List<ProductSurfaceContextDtos.EffectiveScope> scopes,
        OffsetDateTime revalidateAt) {

    private static final String PRODUCT_NOT_REGISTERED = "PRODUCT_NOT_REGISTERED";

    boolean authSurfaceProductNotRegistered() {
        return authority.decision() == ProductSurfaceContextDtos.Decision.SURFACE_DENIED
                && PRODUCT_NOT_REGISTERED.equals(authority.reasonCode());
    }

    boolean authRouteProductNotRegistered() {
        return authority.decision() == ProductSurfaceContextDtos.Decision.ROUTE_DENIED
                && PRODUCT_NOT_REGISTERED.equals(authority.reasonCode());
    }
}
