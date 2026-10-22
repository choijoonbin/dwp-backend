package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV267RouteParityTest {

    private static final Set<String> PAY004_ROUTES = Set.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data");

    @Test
    void v267DefaultOffBindingsMatchTheExactGatewayAndV58ProjectionBoundary()
            throws Exception {
        Path root = repositoryRoot();
        Path migrationPath = root.resolve("dwp-auth-server/src/main/resources/db/migration/"
                + "V267__declare_hris_pay004_v58_authorization_seed.sql");
        assertThat(migrationPath).isRegularFile();
        String migration = Files.readString(migrationPath);
        assertThat(migration)
                .contains("'HRIS_PAY_RETIREMENT_WORKBENCH'", "58, 'OFF'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes",
                        "INSERT INTO com_tenant_feature_enrollment_changes");
        PAY004_ROUTES.forEach(route -> assertThat(migration).contains("'" + route + "'"));

        assertThat(privateSet("TENANT_FEATURE_GOVERNED_ROUTE_KEYS"))
                .containsAll(PAY004_ROUTES);
        assertThat(privateSet("PAY004_RESULT_ROUTE_KEYS"))
                .containsExactlyInAnyOrderElementsOf(PAY004_ROUTES);
        assertThat(privateMap("PAY004_FIELD_POLICY_BY_ROUTE"))
                .containsOnly(
                        Map.entry("route.hcm.operations.pay-retirement.page",
                                "pay004.iaq-049.fields.v1"),
                        Map.entry("route.hcm.operations.pay-retirement.data",
                                "pay004.iaq-049.fields.v1"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode previous = mapper.readTree(root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v57.json").toFile());
        JsonNode current = mapper.readTree(root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v58.json").toFile());
        Map<String, JsonNode> oldRoutes = routes(previous);
        Map<String, JsonNode> newRoutes = routes(current);
        Set<String> added = new HashSet<>(newRoutes.keySet());
        added.removeAll(oldRoutes.keySet());
        assertThat(added).containsExactlyInAnyOrderElementsOf(PAY004_ROUTES);
        oldRoutes.forEach((key, value) -> assertThat(newRoutes.get(key)).as(key).isEqualTo(value));

        GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                mapper, new FileSystemResource(root.resolve(
                        "contracts/product-authorization/product-surfaces-v1.bundle-v58.json")));
        GeneratedProductRouteCatalog.Match match = catalog.match(
                "GET", "/api/payroll/v1/workbench/pay-retirement");
        assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.routes()).hasSize(2)
                .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                .containsExactlyInAnyOrderElementsOf(PAY004_ROUTES);

        for (String routeKey : PAY004_ROUTES) {
            JsonNode route = newRoutes.get(routeKey);
            assertThat(route.path("routeKind").asText())
                    .isIn("PAGE", "DATA");
            assertThat(route.has("stepUpCommandBindings")).isFalse();
            JsonNode profile = route.path("accessProfiles").path(0);
            assertThat(profile.path("targetBindingKinds").path(0).asText())
                    .isEqualTo("TARGET_POPULATION");
            assertThat(profile.path("responseProjectionBindings").path(0)
                    .path("projectionPolicyKey").asText())
                    .isEqualTo("pay004.iaq-049.fields.v1");
            assertThat(profile.path("responseProjectionBindings").path(0)
                    .path("responseSchemaKey").asText())
                    .isEqualTo("Pay004RetirementProjectionV1");
        }
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

    private static Map<String, JsonNode> routes(JsonNode bundle) {
        Map<String, JsonNode> routes = new HashMap<>();
        bundle.path("routes").forEach(route ->
                routes.put(route.path("routeContractKey").asText(), route));
        return routes;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("dwp-auth-server"))
                ? current : current.getParent();
    }
}
