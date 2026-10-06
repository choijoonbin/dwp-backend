package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentProposalV34AuthorizationSeedMigrationTest {

    @Test
    void declaresDraftWithoutImportApprovalActivationOrTenantAssignment()
            throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V241__declare_assignment_proposal_v34_authorization_seed.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("INSERT INTO auth_product_authorization_seed_release")
                    .contains("'product-surfaces-v1.bundle-v34.generated.json'")
                    .contains("'852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b'")
                    .contains("34", "'DRAFT'", "FALSE")
                    .doesNotContain("INSERT INTO auth_product_authorization_bundle")
                    .doesNotContain("auth_product_authorization_active")
                    .doesNotContain("auth_tenant_product_authorization")
                    .doesNotContain("'APPROVED'", "'ACTIVE'");
        }
    }
}
