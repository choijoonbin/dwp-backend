package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV266MigrationContractTest {
    private static final String FEATURE = "HRIS_HRM_DATA_QUALITY_WORKBENCH";
    private static final String DUTY = "HRIS_DUTY_INTEGRATION_EXECUTOR";
    private static final String CAPABILITY = "hcm.integration.workforce-ingestion.execute";
    private static final String RESOURCE = "ACTION.HRIS_INTEGRATION_EXECUTE";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.people-data-quality.page",
            "route.hcm.operations.people-data-quality.data",
            "route.hcm.operations.workforce-ingestion.action");

    @Test
    void declaresOnlyExactCatalogReadinessDefaultOffRoutesAndNoAssignmentsOrGrants()
            throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V266__declare_hris_hrm012_v57_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String checksum;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v57.generated.json")
                .getInputStream()) {
            checksum = new ObjectMapper().readTree(input).get("checksum").textValue();
        }

        assertThat(sql).contains(
                "'product-surfaces', 57", "'" + checksum + "'", "'DRAFT', FALSE",
                "('" + FEATURE + "', 1, 'hcm', 'APP.HCM', 57, 'OFF', 'HRM')",
                "('EXECUTE', 'Execute', 1, 1)",
                "('" + DUTY + "', 'hcm', 'HR_ADMIN', 'APP.HCM'",
                "'" + CAPABILITY + "'",
                "'" + RESOURCE + "'",
                "('HRIS_INTEGRATION_EXECUTOR', 'hcm', 'APP.HCM'",
                "'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO ")).toList())
                .containsExactly(
                        "INSERT INTO com_permissions (code, name, created_by, updated_by)",
                        "INSERT INTO sys_tenant_resource_templates (",
                        "INSERT INTO sys_admin_responsibility_catalog (",
                        "INSERT INTO sys_admin_scoped_duty_catalog (",
                        "INSERT INTO sys_admin_scoped_duty_capabilities (",
                        "INSERT INTO sys_admin_app_preset_catalog (",
                        "INSERT INTO sys_admin_app_preset_duties (preset_code, duty_code, sort_order)",
                        "INSERT INTO com_resources (",
                        "INSERT INTO auth_product_authorization_seed_release (",
                        "INSERT INTO sys_product_feature_activation_policies (",
                        "INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)",
                        "INSERT INTO sys_product_feature_route_bindings (");
        for (String route : ROUTES) {
            assertThat(sql.split("'" + route + "'", -1)).as(route).hasSize(3);
        }
        assertThat(sql).contains(
                "subject.duty_code = 'HRIS_DUTY_CONTRACT_ISSUER'",
                "subject.duty_code = 'HRIS_DUTY_STATUTORY_OPERATOR'",
                "subject.duty_code = '" + DUTY + "'",
                "capability.resolved_capability_code =\n"
                        + "                'ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE'");
        assertThat(sql).doesNotContain(
                "INSERT INTO com_admin_scoped_duty_assignments",
                "INSERT INTO com_admin_role_assignments",
                "INSERT INTO com_admin_app_preset_assignments",
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes",
                "INSERT INTO com_role_permissions",
                "hcm.integration.execute'");
    }
}
