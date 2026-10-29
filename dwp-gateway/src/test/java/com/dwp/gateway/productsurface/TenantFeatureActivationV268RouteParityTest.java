package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration gate: consumes the Auth-owned V268 and generated v59 artifacts. */
class TenantFeatureActivationV268RouteParityTest {

    private static final String FEATURE = "HRIS_PER_TEAM_PERFORMANCE_FEEDBACK";
    private static final Set<String> PER008_ROUTES = Set.of(
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
    void v268DefaultOffBindingsMatchTheExactGatewayAndV59ProjectionBoundary()
            throws Exception {
        Path root = repositoryRoot();
        Path migrationPath = root.resolve("dwp-auth-server/src/main/resources/db/migration/"
                + "V268__declare_hris_per008_v59_authorization_seed.sql");
        Path v59Path = root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v59.json");
        assertThat(migrationPath).as("Auth-owned V268 must be integrated before route parity")
                .isRegularFile();
        assertThat(v59Path).as("generated product-authorization v59 must be integrated")
                .isRegularFile();
        String migration = Files.readString(migrationPath);
        String normalizedMigration = migration.replaceAll("\\s+", " ");
        assertThat(migration)
                .contains("'" + FEATURE + "'", "'APP.HCM'", "'OFF'", "'PER'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes",
                        "INSERT INTO com_tenant_feature_enrollment_changes");
        assertThat(normalizedMigration)
                .contains("minimum_bundle_version = 59", "baseline_state = 'OFF'",
                        "automatic_import_enabled = FALSE")
                .containsPattern("feature_key = '" + FEATURE + "'[^;]+<> 9");
        PER008_ROUTES.forEach(route -> assertThat(migration).contains("'" + route + "'"));

        assertThat(privateSet("TENANT_FEATURE_GOVERNED_ROUTE_KEYS"))
                .containsAll(PER008_ROUTES);
        assertThat(privateSet("PER008_ROUTE_KEYS"))
                .containsExactlyInAnyOrderElementsOf(PER008_ROUTES);
        Map<String, String> purposes = privateMap("WAVE2_OWNER_PURPOSE_BY_ROUTE");
        PER008_ROUTES.forEach(route -> assertThat(purposes.get(route)).as(route).isEqualTo(
                route.startsWith("route.hcm.team.performance.")
                        ? "TEAM_MANAGEMENT" : "PERFORMANCE_FEEDBACK"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode previous = mapper.readTree(root.resolve(
                "contracts/product-authorization/product-surfaces-v1.bundle-v58.json").toFile());
        JsonNode current = mapper.readTree(v59Path.toFile());
        Map<String, JsonNode> oldRoutes = routes(previous);
        Map<String, JsonNode> newRoutes = routes(current);
        Set<String> added = new HashSet<>(newRoutes.keySet());
        added.removeAll(oldRoutes.keySet());
        assertThat(added).containsExactlyInAnyOrderElementsOf(PER008_ROUTES);
        oldRoutes.forEach((key, value) -> assertThat(newRoutes.get(key)).as(key).isEqualTo(value));
        List<String> routeKinds = newRoutes.values().stream()
                .filter(route -> PER008_ROUTES.contains(
                        route.path("routeContractKey").asText()))
                .map(route -> route.path("routeKind").asText())
                .toList();
        assertThat(routeKinds).hasSize(9);
        assertThat(routeKinds.stream().filter("PAGE"::equals).count()).isEqualTo(2);
        assertThat(routeKinds.stream().filter("DATA"::equals).count()).isEqualTo(5);
        assertThat(routeKinds.stream().filter("ACTION"::equals).count()).isEqualTo(2);

        record Topology(
                String key,
                String kind,
                String surface,
                String method,
                String publicPath,
                String servicePath,
                List<String> profiles) { }
        List<Topology> topologies = List.of(
                new Topology("route.hcm.team.performance.page", "PAGE", "hcm.team", "GET",
                        "/api/people/v1/hris/performance/workbench/team-performance",
                        "/v1/hris/performance/workbench/team-performance",
                        List.of("team-performance-reader")),
                new Topology("route.hcm.team.performance.data", "DATA", "hcm.team", "GET",
                        "/api/people/v1/hris/performance/workbench/team-performance",
                        "/v1/hris/performance/workbench/team-performance",
                        List.of("team-performance-reader")),
                new Topology("route.hcm.personal.performance-feedback.page", "PAGE",
                        "hcm.personal", "GET",
                        "/api/people/v1/hris/performance/feedback-assignments/mine",
                        "/v1/hris/performance/feedback-assignments/mine",
                        List.of("self-performance-reader")),
                new Topology("route.hcm.performance.feedback-request-options.data", "DATA",
                        "hcm.operations", "GET",
                        "/api/people/v1/hris/performance/feedback-request-options",
                        "/v1/hris/performance/feedback-request-options",
                        List.of("manager-feedback-requester", "self-feedback-requester")),
                new Topology("route.hcm.performance.feedback-request-create.action", "ACTION",
                        "hcm.operations", "POST",
                        "/api/people/v1/hris/performance/feedback-requests",
                        "/v1/hris/performance/feedback-requests",
                        List.of("manager-feedback-requester", "self-feedback-requester")),
                new Topology("route.hcm.personal.feedback-assignments-mine.data", "DATA",
                        "hcm.personal", "GET",
                        "/api/people/v1/hris/performance/feedback-assignments/mine",
                        "/v1/hris/performance/feedback-assignments/mine",
                        List.of("self-performance-reader")),
                new Topology("route.hcm.personal.feedback-assignment-detail.data", "DATA",
                        "hcm.personal", "GET",
                        "/api/people/v1/hris/performance/feedback-assignments/"
                                + "{feedbackAssignmentId}",
                        "/v1/hris/performance/feedback-assignments/{feedbackAssignmentId}",
                        List.of("assigned-feedback-reader")),
                new Topology("route.hcm.personal.feedback-response-submit.action", "ACTION",
                        "hcm.personal", "POST",
                        "/api/people/v1/hris/performance/feedback-assignments/"
                                + "{feedbackAssignmentId}/responses",
                        "/v1/hris/performance/feedback-assignments/"
                                + "{feedbackAssignmentId}/responses",
                        List.of("assigned-feedback-provider")),
                new Topology("route.hcm.performance.feedback-release.data", "DATA",
                        "hcm.operations", "GET",
                        "/api/people/v1/hris/performance/feedback-requests/"
                                + "{feedbackRequestId}/release",
                        "/v1/hris/performance/feedback-requests/{feedbackRequestId}/release",
                        List.of("self-feedback-release-reader", "manager-feedback-release-reader",
                                "operator-feedback-release-reader")));
        for (Topology topology : topologies) {
            JsonNode route = newRoutes.get(topology.key());
            assertThat(route).as(topology.key()).isNotNull();
            assertThat(route.path("routeKind").asText()).isEqualTo(topology.kind());
            assertThat(route.path("subject").path("surfaceKey").asText())
                    .isEqualTo(topology.surface());
            JsonNode gateway = route.path("gatewayApiBindings").path(0);
            JsonNode service = route.path("servicePepBindings").path(0);
            assertThat(gateway.path("method").asText()).isEqualTo(topology.method());
            assertThat(gateway.path("path").asText()).isEqualTo(topology.publicPath());
            assertThat(service.path("method").asText()).isEqualTo(topology.method());
            assertThat(service.path("serviceKey").asText()).isEqualTo("people");
            assertThat(service.path("path").asText()).isEqualTo(topology.servicePath());
            assertThat(route.path("accessProfiles").findValuesAsText("profileKey"))
                    .containsExactlyElementsOf(topology.profiles());
        }

        GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                mapper, new FileSystemResource(v59Path));
        for (String path : List.of(
                "/api/people/v1/hris/performance/workbench/team-performance",
                "/api/people/v1/hris/performance/feedback-assignments/mine")) {
            GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
            assertThat(match.routes()).hasSize(2)
                    .extracting(GeneratedProductRouteCatalog.Route::routeKind)
                    .containsExactlyInAnyOrder("PAGE", "DATA");
            assertThat(match.uniqueRoute()).isNotNull()
                    .extracting(GeneratedProductRouteCatalog.Route::routeKind)
                    .isEqualTo("DATA");
        }
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
