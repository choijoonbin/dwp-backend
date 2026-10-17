package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class HrisNavigationCanonicalPagesV47MigrationContractTest {

    @Test
    void declaresOnlyTheImmutableDraftV47Seed() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V256__declare_hris_navigation_canonical_pages_v47_seed.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("'product-surfaces'")
                .contains("47")
                .contains("'925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e'")
                .contains("'product-surfaces-v1.bundle-v47.generated.json'")
                .contains("'DRAFT'")
                .contains("FALSE")
                .doesNotContainIgnoringCase("UPDATE auth_product_authorization_bundle")
                .doesNotContainIgnoringCase("INSERT INTO auth_product_authorization_active")
                .doesNotContainIgnoringCase("enrolled_tenant");
    }
}
