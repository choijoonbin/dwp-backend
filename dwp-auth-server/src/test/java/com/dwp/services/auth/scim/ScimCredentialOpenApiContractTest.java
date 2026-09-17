package com.dwp.services.auth.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScimCredentialOpenApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void authContractPublishesCredentialGovernanceAndRequiredRotationBody() throws IOException {
        JsonNode auth = openApi("auth.json");
        JsonNode schemas = auth.path("components").path("schemas");

        assertThat(textValues(schemas.path("CreateRequest").path("required")))
                .containsExactlyInAnyOrder(
                        "allowedOperations",
                        "connectorKey",
                        "credentialTtlDays",
                        "displayName",
                        "purpose");
        assertThat(textValues(schemas.path("RotateRequest").path("required")))
                .containsExactlyInAnyOrder(
                        "credentialTtlDays", "expectedVersion", "explicitConfirmation", "reason");
        assertThat(schemas.path("RotateRequest").path("properties").path("expectedVersion")
                .path("minimum").asLong()).isZero();
        assertThat(fieldNames(schemas.path("ConnectorSummary").path("properties")))
                .contains(
                        "credentialState",
                        "credentialIssuedAt",
                        "credentialExpiresAt",
                        "credentialRotatedAt",
                        "ownerUserId",
                        "purpose");
        assertThat(auth.path("paths")
                .path("/auth/admin/provisioning/scim/connectors/{connectorId}/rotate-secret")
                .path("post")
                .path("requestBody")
                .path("required")
                .asBoolean()).isTrue();
    }

    @Test
    void gatewayProjectionCarriesTheSameRotationContract() throws IOException {
        JsonNode gateway = openApi("gateway-public.json");

        assertThat(gateway.path("paths")
                .path("/api/auth/admin/provisioning/scim/connectors/{connectorId}/rotate-secret")
                .path("post")
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText()).isEqualTo("#/components/schemas/auth_RotateRequest");
        assertThat(gateway.path("components").path("schemas")
                .path("auth_ConnectorSummary").path("properties").has("credentialExpiresAt"))
                .isTrue();
    }

    private static java.util.List<String> textValues(JsonNode node) {
        java.util.List<String> values = new java.util.ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> values = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static JsonNode openApi(String fileName) throws IOException {
        return JSON.readTree(repositoryRoot().resolve("contracts/openapi").resolve(fileName).toFile());
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("contracts/openapi"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the backend repository root.");
    }
}
