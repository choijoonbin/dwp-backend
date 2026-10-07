package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedProductRouteCatalogProviderLifecycleTest
        extends GeneratedProductRouteCatalogTestSupport {

    @Test
    void providerLifecycleCancellationIsPresentInThePublishedContract() throws IOException {
        Path path = Path.of("contracts/openapi/provider.json");
        if (!Files.exists(path)) path = Path.of("../contracts/openapi/provider.json");
        JsonNode document = objectMapper.readTree(Files.readAllBytes(path));
        JsonNode operation = document.path("paths")
                .path("/v1/admin/resource-governance/lifecycle-requests/{requestId}/cancel")
                .path("post");

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("operationId").asText()).isEqualTo("cancelLifecycleRequest");
    }

    @Test
    void keepsProviderLifecycleCancellationOutsideTheProductBoundary() {
        assertThat(catalog(31)
                        .match(
                                "POST",
                                "/api/provider/v1/admin/resource-governance/lifecycle-requests/"
                                        + UUID.randomUUID()
                                        + "/cancel")
                        .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
    }
}
