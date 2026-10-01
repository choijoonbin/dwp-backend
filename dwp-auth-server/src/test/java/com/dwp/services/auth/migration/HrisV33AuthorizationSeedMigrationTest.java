package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisV33AuthorizationSeedMigrationTest {

    @Test
    void declaresTheImmutableDraftWithoutImportingApprovingActivatingOrAssigningIt()
            throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V233__declare_hris_v33_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v33.generated.json'")
                    .contains("'254ead674e1126d50e8dcf1011486ea1127fb2479f7a82d832cdf0466995bc49'")
                    .contains("33", "'DRAFT'", "FALSE")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("auth_tenant_product_authorization")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
