package com.dwp.gateway.productsurface;

import java.util.regex.Pattern;

/** Minimal generated-route metadata consumed by closed gateway route boundaries. */
public interface ProductRouteMetadata {
    String routeContractKey();

    String productKey();

    String surfaceKey();

    String method();

    String publicPath();

    String routeKind();

    boolean stateChanging();

    Pattern exactPattern();
}
