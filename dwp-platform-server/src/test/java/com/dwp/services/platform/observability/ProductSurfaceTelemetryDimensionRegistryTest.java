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
    void loadsTheExactGeneratedRegistryV2Projection() {
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
    }

    @Test
    void acceptsAllThirtyThreeGeneratedRoutesAndRejectsEveryCrossMapping()
            throws Exception {
        ProductSurfaceTelemetryDimensionRegistry registry =
                new ProductSurfaceTelemetryDimensionRegistry(objectMapper);
        List<RouteMapping> mappings = mappings(projection());

        assertThat(mappings).hasSize(33);
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
    void failsStartupWhenTheGeneratedProjectionIsMissing() {
        assertThatThrownBy(() -> new ProductSurfaceTelemetryDimensionRegistry(
                objectMapper,
                new ClassPathResource(
                        "product-authorization/missing-telemetry-dimensions-v2.json")))
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
    void failsStartupWhenTheProjectionReferencesRegistryV3() throws Exception {
        ObjectNode projection = projection();
        ((ObjectNode) projection.path("registryRef")).put("version", 3);

        assertThatThrownBy(() -> new ProductSurfaceTelemetryDimensionRegistry(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(projection))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pinned to registry v2");
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

    private record RouteMapping(String product, String surface, String route) {
    }
}
