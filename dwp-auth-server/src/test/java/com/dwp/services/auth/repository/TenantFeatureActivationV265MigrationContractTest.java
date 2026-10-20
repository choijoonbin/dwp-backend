package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV265MigrationContractTest {
    private static final String FEATURE = "HRIS_AUTOMATION_CONTROL_PLANE";
    private static final List<String> ROUTES = List.of(
            "route.hcm.configuration.automation-catalog.data",
            "route.hcm.configuration.automation-definition-create.action",
            "route.hcm.configuration.automation-run-queue.action",
            "route.hcm.configuration.automation-run-detail.data",
            "route.hcm.configuration.automation-run-retry.action",
            "route.hcm.configuration.automation-version-create.action",
            "route.hcm.configuration.automation-version-validate.action",
            "route.hcm.configuration.automation-version-submit.action",
            "route.hcm.configuration.automation-version-approve.action",
            "route.hcm.configuration.automation-version-activate.action",
            "route.hcm.configuration.automation-schedule-create.action",
            "route.hcm.configuration.automation-schedule-submit.action",
            "route.hcm.configuration.automation-schedule-approve.action",
            "route.hcm.configuration.automation-schedule-activate.action",
            "route.hcm.configuration.automation-schedule-suspend.action",
            "route.hcm.configuration.automation-run-cancel.action");

    @Test
    void onlyTheDraftCarrierAndDefaultOffSixteenAutomationRoutesAreDeclared() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V265__declare_hris_sys006_v56_authorization_seed.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String checksum;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v56.generated.json").getInputStream()) {
            checksum = new ObjectMapper().readTree(input).get("checksum").textValue();
        }
        assertThat(sql).contains("'product-surfaces', 56", "'" + checksum + "'", "'DRAFT', FALSE",
                "('" + FEATURE + "', 1, 'hcm', 'APP.HCM', 56, 'OFF', 'SYS')");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO ")).toList()).containsExactly(
                "INSERT INTO auth_product_authorization_seed_release (",
                "INSERT INTO sys_product_feature_activation_policies (",
                "INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)",
                "INSERT INTO sys_product_feature_route_bindings (");
        for (String route : ROUTES) {
            assertThat(sql.split("'" + route + "'", -1)).as(route).hasSize(3);
        }
        assertThat(sql.split("'DATA'", -1)).hasSize(3);
        assertThat(sql.split("'ACTION'", -1)).hasSize(15);
        assertThat(sql).doesNotContain("'PAGE'", "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes", "INSERT INTO com_role_permissions",
                "INSERT INTO sys_tenant_role_permission_templates");
    }
}
