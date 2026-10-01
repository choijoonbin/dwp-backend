package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationsCodeSetAuthorizationSeedMigrationTest {

    @Test
    void declaresTheImmutableDraftWithoutImportingApprovingOrActivatingIt() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V237__declare_communications_code_set_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v32.generated.json'")
                    .contains("'b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc'")
                    .contains("32", "'DRAFT'", "FALSE")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
