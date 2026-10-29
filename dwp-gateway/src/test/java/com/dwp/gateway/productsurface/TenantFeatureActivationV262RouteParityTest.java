package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV262RouteParityTest {

    private static final Set<String> HRM011_ROUTES = Set.of(
            "route.hcm.operations.contracts-compensation-basis.page",
            "route.hcm.operations.contracts-compensation-basis.data",
            "route.hcm.operations.employment-contract-issue.action");

    @Test
    void v262DefaultOffBindingsMatchTheExactGatewayEnforcementBoundary() throws Exception {
        Path root = repositoryRoot();
        String migration = Files.readString(root.resolve(
                "dwp-auth-server/src/main/resources/db/migration/"
                        + "V262__declare_hris_r7_batch04_hrm011_v53_authorization_seed.sql"));
        assertThat(migration)
                .contains("'HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH'")
                .contains("53, 'OFF'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes")
                .doesNotContain("INSERT INTO com_tenant_feature_enrollment_changes");
        HRM011_ROUTES.forEach(route -> assertThat(migration).contains("'" + route + "'"));
        assertThat(privateSet("TENANT_FEATURE_GOVERNED_ROUTE_KEYS"))
                .containsAll(HRM011_ROUTES);
        assertThat(privateSet("HRM011_ENTRY_QUERY_ROUTE_KEYS"))
                .containsExactlyInAnyOrder(
                        "route.hcm.operations.contracts-compensation-basis.page",
                        "route.hcm.operations.contracts-compensation-basis.data");
        assertThat(privateSet("HRM011_POPULATION_KINDS"))
                .containsExactlyInAnyOrder(
                        "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT");
        assertThat(privateMap("HRM011_FIELD_POLICY_BY_ROUTE"))
                .containsEntry("route.hcm.operations.contracts-compensation-basis.page",
                        "HRM-AUTH-007")
                .containsEntry("route.hcm.operations.contracts-compensation-basis.data",
                        "HRM-AUTH-007")
                .containsEntry("route.hcm.operations.employment-contract-issue.action",
                        "HRM-AUTH-015");

        GeneratedProductRouteCatalog previous = catalog(root, 52);
        GeneratedProductRouteCatalog current = catalog(root, 53);
        assertThat(previous.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .doesNotContainAnyElementsOf(HRM011_ROUTES);
        assertThat(current.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsAll(HRM011_ROUTES);

        GeneratedProductRouteCatalog.Route action = current.routesForTesting().stream()
                .filter(route -> "route.hcm.operations.employment-contract-issue.action"
                        .equals(route.routeContractKey()))
                .findFirst()
                .orElseThrow();
        assertThat(action.stateChanging()).isTrue();
        assertThat(action.highRiskStepUp()).isTrue();
        assertThat(action.commandBoundStepUp()).isTrue();

        var bundle = new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v53.json").toFile());
        com.fasterxml.jackson.databind.JsonNode actionContract = null;
        for (var routeNode : bundle.path("routes")) {
            if ("route.hcm.operations.employment-contract-issue.action".equals(
                    routeNode.path("routeContractKey").asText())) {
                actionContract = routeNode;
                break;
            }
        }
        assertThat(actionContract).isNotNull();
        var binding = actionContract.path("stepUpCommandBindings").path(0);
        assertThat(binding.path("targetType").asText()).isEqualTo("WORK_RELATIONSHIP");
        assertThat(binding.path("targetIdBodyFields").path(0).asText())
                .isEqualTo("relationshipPublicId");
        assertThat(binding.path("expectedObjectVersionSource").asText())
                .isEqualTo("COMMAND_HEADER");
        assertThat(binding.path("expectedObjectVersionName").asText())
                .isEqualTo("X-DWP-Expected-Object-Version");
        assertThat(binding.path("ownerServiceKey").asText()).isEqualTo("people");
        assertThat(binding.path("audience").asText()).isEqualTo("dwp-people-server");
    }

    private static Set<String> privateSet(String name) throws Exception {
        Field field = Class.forName("com.dwp.gateway.filter.ProductSurfaceRoutePolicies")
                .getDeclaredField(name);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> value = (Set<String>) field.get(null);
        return value;
    }

    private static Map<String, String> privateMap(String name) throws Exception {
        Field field = Class.forName("com.dwp.gateway.filter.ProductSurfaceRoutePolicies")
                .getDeclaredField(name);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> value = (Map<String, String>) field.get(null);
        return value;
    }

    private static GeneratedProductRouteCatalog catalog(Path root, int version) {
        return new GeneratedProductRouteCatalog(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new org.springframework.core.io.FileSystemResource(
                        root.resolve("contracts/product-authorization/"
                                + "product-surfaces-v1.bundle-v" + version + ".json")));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("dwp-auth-server"))) return current;
        return current.getParent();
    }
}
