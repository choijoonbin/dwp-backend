package com.dwp.services.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceTelemetryDimensionRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsTheExactGeneratedRegistryV3ProjectionIncludingHcm() {
        ProductSurfaceTelemetryDimensionRegistry registry =
                new ProductSurfaceTelemetryDimensionRegistry(objectMapper);

        assertThatCode(() -> registry.validate(
                ProductSurfaceTelemetryServiceTest.routeDenied(
                        "approvals", "approvals.admin", "approvals.admin.operations")))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(
                ProductSurfaceTelemetryServiceTest.routeDenied(
                        "services", "services.work", "services.work.home")))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(
                ProductSurfaceTelemetryServiceTest.routeDenied(
                        "hcm", "hcm.management", "hcm.management.integration")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAllOneHundredTwentySevenGeneratedRoutesAndRejectsEveryCrossMapping()
            throws Exception {
        ProductSurfaceTelemetryDimensionRegistry registry =
                new ProductSurfaceTelemetryDimensionRegistry(objectMapper);
        List<RouteMapping> mappings = mappings(projection());

        assertThat(mappings).hasSize(127);
        mappings.forEach(mapping -> assertThatCode(() -> registry.validate(
                ProductSurfaceTelemetryServiceTest.routeDenied(
                        mapping.product(), mapping.surface(), mapping.route())))
                .doesNotThrowAnyException());
        for (RouteMapping source : mappings) {
            for (RouteMapping target : mappings) {
                if (source.product().equals(target.product())
                        && source.surface().equals(target.surface())) {
                    continue;
                }
                assertThatThrownBy(() -> registry.validate(
                        ProductSurfaceTelemetryServiceTest.routeDenied(
                                target.product(), target.surface(), source.route())))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void acceptsOnlyCanonicalHcmTaskAndScopeDimensions() {
        ProductSurfaceTelemetryDimensionRegistry registry =
                new ProductSurfaceTelemetryDimensionRegistry(objectMapper);

        assertThatCode(() -> registry.validate(task(
                "hcm", "hcm.personal", ProductSurfaceTelemetryDtos.TaskKind.WORK)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(task(
                "hcm", "hcm.team", ProductSurfaceTelemetryDtos.TaskKind.REVIEW)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(task(
                "hcm", "hcm.operations", ProductSurfaceTelemetryDtos.TaskKind.OPERATIONS)))
                .doesNotThrowAnyException();
        for (ProductSurfaceTelemetryDtos.TaskKind task : List.of(
                ProductSurfaceTelemetryDtos.TaskKind.OPERATIONS,
                ProductSurfaceTelemetryDtos.TaskKind.ADMINISTRATION,
                ProductSurfaceTelemetryDtos.TaskKind.CONFIGURATION,
                ProductSurfaceTelemetryDtos.TaskKind.DESIGN,
                ProductSurfaceTelemetryDtos.TaskKind.INTEGRATION,
                ProductSurfaceTelemetryDtos.TaskKind.REPORTING)) {
            assertThatCode(() -> registry.validate(task("hcm", "hcm.management", task)))
                    .doesNotThrowAnyException();
        }

        assertThatCode(() -> registry.validate(scope(
                "hcm", "hcm.personal", ProductSurfaceTelemetryDtos.ScopeKind.SELF)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(scope(
                "hcm", "hcm.team", ProductSurfaceTelemetryDtos.ScopeKind.TARGET_POPULATION)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(scope(
                "hcm", "hcm.operations", ProductSurfaceTelemetryDtos.ScopeKind.SUPPORT_SESSION)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(scope(
                "hcm", "hcm.management", ProductSurfaceTelemetryDtos.ScopeKind.POLICY_NODE)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> registry.validate(task(
                "hcm", "hcm.personal", ProductSurfaceTelemetryDtos.TaskKind.OPERATIONS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
        assertThatThrownBy(() -> registry.validate(task(
                "hcm", "hcm.operations", ProductSurfaceTelemetryDtos.TaskKind.REVIEW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
        assertThatThrownBy(() -> registry.validate(scope(
                "hcm", "hcm.personal", ProductSurfaceTelemetryDtos.ScopeKind.TEAM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> registry.validate(scope(
                "hcm", "hcm.management", ProductSurfaceTelemetryDtos.ScopeKind.SUPPORT_SESSION)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void acceptsEveryGeneratedTaskAndScopeDimension() throws Exception {
        ProductSurfaceTelemetryDimensionRegistry registry =
                new ProductSurfaceTelemetryDimensionRegistry(objectMapper);
        ObjectNode projection = projection();

        projection.path("products").forEach(product ->
                product.path("surfaces").forEach(surface -> {
                    String productKey = product.path("productKey").asText();
                    String surfaceKey = surface.path("surfaceKey").asText();
                    surface.path("taskKinds").forEach(value ->
                            assertThatCode(() -> registry.validate(task(
                                    productKey,
                                    surfaceKey,
                                    ProductSurfaceTelemetryDtos.TaskKind.valueOf(value.asText()))))
                                    .doesNotThrowAnyException());
                    surface.path("scopeKinds").forEach(value ->
                            assertThatCode(() -> registry.validate(scope(
                                    productKey,
                                    surfaceKey,
                                    ProductSurfaceTelemetryDtos.ScopeKind.valueOf(value.asText()))))
                                    .doesNotThrowAnyException());
                }));
    }

    @Test
    void failsStartupWhenTheGeneratedProjectionIsMissing() {
        assertThatThrownBy(() -> new ProductSurfaceTelemetryDimensionRegistry(
                objectMapper,
                new ClassPathResource(
                        "product-authorization/missing-telemetry-dimensions-v3.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or unreadable");
    }

    @Test
    void failsStartupWhenTheGeneratedProjectionIsUnreadable() {
        ByteArrayResource corrupt = new ByteArrayResource(
                "{not-json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new ProductSurfaceTelemetryDimensionRegistry(
                objectMapper, corrupt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or unreadable");
    }

    @Test
    void failsStartupWhenTheGeneratedProjectionChecksumIsCorrupt() throws Exception {
        ObjectNode projection = projection();
        ((ObjectNode) projection.path("products").get(0)).put("productKey", "hcm");

        assertThatThrownBy(() -> new ProductSurfaceTelemetryDimensionRegistry(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(projection))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void failsStartupWhenTheProjectionReferencesRegistryV4() throws Exception {
        ObjectNode projection = projection();
        ((ObjectNode) projection.path("registryRef")).put("version", 4);

        assertThatThrownBy(() -> new ProductSurfaceTelemetryDimensionRegistry(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(projection))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pinned to registry v3");
    }

    private ObjectNode projection() throws Exception {
        try (InputStream input = new ClassPathResource(
                ProductSurfaceTelemetryDimensionRegistry.RESOURCE).getInputStream()) {
            return (ObjectNode) objectMapper.readTree(input);
        }
    }

    private List<RouteMapping> mappings(ObjectNode projection) {
        List<RouteMapping> result = new ArrayList<>();
        projection.path("products").forEach(product ->
                product.path("surfaces").forEach(surface ->
                        surface.path("routeIds").forEach(route -> result.add(new RouteMapping(
                                product.path("productKey").asText(),
                                surface.path("surfaceKey").asText(),
                                route.asText())))));
        return List.copyOf(result);
    }

    private ProductSurfaceTelemetryDtos.EventRequest task(
            String product,
            String surface,
            ProductSurfaceTelemetryDtos.TaskKind task) {
        return new ProductSurfaceTelemetryDtos.EventRequest(
                1,
                "surface.task.started",
                product,
                surface,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                task,
                null,
                null,
                java.util.UUID.fromString("d2e63316-8564-4d8c-bd02-eaede882f982"));
    }

    private ProductSurfaceTelemetryDtos.EventRequest scope(
            String product,
            String surface,
            ProductSurfaceTelemetryDtos.ScopeKind scope) {
        return new ProductSurfaceTelemetryDtos.EventRequest(
                1,
                "surface.scope.switch.started",
                product,
                surface,
                null,
                null,
                null,
                null,
                scope,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.UUID.fromString("d2e63316-8564-4d8c-bd02-eaede882f982"));
    }

    private record RouteMapping(String product, String surface, String route) {
    }
}
