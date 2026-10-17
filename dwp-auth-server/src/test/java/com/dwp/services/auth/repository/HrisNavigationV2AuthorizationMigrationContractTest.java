package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HrisNavigationV2AuthorizationMigrationContractTest {

    @Test
    void declaresOnlyTheImmutableDraftV45Seed() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V254__declare_hris_navigation_v2_authorization_seed.sql"));

        assertThat(sql)
                .contains("'product-surfaces'")
                .contains("\n    45,")
                .contains("'6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64'")
                .contains("'product-surfaces-v1.bundle-v45.generated.json'")
                .contains("'DRAFT'")
                .contains("FALSE")
                .doesNotContainIgnoringCase("UPDATE auth_product_authorization_bundle")
                .doesNotContainIgnoringCase("INSERT INTO auth_product_authorization_assignment")
                .doesNotContainIgnoringCase("INSERT INTO auth_role")
                .doesNotContainIgnoringCase("INSERT INTO auth_permission");
        assertThat(sql.split("INSERT INTO", -1)).hasSize(2);
    }
}
