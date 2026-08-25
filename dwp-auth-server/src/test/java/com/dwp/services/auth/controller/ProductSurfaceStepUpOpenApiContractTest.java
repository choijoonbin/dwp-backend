package com.dwp.services.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSurfaceStepUpOpenApiContractTest {

    private static final String SERVICE_PATH = "/auth/product-surface-step-up-challenges";
    private static final String GATEWAY_PATH = "/api/auth/product-surface-step-up-challenges";
    private static final Set<String> RESPONSE_CODES =
            Set.of("200", "400", "401", "403", "409", "503");
    private static final Set<String> REQUIRED_COMMAND_FIELDS = Set.of(
            "commandMethod", "commandPath", "targetType", "targetId",
            "expectedObjectVersion", "idempotencyKey", "payload");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serviceAndGatewayPublishTheExactCommandBoundIssuerContract() throws Exception {
        assertIssuerContract(openApi("auth.json"), SERVICE_PATH, "");
        assertIssuerContract(openApi("gateway-public.json"), GATEWAY_PATH, "auth_");
    }

    private void assertIssuerContract(JsonNode api, String path, String schemaPrefix) {
        JsonNode operation = api.path("paths").path(path).path("post");
        assertThat(operation.isObject()).isTrue();
        assertThat(fieldSet(operation.path("responses")))
                .containsExactlyInAnyOrderElementsOf(RESPONSE_CODES);

        JsonNode csrf = header(operation, "X-CSRF-TOKEN");
        JsonNode revision = header(operation, "X-DWP-Expected-Decision-Revision");
        assertThat(csrf.path("required").asBoolean()).isTrue();
        assertThat(revision.path("required").asBoolean()).isTrue();

        JsonNode requestSchema = dereference(api, operation.path("requestBody")
                .path("content").path("application/json").path("schema"));
        assertThat(requestSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(textValues(requestSchema.path("required")))
                .containsExactlyInAnyOrderElementsOf(REQUIRED_COMMAND_FIELDS);

        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpIssueResponse");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpContinuationRequired");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpContinuation");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpAuthenticationError");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpForbiddenError");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpConflictError");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpValidationError");
        assertClosed(api, schemaPrefix + "ProductSurfaceStepUpAuthorityUnavailableError");

        JsonNode forbidden = firstContentSchema(
                operation.path("responses").path("403").path("content"));
        assertThat(forbidden.path("oneOf")).hasSize(2);
    }

    private JsonNode header(JsonNode operation, String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(value -> "header".equals(value.path("in").asText()))
                .filter(value -> name.equals(value.path("name").asText()))
                .findFirst().orElseThrow();
    }

    private void assertClosed(JsonNode api, String name) {
        JsonNode schema = api.path("components").path("schemas").path(name);
        assertThat(schema.isObject()).as(name + " exists").isTrue();
        assertThat(schema.path("additionalProperties").asBoolean())
                .as(name + " is closed").isFalse();
    }

    private JsonNode dereference(JsonNode api, JsonNode schema) {
        String reference = schema.path("$ref").asText();
        if (reference.isBlank()) return schema;
        assertThat(reference).startsWith("#/components/schemas/");
        return api.path("components").path("schemas")
                .path(reference.substring(reference.lastIndexOf('/') + 1));
    }

    private JsonNode firstContentSchema(JsonNode content) {
        JsonNode applicationJson = content.path("application/json").path("schema");
        if (!applicationJson.isMissingNode()) return applicationJson;
        return StreamSupport.stream(content.spliterator(), false)
                .map(value -> value.path("schema"))
                .findFirst().orElseThrow();
    }

    private Set<String> fieldSet(JsonNode object) {
        return StreamSupport.stream(
                        ((Iterable<String>) () -> object.fieldNames()).spliterator(), false)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .collect(java.util.stream.Collectors.toSet());
    }

    private JsonNode openApi(String file) throws Exception {
        return objectMapper.readTree(repositoryRoot()
                .resolve("contracts/openapi").resolve(file).toFile());
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
}
