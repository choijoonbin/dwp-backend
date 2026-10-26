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

class TenantFeatureActivationV270MigrationContractTest {
    private static final String FEATURE = "HRIS_HRM_EXIT_RETURN_WORKBENCH";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.exit-return.page",
            "route.hcm.operations.exit-return.data",
            "route.hcm.operations.separation-case.action");

    @Test
    void declaresExactV61CatalogWithDefaultOffAndZeroDerivedAuthority() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V270__declare_hris_hrm013_v61_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode bundle;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v61.generated.json")
                .getInputStream()) {
            bundle = new ObjectMapper().readTree(input);
        }

        assertThat(bundle.path("version").asInt()).isEqualTo(61);
        assertThat(bundle.path("bundleStatus").asText()).isEqualTo("DRAFT");
        assertThat(bundle.path("checksum").asText())
                .isEqualTo("f7cf92a97f2fabf86f257010064a3183acf58f86cc2c9d7692a1555cab55aa24");
        assertThat(bundle.path("capabilities")).hasSize(279);
        assertThat(bundle.path("accessPolicies")).hasSize(24);
        assertThat(bundle.path("entitlementExpressions")).hasSize(17);
        assertThat(bundle.path("predicatePolicies")).hasSize(87);
        assertThat(bundle.path("routes")).hasSize(1157);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "PAGE".equals(route.path("routeKind").asText())).count())
                .isEqualTo(162);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "DATA".equals(route.path("routeKind").asText())).count())
                .isEqualTo(349);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "ACTION".equals(route.path("routeKind").asText())).count())
                .isEqualTo(646);

        Set<String> actualRoutes = StreamSupport.stream(
                        bundle.path("routes").spliterator(), false)
                .map(route -> route.path("routeContractKey").asText())
                .filter(ROUTES::contains)
                .collect(Collectors.toUnmodifiableSet());
        assertThat(actualRoutes).containsExactlyInAnyOrderElementsOf(ROUTES);

        assertThat(sql).contains(
                "'product-surfaces', 61",
                "'" + bundle.path("checksum").asText() + "'",
                "'product-surfaces-v1.bundle-v61.generated.json', 'DRAFT', FALSE",
                "('" + FEATURE + "', 1, 'hcm', 'APP.HCM', 61, 'OFF', 'HRM')",
                "('MANAGE', 'Manage', 1, 1)",
                "'ACTION.HRIS_SEPARATION_CASE', 'ACTION'",
                "'HRIS_DUTY_SEPARATION_MAKER'",
                "'hcm.operations.separation.manage'",
                "'ACTION.HRIS_SEPARATION_CASE:MANAGE'");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO ")).toList())
                .containsExactly(
                        "INSERT INTO com_permissions (code, name, created_by, updated_by)",
                        "INSERT INTO sys_tenant_resource_templates (",
                        "INSERT INTO sys_admin_responsibility_catalog (",
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
                "FROM com_admin_resource_set_members",
                "FROM sys_admin_app_preset_duties",
                "FROM com_principal_resource_grants",
                "FROM auth_product_feature_activation_changes",
                "FROM com_tenant_feature_enrollment_changes");
        assertThat(sql).doesNotContain(
                "INSERT INTO sys_tenant_role_permission_templates",
                "INSERT INTO com_admin_scoped_duty_assignments",
                "INSERT INTO com_admin_resource_set_members",
                "INSERT INTO com_admin_role_assignments",
                "INSERT INTO sys_admin_app_preset_catalog",
                "INSERT INTO sys_admin_app_preset_duties",
                "INSERT INTO com_admin_app_preset_assignments",
                "INSERT INTO com_principal_resource_grants",
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes",
                "INSERT INTO com_role_permissions");
    }
}
