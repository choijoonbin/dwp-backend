package com.dwp.gateway.productsurface;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact activation-control vocabulary for the governed Product Surface inventory. */
final class ProductSurfaceRolloutFlagCatalog {

    static final String CONTEXT_SHADOW_FLAG =
            "access.product-surfaces.context-shadow.v1";
    static final String LEGACY_GLOBAL_ENFORCEMENT_FLAG =
            "access.product-surfaces.capability-enforcement.v1";

    private static final List<String> PRODUCTS = List.of(
            "approvals",
            "calendar",
            "communications",
            "dwaion",
            "hcm",
            "mail",
            "meetings",
            "messaging",
            "notifications",
            "services",
            "spaces",
            "workplace");
    private static final Set<String> PRODUCT_SET = Set.copyOf(PRODUCTS);
    private static final Set<String> ALL_FLAGS = allFlags();

    private ProductSurfaceRolloutFlagCatalog() {
    }

    static List<String> products() {
        return PRODUCTS;
    }

    static Set<String> productSet() {
        return PRODUCT_SET;
    }

    static boolean supportsProduct(String productKey) {
        return PRODUCT_SET.contains(productKey == null ? "" : productKey);
    }

    static String productEnforcementFlag(String productKey) {
        requireProduct(productKey);
        return "access.product-surfaces.capability-enforcement." + productKey + ".v1";
    }

    static String uiFlag(String productKey) {
        requireProduct(productKey);
        return "ux.product-surfaces." + productKey + ".v1";
    }

    static Set<String> flags() {
        return ALL_FLAGS;
    }

    private static void requireProduct(String productKey) {
        if (!supportsProduct(productKey)) {
            throw new IllegalArgumentException("Invalid product rollout key");
        }
    }

    private static Set<String> allFlags() {
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        flags.add(CONTEXT_SHADOW_FLAG);
        flags.add(LEGACY_GLOBAL_ENFORCEMENT_FLAG);
        PRODUCTS.forEach(product -> flags.add(productEnforcementFlag(product)));
        PRODUCTS.forEach(product -> flags.add(uiFlag(product)));
        return Set.copyOf(flags);
    }
}
