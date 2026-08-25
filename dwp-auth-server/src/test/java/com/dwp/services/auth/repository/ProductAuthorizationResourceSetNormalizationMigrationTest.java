package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationResourceSetNormalizationMigrationTest {

    @Test
    void v90RenamesStableProductBoundariesAndFailsClosedOnCollisions() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V90__normalize_product_authorization_resource_set_keys.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "('APP_COMMUNICATIONS', 'RS_COMMUNICATIONS', 'APP.COMMUNICATIONS')",
                "('APP_EMPLOYEE_SERVICES', 'RS_SERVICES', 'APP.EMPLOYEE_SERVICES')",
                "('APP_APPROVALS', 'RS_APPROVALS', 'APP.APPROVALS')",
                "('APP_HRIS', 'RS_HCM_CONFIG', 'APP.HCM')",
                "canonical.resource_set_id <> legacy.resource_set_id",
                "Canonical product authorization resource-set key collision",
                "SET resource_set_key = mapping.canonical_key",
                "member.resource_set_id = resource_set.resource_set_id",
                "Canonical product authorization resource-set root evidence is incomplete");
        assertThat(migration).doesNotContain(
                "INSERT INTO com_admin_resource_set_members",
                "INSERT INTO com_admin_role_assignments",
                "DELETE FROM com_admin_resource_sets");
    }
}
