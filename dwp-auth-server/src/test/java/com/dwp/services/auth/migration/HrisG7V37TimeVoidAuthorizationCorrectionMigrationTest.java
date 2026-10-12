package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisG7V37TimeVoidAuthorizationCorrectionMigrationTest {

    @Test
    void declaresCorrectiveDraftWithoutMutatingOrActivatingV36() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V245__declare_hris_g7_v37_time_void_authorization_correction.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v37.generated.json'")
                    .contains("'1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c'")
                    .contains("37", "'DRAFT'", "FALSE")
                    .doesNotContain("UPDATE auth_product_authorization_seed_release")
                    .doesNotContain("DELETE FROM auth_product_authorization_seed_release")
                    .doesNotContain("INSERT INTO auth_product_authorization_bundle")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("auth_tenant_product_authorization")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
