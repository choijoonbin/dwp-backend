package com.dwp.services.provider.rollout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceRolloutFlagCatalogTest {

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

    @Test
    void exposesTheExactTwelveProductAndTwentySixFlagContract() {
        assertThat(ProductSurfaceRolloutFlagCatalog.productKeys())
                .hasSize(12)
                .containsExactlyInAnyOrderElementsOf(PRODUCTS);
        assertThat(ProductSurfaceRolloutFlagCatalog.allFlags())
                .hasSize(26)
                .allMatch(ProductSurfaceRolloutFlagCatalog::contains)
                .contains(
                        ProductSurfaceRolloutFlagCatalog.CONTEXT_SHADOW_FLAG,
                        ProductSurfaceRolloutFlagCatalog.LEGACY_GLOBAL_ENFORCEMENT_FLAG);
        PRODUCTS.forEach(product -> assertThat(ProductSurfaceRolloutFlagCatalog.allFlags())
                .contains(
                        ProductSurfaceRolloutFlagCatalog.productEnforcementFlag(product),
                        ProductSurfaceRolloutFlagCatalog.uiFlag(product)));

        assertThatThrownBy(() -> ProductSurfaceRolloutFlagCatalog.productKeys().add("unknown"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ProductSurfaceRolloutFlagCatalog.allFlags().add("unknown"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void generatesEveryProductScopedKeyExactly() {
        assertThat(PRODUCTS.stream()
                .map(ProductSurfaceRolloutFlagCatalog::productEnforcementFlag)
                .toList()).containsExactly(
                "access.product-surfaces.capability-enforcement.approvals.v1",
                "access.product-surfaces.capability-enforcement.calendar.v1",
                "access.product-surfaces.capability-enforcement.communications.v1",
                "access.product-surfaces.capability-enforcement.dwaion.v1",
                "access.product-surfaces.capability-enforcement.hcm.v1",
                "access.product-surfaces.capability-enforcement.mail.v1",
                "access.product-surfaces.capability-enforcement.meetings.v1",
                "access.product-surfaces.capability-enforcement.messaging.v1",
                "access.product-surfaces.capability-enforcement.notifications.v1",
                "access.product-surfaces.capability-enforcement.services.v1",
                "access.product-surfaces.capability-enforcement.spaces.v1",
                "access.product-surfaces.capability-enforcement.workplace.v1");
        assertThat(PRODUCTS.stream()
                .map(ProductSurfaceRolloutFlagCatalog::uiFlag)
                .toList()).containsExactly(
                "ux.product-surfaces.approvals.v1",
                "ux.product-surfaces.calendar.v1",
                "ux.product-surfaces.communications.v1",
                "ux.product-surfaces.dwaion.v1",
                "ux.product-surfaces.hcm.v1",
                "ux.product-surfaces.mail.v1",
                "ux.product-surfaces.meetings.v1",
                "ux.product-surfaces.messaging.v1",
                "ux.product-surfaces.notifications.v1",
                "ux.product-surfaces.services.v1",
                "ux.product-surfaces.spaces.v1",
                "ux.product-surfaces.workplace.v1");
    }

    @Test
    void rejectsUnknownAndCaseVariantProductsAndFlags() {
        assertThat(ProductSurfaceRolloutFlagCatalog.contains(null)).isFalse();
        assertThat(List.of(
                        "access.product-surfaces.capability-enforcement.unknown.v1",
                        "access.product-surfaces.capability-enforcement.Approvals.v1",
                        "ux.product-surfaces.unknown.v1",
                        "ux.product-surfaces.Approvals.v1"))
                .noneMatch(ProductSurfaceRolloutFlagCatalog::contains);

        List.of("unknown", "Approvals", "APPROVALS").forEach(product -> {
            assertThatThrownBy(() ->
                    ProductSurfaceRolloutFlagCatalog.productEnforcementFlag(product))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProductSurfaceRolloutFlagCatalog.uiFlag(product))
                    .isInstanceOf(IllegalArgumentException.class);
        });
        assertThatThrownBy(() -> ProductSurfaceRolloutFlagCatalog.productEnforcementFlag(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSurfaceRolloutFlagCatalog.uiFlag(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
