package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceScopeIntersectionTest {

    private static final OffsetDateTime VALID_UNTIL =
            OffsetDateTime.parse("2026-10-02T06:00:00Z");

    @Test
    void collapsesCompatibleAliasMaterialAndRebindsEachGrantBySourceAlias() {
        var result = ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.page", "source.page"),
                        grant("grant.action", "source.action")),
                List.of(source("source.page", false, VALID_UNTIL),
                        source("source.action", false, VALID_UNTIL)),
                List.of(eligible("source.page", "Pay population", false, VALID_UNTIL),
                        eligible("source.action", "Pay population", false, VALID_UNTIL)));

        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo("hcm-scope-derived");
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.displayName()).isEqualTo("Pay population");
            assertThat(scope.isDefault()).isTrue();
            assertThat(scope.readOnly()).isFalse();
            assertThat(scope.validUntil()).isEqualTo(VALID_UNTIL);
        });
        assertThat(result.grants())
                .extracting(ProductSurfaceContextDtos.EffectiveGrant::scopeKeys)
                .containsExactly(List.of("hcm-scope-derived"),
                        List.of("hcm-scope-derived"));
    }

    @Test
    void rejectsAliasMaterialWithConflictingDisplayReadOnlyOrValidity() {
        assertThatThrownBy(() -> ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.page", "source.page")),
                List.of(source("source.page", false, VALID_UNTIL),
                        source("source.action", false, VALID_UNTIL)),
                List.of(eligible("source.page", "Pay population", false, VALID_UNTIL),
                        eligible("source.action", "Different", false, VALID_UNTIL))))
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);

        assertThatThrownBy(() -> ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.page", "source.page")),
                List.of(source("source.page", false, VALID_UNTIL),
                        source("source.action", true, VALID_UNTIL)),
                List.of(eligible("source.page", "Pay population", false, VALID_UNTIL),
                        eligible("source.action", "Pay population", false, VALID_UNTIL))))
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);

        OffsetDateTime later = VALID_UNTIL.plusSeconds(1);
        assertThatThrownBy(() -> ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.page", "source.page")),
                List.of(source("source.page", false, VALID_UNTIL),
                        source("source.action", false, later)),
                List.of(eligible("source.page", "Pay population", false, VALID_UNTIL),
                        eligible("source.action", "Pay population", false, later))))
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);
    }

    private static ProductSurfaceContextDtos.PolicyGrant grant(
            String key,
            String sourceScope) {
        return new ProductSurfaceContextDtos.PolicyGrant(
                key,
                "policy-ref",
                ProductSurfaceContextDtos.PolicyAuthorityMode.ENTITLEMENT_AND_RELATIONSHIP,
                List.of(sourceScope),
                true,
                false,
                VALID_UNTIL);
    }

    private static ProductSurfaceContextDtos.EffectiveScope source(
            String key,
            boolean readOnly,
            OffsetDateTime validUntil) {
        return new ProductSurfaceContextDtos.EffectiveScope(
                key, "TARGET_POPULATION", "Auth source", true, readOnly, validUntil);
    }

    private static ProductSurfaceContextDtos.EligibleScope eligible(
            String source,
            String displayName,
            boolean readOnly,
            OffsetDateTime validUntil) {
        return new ProductSurfaceContextDtos.EligibleScope(
                source, "hcm-scope-derived", "TARGET_POPULATION", displayName,
                true, readOnly, validUntil);
    }
}
