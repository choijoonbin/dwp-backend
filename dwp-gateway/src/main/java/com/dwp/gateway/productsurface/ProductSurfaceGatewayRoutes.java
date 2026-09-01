package com.dwp.gateway.productsurface;

/** Canonical gateway-owned handler paths for product-surface authority routes. */
final class ProductSurfaceGatewayRoutes {

    static final String CONTEXTS_HANDLER_PATH = "/_gateway/product-surface-contexts";
    static final String PRODUCT_EVALUATION_HANDLER_PATH =
            "/_gateway/product-surface-access/evaluate";
    static final String GOVERNED_EVALUATION_HANDLER_PATH =
            "/_gateway/governed-route-access/evaluate";

    private ProductSurfaceGatewayRoutes() {
    }
}
