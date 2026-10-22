package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV267MigrationContractTest {
    private static final String FEATURE = "HRIS_PAY_RETIREMENT_WORKBENCH";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data");

    @Test
    void declaresOnlyTheExactDefaultOffCatalogWithoutAssignmentsOrGrants()
            throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V267__declare_hris_pay004_v58_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String checksum;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v58.generated.json")
                .getInputStream()) {
            checksum = new ObjectMapper().readTree(input).get("checksum").textValue();
        }

        assertThat(sql).contains(
                "'product-surfaces', 58",
                "'" + checksum + "'",
                "'product-surfaces-v1.bundle-v58.generated.json', 'DRAFT', FALSE",
                "('" + FEATURE + "', 1, 'hcm', 'APP.HCM', 58, 'OFF', 'PAY')",
                "58, 'PAGE', 'PRODUCT', 'hcm.operations'",
                "58, 'DATA', 'PRODUCT', 'hcm.operations'");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO ")).toList())
                .containsExactly(
                        "INSERT INTO auth_product_authorization_seed_release (",
                        "INSERT INTO sys_product_feature_activation_policies (",
                        "INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)",
                        "INSERT INTO sys_product_feature_route_bindings (");
        for (String route : ROUTES) {
            assertThat(sql.split("'" + route + "'", -1)).as(route).hasSize(5);
        }
        assertThat(sql).contains(
                "ON CONFLICT (bundle_key, version) DO NOTHING",
                "ON CONFLICT (feature_key, policy_version) DO NOTHING",
                "ON CONFLICT (route_contract_key) DO NOTHING",
                "ON CONFLICT (route_contract_key, feature_key, policy_version) DO NOTHING",
                "V267 conflict: product-authorization v58 release differs",
                "V267 conflict: PAY retirement feature policy differs",
                "V267 conflict: PAY retirement route owners differ",
                "V267 conflict: PAY retirement route bindings differ");
        assertThat(sql).doesNotContain(
                "INSERT INTO com_permissions",
                "INSERT INTO sys_tenant_resource_templates",
                "INSERT INTO sys_admin_responsibility_catalog",
                "INSERT INTO sys_admin_scoped_duty_catalog",
                "INSERT INTO sys_admin_scoped_duty_capabilities",
                "INSERT INTO sys_admin_app_preset_catalog",
                "INSERT INTO sys_admin_app_preset_duties",
                "INSERT INTO com_resources",
                "INSERT INTO com_admin_scoped_duty_assignments",
                "INSERT INTO com_admin_role_assignments",
                "INSERT INTO com_admin_app_preset_assignments",
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes",
                "INSERT INTO com_role_permissions");
    }
}
