package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalV31AuthorizationSeedMigrationTest {

    @Test
    void declaresTheImmutableDraftWithoutImportingApprovingOrActivatingIt() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V223__declare_approval_v31_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v31.generated.json'")
                    .contains("'be4e1b6db3d3f0b5100182a3c80066a39c64479f9ba88d908fee661efd3335b8'")
                    .contains("31", "'DRAFT'", "FALSE")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
