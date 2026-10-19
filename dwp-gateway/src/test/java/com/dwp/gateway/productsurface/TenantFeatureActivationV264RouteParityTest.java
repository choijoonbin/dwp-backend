package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration gate: consumes the Auth-owned v55/V264 artifacts, never creates copies. */
class TenantFeatureActivationV264RouteParityTest {
    @Test
    void v264DefaultOffQueryBindingsMatchTheExactFourGatewayRoutes() throws Exception {
        Path root = repositoryRoot();
        Path migrationPath = root.resolve("dwp-auth-server/src/main/resources/db/migration/"
                + "V264__declare_hris_r7_batch04_per004_v55_authorization_seed.sql");
        Path bundlePath = root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v55.json");
        assertThat(migrationPath).as("Auth-owned V264 must be integrated before route parity")
                .isRegularFile();
        assertThat(bundlePath).as("Auth-owned v55 must be integrated before route parity")
                .isRegularFile();
        String migration = Files.readString(migrationPath);
        assertThat(migration).contains("'HRIS_PERFORMANCE_CONFIGURATION'", "55, 'OFF'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes",
                        "INSERT INTO com_tenant_feature_enrollment_changes");
        Per004GatewayRoutes.ROUTE_KEYS.forEach(key -> assertThat(migration).contains("'" + key + "'"));

        var field = ProductSurfaceDecisionContextFilter.class.getDeclaredField(
                "TENANT_FEATURE_GOVERNED_ROUTE_KEYS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> governed = (Set<String>) field.get(null);
        assertThat(governed).containsAll(Per004GatewayRoutes.ROUTE_KEYS);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode previous = mapper.readTree(root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v54.json").toFile());
        JsonNode current = mapper.readTree(bundlePath.toFile());
        Map<String, JsonNode> oldRoutes = routes(previous);
        Map<String, JsonNode> newRoutes = routes(current);
        assertThat(newRoutes.keySet()).containsAll(oldRoutes.keySet());
        var added = new java.util.HashSet<>(newRoutes.keySet());
        added.removeAll(oldRoutes.keySet());
        assertThat(added).containsExactlyInAnyOrderElementsOf(Per004GatewayRoutes.ROUTE_KEYS);
        oldRoutes.forEach((key, value) -> assertThat(newRoutes.get(key)).as(key).isEqualTo(value));

        var catalog = new GeneratedProductRouteCatalog(mapper, new FileSystemResource(bundlePath));
        for (String path : Set.of(Per004GatewayRoutes.FORMS_PATH, Per004GatewayRoutes.SETTINGS_PATH)) {
            var match = catalog.match("GET", path);
            assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.routes()).hasSize(2);
            assertThat(match.routes()).extracting(GeneratedProductRouteCatalog.Route::routeKind)
                    .containsExactlyInAnyOrder("PAGE", "DATA");
            assertThat(match.uniqueRoute()).isNotNull();
            for (var route : match.routes()) {
                var policy = Per004GatewayRoutes.policy(route.routeContractKey());
                assertThat(policy).isNotNull();
                assertThat(policy.path()).isEqualTo(path);
                assertThat(route.stateChanging()).isFalse();
                var contract = newRoutes.get(route.routeContractKey());
                assertThat(contract.path("gatewayApiBindings").size()).isEqualTo(1);
                assertThat(contract.path("gatewayApiBindings").get(0).path("method").asText())
                        .isEqualTo("GET");
                assertThat(contract.path("servicePepBindings").get(0).path("serviceKey").asText())
                        .isEqualTo("people");
                assertThat(contract.path("servicePepBindings").get(0).path("path").asText())
                        .isEqualTo(path.substring("/api/people".length()));
                var profile = contract.path("accessProfiles").get(0);
                assertThat(profile.path("targetBindingKinds").get(0).asText())
                        .isEqualTo(policy.ownerScopeKind());
                assertThat(profile.path("responseProjectionBindings").get(0)
                        .path("projectionPolicyKey").asText()).isEqualTo(policy.fieldPolicyKey());
            }
        }
        String configuration = Files.readString(root.resolve(
                "dwp-gateway/src/main/resources/application.yml"));
        assertThat(configuration).contains("Path=/api/people/**", "StripPrefix=2")
                .doesNotContain(Per004GatewayRoutes.FORMS_PATH, Per004GatewayRoutes.SETTINGS_PATH);
    }

    private Map<String, JsonNode> routes(JsonNode bundle) {
        Map<String, JsonNode> routes = new HashMap<>();
        bundle.path("routes").forEach(route -> routes.put(route.path("routeContractKey").asText(), route));
        return routes;
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("dwp-auth-server")) ? current : current.getParent();
    }
}
