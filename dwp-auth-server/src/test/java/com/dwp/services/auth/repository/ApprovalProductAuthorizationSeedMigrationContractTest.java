package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalProductAuthorizationSeedMigrationContractTest {

    @Test
    void v89DeclaresImmutableV2DraftSeedAndCannotAutoImportOrActivate() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V89__seed_approval_product_management_capabilities.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "CREATE TABLE auth_product_authorization_seed_release",
                "'product-surfaces'",
                "2,",
                "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c",
                "product-surfaces-v1.bundle-v2.generated.json",
                "'DRAFT'",
                "FALSE",
                "ck_product_authorization_seed_release_default_off",
                "trg_product_authorization_seed_release_immutable");
        assertThat(migration).doesNotContain(
                "INSERT INTO auth_product_authorization_active",
                "bundle_status = 'APPROVED'",
                "bundle_status = 'ACTIVE'");
    }
}
