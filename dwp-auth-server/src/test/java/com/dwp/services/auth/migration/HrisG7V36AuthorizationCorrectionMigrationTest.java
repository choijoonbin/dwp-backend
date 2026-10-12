package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisG7V36AuthorizationCorrectionMigrationTest {

    @Test
    void declaresCorrectiveDraftWithoutMutatingOrActivatingV35() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V244__declare_hris_g7_v36_authorization_correction.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v36.generated.json'")
                    .contains("'12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b'")
                    .contains("36", "'DRAFT'", "FALSE")
                    .doesNotContain("UPDATE auth_product_authorization_seed_release")
                    .doesNotContain("DELETE FROM auth_product_authorization_seed_release")
                    .doesNotContain("INSERT INTO auth_product_authorization_bundle")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("auth_tenant_product_authorization")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
