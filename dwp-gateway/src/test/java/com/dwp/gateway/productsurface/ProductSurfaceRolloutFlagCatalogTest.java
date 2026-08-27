package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceRolloutFlagCatalogTest {

    private static final Set<String> PRODUCTS = Set.of(
            "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
            "meetings", "messaging", "notifications", "services", "spaces", "workplace");

    @Test
    void exactInventoryProducesTheTwentySixActivationControls() {
        assertThat(ProductSurfaceRolloutFlagCatalog.productSet())
                .containsExactlyInAnyOrderElementsOf(PRODUCTS);
        assertThat(ProductSurfaceRolloutFlagCatalog.products()).hasSize(12);
        assertThat(ProductSurfaceRolloutFlagCatalog.flags()).hasSize(26)
                .contains(
                        ProductSurfaceRolloutFlagCatalog.CONTEXT_SHADOW_FLAG,
                        ProductSurfaceRolloutFlagCatalog.LEGACY_GLOBAL_ENFORCEMENT_FLAG);
        for (String product : PRODUCTS) {
            assertThat(ProductSurfaceRolloutFlagCatalog.flags()).contains(
                    "access.product-surfaces.capability-enforcement." + product + ".v1",
                    "ux.product-surfaces." + product + ".v1");
        }
    }

    @Test
    void unknownOrCaseVariantProductsCannotCreateFlags() {
        for (String product : new String[] {null, "", "unknown", "Approvals", "approvals "}) {
            assertThat(ProductSurfaceRolloutFlagCatalog.supportsProduct(product)).isFalse();
            assertThatThrownBy(() ->
                    ProductSurfaceRolloutFlagCatalog.productEnforcementFlag(product))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProductSurfaceRolloutFlagCatalog.uiFlag(product))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
