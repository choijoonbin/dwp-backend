package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Tim007V50AuthorizationSeedMigrationContractTest {

    @Test
    void declaresOnlyTheTim007V50DraftWithoutImportOrActivation() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V258__declare_tim007_v50_authorization_seed.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .containsOnlyOnce("INSERT INTO auth_product_authorization_seed_release")
                .containsOnlyOnce("        50,")
                .containsOnlyOnce("'product-surfaces-v1.bundle-v50.generated.json'")
                .containsOnlyOnce(
                        "'c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760'")
                .containsOnlyOnce("        'DRAFT',\n        FALSE")
                .doesNotContainIgnoringCase("UPDATE auth_product_authorization_seed_release")
                .doesNotContainIgnoringCase("DELETE FROM auth_product_authorization_seed_release")
                .doesNotContain("INSERT INTO auth_product_authorization_bundle")
                .doesNotContain("INSERT INTO auth_product_authorization_active")
                .doesNotContain("auth_tenant_product_authorization")
                .doesNotContain("INSERT INTO com_resources")
                .doesNotContain("INSERT INTO com_role_permissions")
                .doesNotContain("'APPROVED'", "'ACTIVE'", "TRUE");
    }
}
