package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationAuthorityEndpointMigrationTest {

    @Test
    void additiveMigrationCreatesAnImmutableBundleOwnedEndpointStore() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V89_3__persist_product_authority_endpoints.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "CREATE TABLE auth_product_authority_endpoint",
                "REFERENCES auth_product_authorization_bundle(bundle_id)",
                "PRIMARY KEY (bundle_id, endpoint_key)",
                "trg_product_authority_endpoint_immutable",
                "dwp_reject_authorization_descriptor_mutation");
        assertThat(migration).doesNotContain("ON DELETE CASCADE", "INSERT INTO");
    }
}
