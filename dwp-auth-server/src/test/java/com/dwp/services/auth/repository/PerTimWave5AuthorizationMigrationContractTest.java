package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PerTimWave5AuthorizationMigrationContractTest {

    @Test
    void v251CreatesOnlyExactReviewUpdateGrantsAndSealsCombinedV42AsDraft()
            throws Exception {
        String migration = read(
                "db/migration/V251__authorize_performance_review_and_timecard_contracts.sql");

        assertThat(migration).contains(
                "'DATA.HRIS_PERFORMANCE_REVIEW', 'DATA'",
                "('WORKSPACE_MEMBER', 'DATA.HRIS_PERFORMANCE_REVIEW', 'UPDATE', 'ACTIVE')",
                "('TALENT_ADMIN', 'DATA.HRIS_PERFORMANCE_REVIEW', 'UPDATE', 'ACTIVE')",
                "('HR_ADMIN', 'DATA.HRIS_PERFORMANCE_REVIEW', 'UPDATE', 'ACTIVE')",
                "INSERT INTO com_resources",
                "INSERT INTO com_role_permissions",
                "access_revision = user_record.access_revision + 1",
                "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983",
                "product-surfaces-v1.bundle-v42.generated.json",
                "'DRAFT'",
                "FALSE");
        assertThat(migration).doesNotContain(
                "'DATA.HRIS_PERFORMANCE_REVIEW', 'VIEW'",
                "'DATA.HRIS_PERFORMANCE_REVIEW', 'CREATE'",
                "'DATA.HRIS_PERFORMANCE_REVIEW', 'APPROVE'",
                "'DATA.HRIS_PERFORMANCE_REVIEW', 'MANAGE'",
                "DATA.HR_TIME',",
                "DATA.HRIS_COMMAND_RECEIPT',",
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
