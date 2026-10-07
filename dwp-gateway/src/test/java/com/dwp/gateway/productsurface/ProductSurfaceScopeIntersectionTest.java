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
    void collapsesPayReadAndMutationAliasesWithoutEscalatingEitherGrant() {
        OffsetDateTime later = VALID_UNTIL.plusSeconds(1);
        var result = ProductSurfaceScopeIntersection.intersect(
                List.of(readOnlyGrant("grant.read", "source.read"),
                        grant("grant.mutate", "source.mutate")),
                List.of(source("source.read", true, later),
                        source("source.mutate", false, VALID_UNTIL)),
                List.of(eligible("source.read", "Pay population", false, later),
                        eligible("source.mutate", "Pay population", false, VALID_UNTIL)));

        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo("hcm-scope-derived");
            assertThat(scope.readOnly()).isFalse();
            assertThat(scope.validUntil()).isEqualTo(VALID_UNTIL);
        });
        assertThat(result.grants()).satisfiesExactly(
                grant -> {
                    assertThat(grant.readOnly()).isTrue();
                    assertThat(grant.scopeKeys()).containsExactly("hcm-scope-derived");
                },
                grant -> {
                    assertThat(grant.readOnly()).isFalse();
                    assertThat(grant.scopeKeys()).containsExactly("hcm-scope-derived");
                });
    }

    @Test
    void dropsUnmatchedAuthOnlyGrantButKeepsPeopleOwnedIntersection() {
        var result = ProductSurfaceScopeIntersection.intersect(
                List.of(grant("hcm.personal-access.v1", "source.self"),
                        grant("hcm.management-product-entry.v1", "source.tenant")),
                List.of(source("source.self", false, VALID_UNTIL),
                        new ProductSurfaceContextDtos.EffectiveScope(
                                "source.tenant", "TENANT", "Tenant", true,
                                false, VALID_UNTIL)),
                List.of(eligible("source.self", "Self", false, VALID_UNTIL)));

        assertThat(result.grants()).singleElement().satisfies(grant -> {
            assertThat(((ProductSurfaceContextDtos.PolicyGrant) grant).accessPolicyKey())
                    .isEqualTo("hcm.personal-access.v1");
            assertThat(grant.scopeKeys()).containsExactly("hcm-scope-derived");
        });
        assertThat(result.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.key()).isEqualTo("hcm-scope-derived"));
    }

    @Test
    void rejectsAliasMaterialWithConflictingSemanticIdentity() {
        assertThatThrownBy(() -> ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.page", "source.page")),
                List.of(source("source.page", false, VALID_UNTIL),
                        source("source.action", false, VALID_UNTIL)),
                List.of(eligible("source.page", "Pay population", false, VALID_UNTIL),
                        eligible("source.action", "Different", false, VALID_UNTIL))))
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);

        assertThatThrownBy(() -> ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.page", "source.page"),
                        grant("grant.action", "source.action")),
                List.of(source("source.page", false, VALID_UNTIL),
                        source("source.action", false, VALID_UNTIL)),
                List.of(eligible("source.page", "Pay population", true, VALID_UNTIL),
                        eligible("source.action", "Pay population", false, VALID_UNTIL))))
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);
    }

    @Test
    void rejectsIntersectionWhenPeopleOwnsNoneOfTheAuthGrants() {
        assertThatThrownBy(() -> ProductSurfaceScopeIntersection.intersect(
                List.of(grant("grant.tenant", "source.tenant")),
                List.of(source("source.tenant", false, VALID_UNTIL)),
                List.of(eligible("source.other", "Other", false, VALID_UNTIL))))
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);
    }

    private static ProductSurfaceContextDtos.PolicyGrant grant(
            String key,
            String sourceScope) {
        return grant(key, sourceScope, false);
    }

    private static ProductSurfaceContextDtos.PolicyGrant readOnlyGrant(
            String key,
            String sourceScope) {
        return grant(key, sourceScope, true);
    }

    private static ProductSurfaceContextDtos.PolicyGrant grant(
            String key,
            String sourceScope,
            boolean readOnly) {
        return new ProductSurfaceContextDtos.PolicyGrant(
                key,
                "policy-ref",
                ProductSurfaceContextDtos.PolicyAuthorityMode.ENTITLEMENT_AND_RELATIONSHIP,
                List.of(sourceScope),
                true,
                readOnly,
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
