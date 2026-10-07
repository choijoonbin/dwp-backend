package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class GeneratedProductRouteCatalogTestSupport {

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final GeneratedProductRouteCatalog catalog = catalog(3);

    protected GeneratedProductRouteCatalog catalog(int version) {
        return new GeneratedProductRouteCatalog(
                objectMapper,
                new FileSystemResource("../contracts/product-authorization/"
                        + "product-surfaces-v1.bundle-v" + version + ".json"));
    }

    protected ObjectNode bundle(int version) throws IOException {
        try (var input = new FileSystemResource(
                "../contracts/product-authorization/product-surfaces-v1.bundle-v"
                        + version + ".json").getInputStream()) {
            return (ObjectNode) objectMapper.readTree(input);
        }
    }

    protected Set<String> platformV21OpenApiWorkplaceBindings() throws IOException {
        // v21 closed against this exact OpenAPI inventory. Later platform additions must not
        // rewrite the historical registry assertion.
        var resource = getClass().getResourceAsStream(
                "/product-authorization/platform-workplace-openapi-v21.bindings.txt");
        if (resource == null) {
            throw new IOException("Frozen v21 Workplace OpenAPI inventory is absent.");
        }
        try (var lines = new BufferedReader(new InputStreamReader(
                resource, StandardCharsets.UTF_8)).lines()) {
            return lines.filter(line -> !line.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    protected Set<String> platformOpenApiWorkplaceBindings() throws IOException {
        Path path = Path.of("contracts/openapi/platform.json");
        if (!Files.exists(path)) path = Path.of("../contracts/openapi/platform.json");
        JsonNode document = objectMapper.readTree(Files.readAllBytes(path));
        Set<String> methods = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
        Set<String> result = new HashSet<>();
        document.path("paths").properties().forEach(pathEntry -> {
            String servicePath = pathEntry.getKey();
            if (!(servicePath.startsWith("/v1/workplace/")
                    || servicePath.startsWith("/v1/admin/workplace/")
                    || servicePath.startsWith("/v1/device/workplace/"))) {
                return;
            }
            pathEntry.getValue().properties().forEach(operation -> {
                String method = operation.getKey().toUpperCase(java.util.Locale.ROOT);
                if (methods.contains(method)) {
                    result.add(method + " /api/platform" + servicePath);
                }
            });
        });
        return Set.copyOf(result);
    }

    protected Set<String> routeKeys(ObjectNode bundle) {
        Set<String> result = new HashSet<>();
        bundle.withArray("routes").forEach(route ->
                result.add(route.path("routeContractKey").asText()));
        return result;
    }

    protected void assertRoute(
            GeneratedProductRouteCatalog source,
            String method,
            String path,
            String routeKey,
            boolean highRisk) {
        var match = source.match(method, path);
        assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.uniqueRoute()).satisfies(route -> {
            assertThat(route.routeContractKey()).isEqualTo(routeKey);
            assertThat(route.highRiskStepUp()).isEqualTo(highRisk);
        });
    }

    protected void assertInvalid(ObjectNode bundle) throws Exception {
        byte[] value = objectMapper.writeValueAsBytes(bundle);
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper, new ByteArrayResource(value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority endpoint");
    }
}
