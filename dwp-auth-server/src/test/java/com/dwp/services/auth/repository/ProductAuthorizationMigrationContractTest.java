package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationMigrationContractTest {

    @Test
    void migrationCreatesImmutableRegistryAndCasPointerTables() throws IOException {
        String migration = migration(
                "db/migration/V87__create_product_surface_authorization_registry.sql");

        assertThat(migration).contains(
                "CREATE TABLE auth_product_authorization_bundle",
                "CREATE TABLE auth_product_capability_contract",
                "CREATE TABLE auth_product_access_policy",
                "CREATE TABLE auth_product_entitlement_expression",
                "CREATE TABLE auth_product_predicate_policy",
                "CREATE TABLE auth_governed_route_contract",
                "CREATE TABLE auth_product_authorization_active",
                "CREATE TABLE auth_product_authorization_activation_event",
                "dwp_reject_authorization_descriptor_mutation",
                "dwp_guard_authorization_active_pointer_update",
                "trg_product_authorization_activation_event_immutable",
                "resulting_revision = expected_revision + 1");
        assertThat(migration).doesNotContain("ON DELETE CASCADE");
    }

    @Test
    void predicateOwnerConstraintIncludesEveryIndependentPepRuntime() throws IOException {
        String migration = migration(
                "db/migration/V209__authorize_product_predicate_owner_services.sql");

        assertThat(migration).contains(
                "DROP CONSTRAINT IF EXISTS ck_product_predicate_owner_service",
                "ADD CONSTRAINT ck_product_predicate_owner_service",
                "'agent'",
                "'approval'",
                "'auth'",
                "'meeting'",
                "'messaging'",
                "'notification'",
                "'people'",
                "'platform'",
                "'space'",
                "VALIDATE CONSTRAINT ck_product_predicate_owner_service");
    }

    private String migration(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
