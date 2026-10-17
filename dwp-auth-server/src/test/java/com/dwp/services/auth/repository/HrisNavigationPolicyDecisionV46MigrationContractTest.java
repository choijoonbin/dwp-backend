package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HrisNavigationPolicyDecisionV46MigrationContractTest {

    @Test
    void declaresOnlyTheImmutableDraftV46Seed() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V255__declare_hris_navigation_policy_decision_v46_seed.sql"));

        assertThat(sql)
                .contains("'product-surfaces'")
                .contains("\n    46,")
                .contains("'3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e'")
                .contains("'product-surfaces-v1.bundle-v46.generated.json'")
                .contains("'DRAFT'")
                .contains("FALSE")
                .doesNotContainIgnoringCase("UPDATE auth_product_authorization_bundle")
                .doesNotContainIgnoringCase("INSERT INTO auth_product_authorization_assignment")
                .doesNotContainIgnoringCase("INSERT INTO auth_role")
                .doesNotContainIgnoringCase("INSERT INTO auth_permission");
        assertThat(sql.split("INSERT INTO", -1)).hasSize(2);
    }
}
