package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisNavigationPolicyAuthorizationMigrationContractTest {

    @Test
    void v249CreatesOnlyExactNavigationPolicyGrantsAndSealsV40AsDraft() throws Exception {
        String migration = read(
                "db/migration/V249__authorize_hris_navigation_policy.sql");

        assertThat(migration).contains(
                "VALUES ('CONFIG', 'Configuration', 'ACTIVE', 55)",
                "'APP', 'ADMIN', 'DATA', 'ACTION', 'CONFIG'",
                "'CONFIG.HRIS_NAVIGATION_POLICY', 'CONFIG'",
                "'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'ACTION'",
                "('ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'VIEW', 'ACTIVE')",
                "('ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'UPDATE', 'ACTIVE')",
                "('ADMIN', 'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'PUBLISH', 'ACTIVE')",
                "('HR_ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'VIEW', 'ACTIVE')",
                "('HR_ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'UPDATE', 'ACTIVE')",
                "('HR_ADMIN', 'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'PUBLISH', 'ACTIVE')",
                "4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329",
                "product-surfaces-v1.bundle-v40.generated.json",
                "'DRAFT'",
                "FALSE");
        assertThat(migration).doesNotContain(
                "CONFIG.HRIS_NAVIGATION_POLICY', 'MANAGE'",
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'MANAGE'",
                "INSERT INTO auth_product_authorization_active",
                "bundle_status = 'APPROVED'",
                "bundle_status = 'ACTIVE'");
    }

    private String read(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
