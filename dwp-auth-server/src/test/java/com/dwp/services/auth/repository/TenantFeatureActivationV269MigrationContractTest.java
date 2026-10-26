package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV269MigrationContractTest {
    private static final String FEATURE = "HRIS_INTEGRATION_CONTROL_PLANE";
    private static final List<String> ROUTES = List.of(
            "route.hcm.configuration.settings-integrations.page",
            "route.hcm.configuration.dwp-integrations.data",
            "route.hcm.configuration.connector-catalog.data",
            "route.hcm.configuration.connector-definition-create.action",
            "route.hcm.configuration.connector-test.action",
            "route.hcm.configuration.connector-mapping-dry-run.action",
            "route.hcm.configuration.connector-execution-create.action",
            "route.hcm.configuration.connector-execution-detail.data",
            "route.hcm.configuration.connector-definition-validate.action",
            "route.hcm.configuration.connector-definition-submit.action",
            "route.hcm.configuration.connector-definition-approve.action",
            "route.hcm.configuration.connector-definition-activate.action",
            "route.hcm.configuration.connector-mapping-version-create.action",
            "route.hcm.configuration.connector-mapping-version-validate.action",
            "route.hcm.configuration.connector-mapping-version-submit.action",
            "route.hcm.configuration.connector-mapping-version-approve.action",
            "route.hcm.configuration.connector-mapping-version-activate.action",
            "route.hcm.configuration.connector-execution-reconcile.action",
            "route.hcm.configuration.connector-execution-retry.action",
            "route.hcm.configuration.connector-execution-cancel.action");

    @Test
    void declaresExactV60CatalogWithDefaultOffAndZeroDerivedAuthority() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V269__declare_hris_sys008_v60_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode bundle;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v60.generated.json")
                .getInputStream()) {
            bundle = new ObjectMapper().readTree(input);
        }

        assertThat(bundle.path("version").asInt()).isEqualTo(60);
        assertThat(bundle.path("checksum").asText())
                .isEqualTo("405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c");
        assertThat(bundle.path("capabilities")).hasSize(278);
        assertThat(bundle.path("routes")).hasSize(1154);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "PAGE".equals(route.path("routeKind").asText())).count())
                .isEqualTo(161);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "DATA".equals(route.path("routeKind").asText())).count())
                .isEqualTo(348);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "ACTION".equals(route.path("routeKind").asText())).count())
                .isEqualTo(645);

        Set<String> actualRoutes = StreamSupport.stream(
                        bundle.path("routes").spliterator(), false)
                .map(route -> route.path("routeContractKey").asText())
                .filter(ROUTES::contains)
                .collect(Collectors.toUnmodifiableSet());
        assertThat(actualRoutes).containsExactlyInAnyOrderElementsOf(ROUTES);

        assertThat(sql).contains(
                "'product-surfaces', 60",
                "'" + bundle.path("checksum").asText() + "'",
                "'product-surfaces-v1.bundle-v60.generated.json', 'DRAFT', FALSE",
                "('" + FEATURE + "', 1, 'hcm', 'APP.HCM', 60, 'OFF', 'SYS')",
                "('UPDATE', 'Update', 1, 1)",
                "('EXECUTE', 'Execute', 1, 1)",
                "'CONFIG.HRIS_INTEGRATION', 'CONFIG'",
                "'ACTION.HRIS_INTEGRATION_CONTROL_PLANE', 'ACTION'",
                "'HRIS_DUTY_INTEGRATION_AUTHOR'",
                "'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR'",
                "'HRIS_INTEGRATION_AUTHOR'",
                "'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR'",
                "'hcm.integration.definition.update'",
                "'CONFIG.HRIS_INTEGRATION:UPDATE'",
                "'hcm.integration.control-plane.execute'",
                "'ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE'");
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
            assertThat(sql.split("'" + route + "'", -1)).as(route).hasSize(5);
        }
        assertThat(sql).contains(
                "FROM sys_tenant_role_permission_templates",
                "FROM com_role_permissions",
                "FROM com_admin_scoped_duty_assignments",
                "FROM com_admin_resource_set_members",
                "FROM com_admin_app_preset_assignments",
                "FROM com_principal_resource_grants",
                "FROM auth_product_feature_activation_changes",
                "FROM com_tenant_feature_enrollment_changes");
        assertThat(sql).doesNotContain(
                "INSERT INTO sys_tenant_role_permission_templates",
                "INSERT INTO com_admin_scoped_duty_assignments",
                "INSERT INTO com_admin_resource_set_members",
                "INSERT INTO com_admin_role_assignments",
                "INSERT INTO com_admin_app_preset_assignments",
                "INSERT INTO com_principal_resource_grants",
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes",
                "INSERT INTO com_role_permissions",
                "V334__");
    }
}
