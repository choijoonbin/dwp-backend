package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AppResponsibilityEffectiveUserSodMigrationContractTest {

    @Test
    void v208RepairsDriftThenEnforcesEveryAuthorityChangingBoundary() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V208__enforce_effective_user_app_responsibility_sod.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "LOCK TABLE",
                "IN SHARE ROW EXCLUSIVE MODE",
                "CREATE TABLE sys_app_responsibility_sod_repairs",
                "CREATE VIEW auth_open_app_responsibility_subjects",
                "CREATE FUNCTION auth_app_responsibility_sod_conflicts",
                "CREATE FUNCTION dwp_repair_app_responsibility_sod",
                "SELECT dwp_repair_app_responsibility_sod()",
                "EFFECTIVE_USER_SOD_REPAIR_V208",
                "UPDATE com_admin_scoped_duty_assignments duty",
                "UPDATE com_admin_app_preset_assignments aggregate",
                "aggregate.responsibility_assignment_id = revoked_assignment",
                "dwp_reject_app_responsibility_sod_repair_mutation",
                "trg_app_responsibility_sod_repair_immutable",
                "CREATE FUNCTION dwp_assert_app_responsibility_sod",
                "CREATE FUNCTION dwp_enforce_app_responsibility_user_sod",
                "trg_app_responsibility_effective_user_sod",
                "trg_app_responsibility_group_membership_sod",
                "trg_app_responsibility_group_state_sod",
                "trg_app_responsibility_user_state_sod",
                "trg_app_responsibility_user_insert_sod",
                "trg_app_responsibility_scope_member_sod",
                "trg_app_responsibility_scope_state_sod",
                "pg_advisory_xact_lock",
                "user_record.status = 'ACTIVE'",
                "access_group.status = 'ACTIVE'",
                "COALESCE(left_subject.valid_from",
                "right_member.resource_key = left_member.resource_key");
        assertThat(migration).doesNotContain(
                "DROP TABLE com_admin_role_assignments",
                "DELETE FROM com_admin_role_assignments");
    }
}
