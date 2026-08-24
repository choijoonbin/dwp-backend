package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the canonical governed bindings to both owner and exported Gateway contracts. */
class AssignedAccessReviewOpenApiExportContractTest {

    private static final Map<String, Binding> ROUTES = Map.of(
            "route.context.work__work.review-detail.data",
            new Binding(
                    "get",
                    "/api/auth/work/access-review-items/{workItemRef}",
                    "/auth/work/access-review-items/{workItemRef}"),
            "route.context.work__work.review-decision.action",
            new Binding(
                    "put",
                    "/api/auth/work/access-review-items/{workItemRef}/decision",
                    "/auth/work/access-review-items/{workItemRef}/decision"));

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalBindingsExistInOwnerAndGatewaySnapshots() throws Exception {
        Path root = repositoryRoot();
        JsonNode registry = read(root, "contracts/product-authorization/product-surfaces-v1.json");
        JsonNode auth = read(root, "contracts/openapi/auth.json");
        JsonNode gateway = read(root, "contracts/openapi/gateway-public.json");

        ROUTES.forEach((key, expected) -> {
            JsonNode route = StreamSupport.stream(registry.path("routes").spliterator(), false)
                    .filter(candidate -> key.equals(candidate.path("routeContractKey").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing governed route " + key));
            assertBinding(route.path("gatewayApiBindings").path(0), expected.publicPath());
            assertBinding(route.path("servicePepBindings").path(0), expected.servicePath());
            assertThat(route.path("servicePepBindings").path(0).path("serviceKey").asText())
                    .isEqualTo("auth");
            assertThat(auth.path("paths").path(expected.servicePath()).has(expected.method()))
                    .as("Auth owner %s %s", expected.method(), expected.servicePath())
                    .isTrue();
            assertThat(gateway.path("paths").path(expected.publicPath()).has(expected.method()))
                    .as("Gateway export %s %s", expected.method(), expected.publicPath())
                    .isTrue();
            String operationId = expected.method().equals("get")
                    ? "getAssignedAccessReviewWorkItem"
                    : "decideAssignedAccessReviewWorkItem";
            assertThat(auth.path("paths").path(expected.servicePath())
                    .path(expected.method()).path("operationId").asText())
                    .isEqualTo(operationId);
            assertThat(gateway.path("paths").path(expected.publicPath())
                    .path(expected.method()).path("operationId").asText())
                    .isEqualTo("auth_" + operationId);
        });

        assertThat(gateway.path("components").path("schemas")
                .has("auth_ApiResponseWorkItemDetail")).isTrue();
        assertThat(gateway.path("components").path("schemas")
                .has("auth_WorkItemDetail")).isTrue();
        assertThat(gateway.path("paths").has(
                "/api/internal/auth/work/access-review-items/{workItemRef}"))
                .isFalse();
    }

    @Test
    void exportedWorkspaceQueueAdmitsOnlyTheExplicitReviewType() throws Exception {
        Path root = repositoryRoot();
        JsonNode platform = read(root, "contracts/openapi/platform.json");
        JsonNode gateway = read(root, "contracts/openapi/gateway-public.json");

        assertThat(platform.path("components").path("schemas").path("WorkItem")
                .path("properties").path("type").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("APPROVAL", "TASK", "SERVICE", "REQUIRED", "REVIEW");
        assertThat(gateway.path("components").path("schemas").path("platform_WorkItem")
                .path("properties").path("type").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("APPROVAL", "TASK", "SERVICE", "REQUIRED", "REVIEW");
    }

    private void assertBinding(JsonNode binding, String path) {
        assertThat(binding.path("path").asText()).isEqualTo(path);
        assertThat(binding.path("method").asText().toLowerCase())
                .isEqualTo(ROUTES.values().stream()
                        .filter(expected -> expected.publicPath().equals(path)
                                || expected.servicePath().equals(path))
                        .findFirst().orElseThrow().method());
    }

    private JsonNode read(Path root, String relative) throws Exception {
        return objectMapper.readTree(root.resolve(relative).toFile());
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/auth.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }

    private record Binding(String method, String publicPath, String servicePath) {
    }
}
