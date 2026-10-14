package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationPlanningAuthorizationMigrationContractTest {

    @Test
    void v247MaterializesExactMakerAndIndependentApproverGrants() throws Exception {
        String migration = read("db/migration/V247__authorize_compensation_planning.sql");

        assertThat(migration).contains(
                "'DATA.HRIS_COMPENSATION_PLAN', 'DATA'",
                "'ACTION.HRIS_COMPENSATION_PLAN_APPROVE', 'ACTION'",
                "('TALENT_ADMIN', 'DATA.HRIS_COMPENSATION_PLAN', 'VIEW', 'ACTIVE')",
                "('TALENT_ADMIN', 'DATA.HRIS_COMPENSATION_PLAN', 'UPDATE', 'ACTIVE')",
                "('HR_ADMIN', 'DATA.HRIS_COMPENSATION_PLAN', 'VIEW', 'ACTIVE')",
                "('HR_ADMIN', 'DATA.HRIS_COMPENSATION_PLAN', 'UPDATE', 'ACTIVE')",
                "('HR_ADMIN', 'ACTION.HRIS_COMPENSATION_PLAN_APPROVE', 'APPROVE', 'ACTIVE')",
                "INSERT INTO com_resources",
                "INSERT INTO com_role_permissions",
                "access_revision = user_record.access_revision + 1");
        assertThat(migration).doesNotContain(
                "('TALENT_ADMIN', 'ACTION.HRIS_COMPENSATION_PLAN_APPROVE'",
                "'DATA.HRIS_COMPENSATION_PLAN', 'MANAGE'",
                "'ACTION.HRIS_COMPENSATION_PLAN_APPROVE', 'MANAGE'");
    }

    @Test
    void v248DeclaresImmutableV39DraftAndCannotAutoImportOrActivate() throws Exception {
        String migration = read(
                "db/migration/V248__declare_compensation_planning_v39_authorization_seed.sql");

        assertThat(migration).contains(
                "'product-surfaces'",
                "39,",
                "86e796c95e45c02892d4c4ae76e7a5b36114ab09554ffc1be3a450066fe8e34c",
                "product-surfaces-v1.bundle-v39.generated.json",
                "'DRAFT'",
                "FALSE");
        assertThat(migration).doesNotContain(
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
