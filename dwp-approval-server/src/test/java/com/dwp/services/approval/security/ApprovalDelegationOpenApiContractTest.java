package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalDelegationOpenApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serviceAndGatewayPublishTheImmutableWorkflowDelegationIdentity()
            throws IOException {
        assertDelegationSchemas(openApi("approval.json"), "");
        assertDelegationSchemas(openApi("gateway-public.json"), "approval_");
    }

    private void assertDelegationSchemas(JsonNode contract, String prefix) {
        JsonNode schemas = contract.path("components").path("schemas");
        JsonNode request = schemas.path(prefix + "CreateDelegationRequest");
        JsonNode summary = schemas.path(prefix + "DelegationSummary");

        assertThat(fieldNames(request.path("properties")))
                .contains("workflowId", "workflowKey");
        assertThat(request.path("properties").path("workflowId").path("format").asText())
                .isEqualTo("uuid");
        assertThat(request.path("properties").path("workflowKey")
                .path("deprecated").asBoolean()).isTrue();
        assertThat(textValues(request.path("required")))
                .doesNotContain("workflowId", "workflowKey");

        assertThat(fieldNames(summary.path("properties")))
                .contains("workflowId", "workflowKey");
        assertThat(summary.path("properties").path("workflowId").path("format").asText())
                .isEqualTo("uuid");
        assertThat(summary.path("properties").path("workflowKey")
                .path("deprecated").asBoolean()).isTrue();
    }

    private JsonNode openApi(String file) throws IOException {
        return objectMapper.readTree(repositoryRoot()
                .resolve("contracts/openapi").resolve(file).toFile());
    }

    private Set<String> fieldNames(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private Set<String> textValues(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.forEach(item -> result.add(item.asText()));
        return result;
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("contracts/openapi/approval.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root was not found.");
    }
}
