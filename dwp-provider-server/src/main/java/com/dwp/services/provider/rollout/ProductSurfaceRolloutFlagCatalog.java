package com.dwp.services.provider.rollout;

import java.util.LinkedHashSet;
import java.util.Set;

final class ProductSurfaceRolloutFlagCatalog {

    static final String CONTEXT_SHADOW_FLAG =
            "access.product-surfaces.context-shadow.v1";
    static final String LEGACY_GLOBAL_ENFORCEMENT_FLAG =
            "access.product-surfaces.capability-enforcement.v1";

    private static final Set<String> PRODUCT_KEYS = Set.of(
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
    private static final Set<String> ALL_FLAGS = buildAllFlags();

    private ProductSurfaceRolloutFlagCatalog() {
    }

    static Set<String> productKeys() {
        return PRODUCT_KEYS;
    }

    static String productEnforcementFlag(String productKey) {
        requireProduct(productKey);
        return "access.product-surfaces.capability-enforcement."
                + productKey + ".v1";
    }

    static String uiFlag(String productKey) {
        requireProduct(productKey);
        return "ux.product-surfaces." + productKey + ".v1";
    }

    static Set<String> allFlags() {
        return ALL_FLAGS;
    }

    static boolean contains(String featureKey) {
        return featureKey != null && ALL_FLAGS.contains(featureKey);
    }

    private static Set<String> buildAllFlags() {
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        flags.add(CONTEXT_SHADOW_FLAG);
        flags.add(LEGACY_GLOBAL_ENFORCEMENT_FLAG);
        PRODUCT_KEYS.stream().sorted()
                .map(ProductSurfaceRolloutFlagCatalog::productEnforcementFlag)
                .forEach(flags::add);
        PRODUCT_KEYS.stream().sorted()
                .map(ProductSurfaceRolloutFlagCatalog::uiFlag)
                .forEach(flags::add);
        if (flags.size() != 26) {
            throw new IllegalStateException("Product-surface rollout flag catalog is incomplete");
        }
        return Set.copyOf(flags);
    }

    private static void requireProduct(String productKey) {
        if (productKey == null || !PRODUCT_KEYS.contains(productKey)) {
            throw new IllegalArgumentException("Unknown product-surface rollout product");
        }
    }
}
