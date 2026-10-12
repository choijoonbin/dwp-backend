package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisG7V35AuthorizationSeedMigrationTest {

    @Test
    void declaresDraftWithoutImportApprovalActivationOrTenantAssignment()
            throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V243__declare_hris_g7_v35_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v35.generated.json'")
                    .contains("'38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d'")
                    .contains("35", "'DRAFT'", "FALSE")
                    .doesNotContain("INSERT INTO auth_product_authorization_bundle")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("auth_tenant_product_authorization")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
