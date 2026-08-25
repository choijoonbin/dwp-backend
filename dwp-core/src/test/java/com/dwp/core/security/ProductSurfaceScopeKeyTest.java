package com.dwp.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProductSurfaceScopeKeyTest {

    @Test
    void preservesTheCanonicalCrossServiceResourceSetEncoding() {
        assertThat(ProductSurfaceScopeKey.resourceSet(
                7L, 41L, "approvals", "approvals.admin", "RS_APPROVALS"))
                .isEqualTo("scope-4b4952ace110601cfcf023abff9b7f67");
    }

    @Test
    void rejectsUnboundOrNonCanonicalMaterial() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ProductSurfaceScopeKey.resourceSet(
                        0L, 41L, "approvals", "approvals.admin", "RS_APPROVALS"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ProductSurfaceScopeKey.resourceSet(
                        7L, 41L, "approvals", "approvals.admin", " RS_APPROVALS"));
    }
}
