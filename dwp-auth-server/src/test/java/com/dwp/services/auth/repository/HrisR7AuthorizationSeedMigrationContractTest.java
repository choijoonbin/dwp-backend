package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisR7AuthorizationSeedMigrationContractTest {

    @Test
    void declaresTheMissingV48AndR7V49DraftsWithoutImportOrActivation() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V257__declare_hris_payroll_settings_and_r7_authorization_seeds.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .containsOnlyOnce("INSERT INTO auth_product_authorization_seed_release")
                .contains("'product-surfaces-v1.bundle-v48.generated.json'")
                .contains("'9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043'")
                .contains("'product-surfaces-v1.bundle-v49.generated.json'")
                .contains("'5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60'")
                .containsOnlyOnce("        48,")
                .containsOnlyOnce("        49,")
                .containsOnlyOnce("        'DRAFT',\n        FALSE\n    ),")
                .containsOnlyOnce("        'DRAFT',\n        FALSE\n    );")
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
