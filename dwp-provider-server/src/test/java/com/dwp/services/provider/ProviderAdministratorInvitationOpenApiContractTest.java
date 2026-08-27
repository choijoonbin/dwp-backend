package com.dwp.services.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAdministratorInvitationOpenApiContractTest {

    private static final String AUTH_PATH =
            "/internal/provider/v1/tenants/{providerTenantId}/administrator-invitations";
    private static final String PROVIDER_PATH =
            "/v1/admin/tenants/{tenantId}/administrators/{administratorId}/invitations";
    private static final String GATEWAY_PATH =
            "/api/provider/v1/admin/tenants/{tenantId}/administrators/{administratorId}/invitations";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerInvitationContractsAdvertiseOnlyTheFailClosedConflict() throws IOException {
        JsonNode auth = openApi("auth.json");
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertConflictOnly(auth, AUTH_PATH, "#/components/schemas/AdministratorInvitationConflictError");
        assertConflictOnly(provider, PROVIDER_PATH,
                "#/components/schemas/AdministratorInvitationConflictError");
        assertConflictOnly(gateway, GATEWAY_PATH,
                "#/components/schemas/provider_AdministratorInvitationConflictError");

        for (JsonNode contract : new JsonNode[]{auth, provider, gateway}) {
            assertThat(contract.toString())
                    .doesNotContain("\"activationToken\"")
                    .doesNotContain("\"activationPath\"");
        }
        assertThat(provider.path("components").path("schemas").has("AdministratorInvitation"))
                .isFalse();
        assertThat(gateway.path("components").path("schemas")
                .has("provider_AdministratorInvitation"))
                .isFalse();
    }

    private void assertConflictOnly(JsonNode contract, String path, String expectedSchema) {
        JsonNode responses = contract.path("paths").path(path).path("post").path("responses");
        assertThat(responses.has("200")).isFalse();
        assertThat(responses.path("409").path("description").asText())
                .contains("customer-owned out-of-band delivery");
        assertThat(responses.path("409").path("content").elements().next()
                .path("schema").path("$ref").asText()).isEqualTo(expectedSchema);

        String componentName = expectedSchema.substring(expectedSchema.lastIndexOf('/') + 1);
        assertThat(contract.path("components").path("schemas").path(componentName)
                .path("properties").path("errorCode").path("enum").get(0).asText())
                .isEqualTo("E1009");
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
