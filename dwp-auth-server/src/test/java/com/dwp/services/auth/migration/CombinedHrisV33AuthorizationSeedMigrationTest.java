package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CombinedHrisV33AuthorizationSeedMigrationTest {

    @Test
    void extendsOwnerClosureAndDeclaresDraftWithoutImportApprovalOrActivation()
            throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V240__declare_combined_hris_v33_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("DROP CONSTRAINT IF EXISTS ck_product_predicate_owner_service")
                    .contains("ADD CONSTRAINT ck_product_predicate_owner_service")
                    .contains("'payroll'", "'time'")
                    .contains("VALIDATE CONSTRAINT ck_product_predicate_owner_service")
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v33.generated.json'")
                    .contains("'9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639'")
                    .contains("33", "'DRAFT'", "FALSE")
                    .doesNotContain("INSERT INTO auth_product_authorization_bundle")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("auth_tenant_product_authorization")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
            assertThat(sql.indexOf("VALIDATE CONSTRAINT ck_product_predicate_owner_service"))
                    .isLessThan(sql.indexOf(
                            "INSERT INTO auth_product_authorization_seed_release"));
        }
    }
}
