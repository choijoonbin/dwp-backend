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

class AuthDiscoveryOpenApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publicLoginPolicyPublishesOnlyTheMinimalPreAuthenticationProjection()
            throws IOException {
        JsonNode auth = openApi("auth.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertThat(fieldNames(auth.path("components").path("schemas")
                .path("LoginOptionsResponse").path("properties")))
                .containsExactlyInAnyOrder(
                        "localLoginAvailable", "ssoLoginAvailable", "preferredLoginType")
                .doesNotContain(
                        "tenantId", "allowedLoginTypes", "ssoProviderKey", "providerKey",
                        "clientId", "issuer", "requireMfa");
        assertThat(auth.path("paths").path("/auth/policy").path("get")
                .path("responses").path("200").path("content").path("*/*")
                .path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiResponseLoginOptionsResponse");
        assertThat(gateway.path("paths").path("/api/auth/policy").path("get")
                .path("responses").path("200").path("content").path("*/*")
                .path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/auth_ApiResponseLoginOptionsResponse");
    }

    @Test
    void fullPolicyAndIdpDiscoveryStayOnAuthenticatedEndpointsAndOidcHidesProviderKey()
            throws IOException {
        JsonNode auth = openApi("auth.json");

        assertThat(auth.path("paths").has("/auth/me/policy")).isTrue();
        assertThat(auth.path("paths").has("/auth/idp")).isTrue();
        assertThat(parameterNames(auth.path("paths").path("/auth/oidc/login")
                .path("get").path("parameters")))
                .contains("tenantId", "X-Tenant-ID")
                .doesNotContain("providerKey");
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
