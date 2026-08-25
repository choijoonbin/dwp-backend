package com.dwp.services.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppAdminPresetOpenApiContractTest {

    private static final String ROOT =
            "/auth/admin/access/app-governance/presets";
    private static final Set<String> RELATIVE_PATHS = Set.of(
            "/catalog",
            "/assignments",
            "/assignments/{assignmentId}",
            "/assignments/{assignmentId}/decision",
            "/assignments/{assignmentId}/activate",
            "/assignments/{assignmentId}/revoke",
            "/reviews/{reviewId}/decision",
            "/self-service-options",
            "/self-service-requests");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authAndGatewaySnapshotsPublishTheCompletePresetWorkflow() throws IOException {
        JsonNode auth = openApi("auth.json");
        JsonNode gateway = openApi("gateway-public.json");

        RELATIVE_PATHS.forEach(relative -> {
            assertThat(auth.path("paths").has(ROOT + relative)).isTrue();
            assertThat(gateway.path("paths").has("/api" + ROOT + relative)).isTrue();
        });
        assertThat(auth.path("paths").has("/api" + ROOT + "/catalog")).isFalse();

        JsonNode selfOptions = auth.path("paths")
                .path(ROOT + "/self-service-options").path("get");
        assertThat(parameterNames(selfOptions.path("parameters")))
                .contains("appResourceKey")
                .doesNotContain("surfaceId");
        assertThat(parameter(selfOptions, "appResourceKey").path("required").asBoolean())
                .isTrue();

        JsonNode selfRequest = auth.path("paths")
                .path(ROOT + "/self-service-requests").path("post");
        JsonNode idempotency = parameter(selfRequest, "Idempotency-Key");
        assertThat(idempotency.path("required").asBoolean()).isTrue();
        assertThat(idempotency.path("schema").path("minLength").asInt()).isEqualTo(8);
        assertThat(idempotency.path("schema").path("maxLength").asInt()).isEqualTo(160);
        assertThat(idempotency.path("schema").path("pattern").asText())
                .isEqualTo("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}");
        assertThat(selfRequest.path("requestBody").path("content")
                .path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/CreateSelfServicePresetRequest");
        assertThat(selfRequest.path("responses").path("200").path("content")
                .path("*/*").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiResponseAppAdminPresetAssignment");
    }

    @Test
    void schemasExposeAuthoritativeResourceAndDashboardProjectionWithoutInference()
            throws IOException {
        JsonNode schemas = openApi("auth.json").path("components").path("schemas");

        assertThat(fieldNames(schemas.path("AppAdminPreset").path("properties")))
                .contains("presetCode", "productKey", "appResourceKey", "requestable",
                        "unavailableReason", "catalogVersion", "duties");
        assertThat(fieldNames(schemas.path("AppAdminPresetAssignment").path("properties")))
                .contains("presetAssignmentId", "requestChannel", "resourceSetId",
                        "responsibilityAssignmentId", "lifecycleState", "validTo",
                        "reviewDueAt", "catalogVersion", "version", "duties",
                        "activatedBy", "activatedByName", "activatedAt",
                        "activationReason");
        assertThat(fieldNames(schemas.path("AppAdminPresetReview").path("properties")))
                .contains("reviewId", "resourceSetId", "resourceSetName",
                        "lifecycleState", "evidence");
        assertThat(fieldNames(schemas.path("ActivateAppAdminPresetRequest")
                .path("properties")))
                .containsExactlyInAnyOrder("reason", "version");
        assertThat(fieldNames(schemas.path("Dashboard").path("properties")))
                .contains("presetCatalog", "presetAssignments", "presetReviews");
        assertThat(fieldNames(schemas.path("CreateSelfServicePresetRequest")
                .path("properties")))
                .containsExactlyInAnyOrder(
                        "presetCode", "resourceSetId", "validTo", "reviewDueAt",
                        "justification");
        assertThat(arrayValues(schemas.path("CreateSelfServicePresetRequest")
                .path("required")))
                .containsExactlyInAnyOrder(
                        "presetCode", "resourceSetId", "validTo", "reviewDueAt",
                        "justification");
    }

    private JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) return parameter;
        }
        throw new AssertionError("Missing OpenAPI parameter: " + name);
    }

    private Set<String> parameterNames(JsonNode parameters) {
        Set<String> values = new HashSet<>();
        parameters.forEach(parameter -> values.add(parameter.path("name").asText()));
        return values;
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> values = new HashSet<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private Set<String> arrayValues(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private JsonNode openApi(String fileName) throws IOException {
        return objectMapper.readTree(
                repositoryRoot().resolve("contracts/openapi").resolve(fileName).toFile());
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("contracts/openapi/auth.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root was not found.");
    }
}
