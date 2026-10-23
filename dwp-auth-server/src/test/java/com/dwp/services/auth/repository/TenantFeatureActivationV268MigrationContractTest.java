package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV268MigrationContractTest {
    private static final String FEATURE = "HRIS_PER_TEAM_PERFORMANCE_FEEDBACK";
    private static final List<String> ROUTES = List.of(
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data",
            "route.hcm.personal.performance-feedback.page",
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action",
            "route.hcm.personal.feedback-assignments-mine.data",
            "route.hcm.personal.feedback-assignment-detail.data",
            "route.hcm.personal.feedback-response-submit.action",
            "route.hcm.performance.feedback-release.data");

    @Test
    void declaresOnlyTheExactCatalogAndSevenZeroEffectBoundaries() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V268__declare_hris_per008_v59_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String checksum;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v59.generated.json")
                .getInputStream()) {
            checksum = new ObjectMapper().readTree(input).get("checksum").textValue();
        }

        assertThat(sql).contains(
                "'product-surfaces', 59",
                "'" + checksum + "'",
                "'product-surfaces-v1.bundle-v59.generated.json', 'DRAFT', FALSE",
                "('" + FEATURE + "', 1, 'hcm', 'APP.HCM', 59, 'OFF', 'PER')",
                "DATA.HRIS_TEAM_WORKBENCH",
                "ACTION.HRIS_PERFORMANCE_FEEDBACK",
                "DATA.HRIS_PERFORMANCE_FEEDBACK",
                "'DATA.HR_TALENT'",
                "'Talent, learning, and employee journeys'",
                "HRIS_DUTY_TEAM_WORKBENCH_READER",
                "HRIS_DUTY_SELF_PERFORMANCE_READER",
                "HRIS_DUTY_FEEDBACK_REQUESTER",
                "HRIS_DUTY_FEEDBACK_PROVIDER",
                "HRIS_DUTY_FEEDBACK_AGGREGATE_READER");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO ")).toList())
                .containsExactly(
                        "INSERT INTO sys_tenant_resource_templates (",
                        "INSERT INTO sys_admin_scoped_duty_catalog (",
                        "INSERT INTO sys_admin_scoped_duty_capabilities (",
                        "INSERT INTO com_resources (",
                        "INSERT INTO auth_product_authorization_seed_release (",
                        "INSERT INTO sys_product_feature_activation_policies (",
                        "INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)",
                        "INSERT INTO sys_product_feature_route_bindings (");
        for (String route : ROUTES) {
            assertThat(sql.split("'" + route + "'", -1)).as(route).hasSize(5);
        }
        assertThat(sql).contains(
                "FROM sys_tenant_role_permission_templates",
                "FROM com_role_permissions",
                "FROM com_admin_scoped_duty_assignments",
                "FROM sys_admin_app_preset_duties",
                "FROM com_admin_resource_set_members",
                "FROM com_principal_resource_grants",
                "FROM sys_builtin_role_catalog",
                "FROM com_roles",
                "FROM auth_product_feature_activation_changes",
                "FROM com_tenant_feature_enrollment_changes");
        assertThat(sql).doesNotContain(
                "INSERT INTO com_permissions",
                "INSERT INTO sys_tenant_role_permission_templates",
                "INSERT INTO sys_admin_responsibility_catalog",
                "INSERT INTO sys_admin_app_preset_catalog",
                "INSERT INTO sys_admin_app_preset_duties",
                "INSERT INTO com_admin_scoped_duty_assignments",
                "INSERT INTO com_admin_resource_set_members",
                "INSERT INTO com_admin_role_assignments",
                "INSERT INTO com_admin_app_preset_assignments",
                "INSERT INTO com_principal_resource_grants",
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes",
                "INSERT INTO com_role_permissions",
                "INSERT INTO com_roles",
                "INSERT INTO sys_builtin_role_catalog");
    }
}
