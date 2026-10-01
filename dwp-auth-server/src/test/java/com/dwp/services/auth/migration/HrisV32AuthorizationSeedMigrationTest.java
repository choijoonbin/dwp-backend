package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisV32AuthorizationSeedMigrationTest {

    @Test
    void declaresTheImmutableDraftWithoutImportingApprovingOrActivatingIt() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V232__declare_hris_v32_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("DROP CONSTRAINT IF EXISTS ck_product_predicate_owner_service")
                    .contains("ADD CONSTRAINT ck_product_predicate_owner_service")
                    .contains("'payroll'", "'time'")
                    .contains("VALIDATE CONSTRAINT ck_product_predicate_owner_service")
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v32.generated.json'")
                    .contains("'9e4e274bf457d1a5947c8b54e83299d28fb9fe128d9f1100991bc30634b54344'")
                    .contains("32", "'DRAFT'", "FALSE")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
            assertThat(sql.indexOf("VALIDATE CONSTRAINT ck_product_predicate_owner_service"))
                    .isLessThan(sql.indexOf(
                            "INSERT INTO auth_product_authorization_seed_release"));
        }
    }
}
