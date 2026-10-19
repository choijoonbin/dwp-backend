package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV263RouteParityTest {

    private static final Set<String> PAY003_ROUTES = Set.of(
            "route.hcm.configuration.statutory.page",
            "route.hcm.configuration.statutory.data",
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data",
            "route.hcm.operations.country-pack-activate.action");

    @Test
    void v263DefaultOffBindingsMatchTheExactGatewayEnforcementBoundary() throws Exception {
        Path root = repositoryRoot();
        String migration = Files.readString(root.resolve(
                "dwp-auth-server/src/main/resources/db/migration/"
                        + "V263__declare_hris_r7_batch04_pay003_v54_authorization_seed.sql"));
        assertThat(migration)
                .contains("'HRIS_PAYROLL_STATUTORY_INSURANCE'")
                .contains("54, 'OFF'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes")
                .doesNotContain("INSERT INTO com_tenant_feature_enrollment_changes");
        PAY003_ROUTES.forEach(route -> assertThat(migration).contains("'" + route + "'"));
        assertThat(privateSet("TENANT_FEATURE_GOVERNED_ROUTE_KEYS"))
                .containsAll(PAY003_ROUTES);
        assertThat(privateSet("PAY003_CONFIGURATION_ROUTE_KEYS"))
                .containsExactlyInAnyOrder(
                        "route.hcm.configuration.statutory.page",
                        "route.hcm.configuration.statutory.data");
        assertThat(privateSet("PAY003_RESULT_ROUTE_KEYS"))
                .containsExactlyInAnyOrder(
                        "route.hcm.operations.pay-tax-social-insurance.page",
                        "route.hcm.operations.pay-tax-social-insurance.data");
        assertThat(privateMap("PAY003_FIELD_POLICY_BY_ROUTE"))
                .containsEntry("route.hcm.configuration.statutory.page",
                        "pay003.iaq-026.fields.v1")
                .containsEntry("route.hcm.configuration.statutory.data",
                        "pay003.iaq-026.fields.v1")
                .containsEntry("route.hcm.operations.pay-tax-social-insurance.page",
                        "pay003.iaq-048.fields.v1")
                .containsEntry("route.hcm.operations.pay-tax-social-insurance.data",
                        "pay003.iaq-048.fields.v1");

        GeneratedProductRouteCatalog previous = catalog(root, 53);
        GeneratedProductRouteCatalog current = catalog(root, 54);
        assertThat(previous.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .doesNotContainAnyElementsOf(PAY003_ROUTES);
        assertThat(current.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsAll(PAY003_ROUTES);

        GeneratedProductRouteCatalog.Route action = current.routesForTesting().stream()
                .filter(route -> "route.hcm.operations.country-pack-activate.action"
                        .equals(route.routeContractKey()))
                .findFirst().orElseThrow();
        assertThat(action.stateChanging()).isTrue();
        assertThat(action.highRiskStepUp()).isTrue();
        assertThat(action.commandBoundStepUp()).isTrue();

        var bundle = new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v54.json").toFile());
        var actionContract = java.util.stream.StreamSupport.stream(
                        bundle.path("routes").spliterator(), false)
                .filter(route -> "route.hcm.operations.country-pack-activate.action".equals(
                        route.path("routeContractKey").asText()))
                .findFirst().orElseThrow();
        var binding = actionContract.path("stepUpCommandBindings").path(0);
        assertThat(binding.path("targetType").asText()).isEqualTo("COUNTRY_PACK");
        assertThat(binding.path("targetIdPathParameter").asText()).isEqualTo("packId");
        assertThat(binding.path("expectedObjectVersionSource").asText())
                .isEqualTo("COMMAND_HEADER");
        assertThat(binding.path("expectedObjectVersionName").asText())
                .isEqualTo("X-DWP-Expected-Object-Version");
        assertThat(binding.path("ownerServiceKey").asText()).isEqualTo("payroll");
        assertThat(binding.path("audience").asText()).isEqualTo("dwp-payroll-server");
    }

    private static Set<String> privateSet(String name) throws Exception {
        Field field = ProductSurfaceDecisionContextFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> value = (Set<String>) field.get(null);
        return value;
    }

    private static Map<String, String> privateMap(String name) throws Exception {
        Field field = ProductSurfaceDecisionContextFilter.class.getDeclaredField(name);
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
