package com.dwp.services.provider.settings;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingsReadOpenApiContractTest {

    private static final String CATALOG_PATH = "/v1/admin/settings";
    private static final String EFFECTIVE_PATH =
            "/v1/admin/settings/{settingId}/effective";
    private static final String GATEWAY_PREFIX = "/api/provider";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void runtimeContractPublishesDefinitionAndApplicationTruthSchemas() {
        Map<String, Schema> schemas = ModelConverters.getInstance()
                .readAll(SettingsContracts.Resolution.class);

        assertThat(schemas).containsKeys(
                "Resolution", "Definition", "Owner", "ValidationContract",
                "ChangeContract", "Provenance", "ApplicationStatus", "ScopeTarget");
        assertThat(schemas.get("Definition").getProperties().keySet()).containsExactlyInAnyOrder(
                "settingId", "displayName", "description", "owner", "supportedScopes",
                "validation", "sensitivity", "change", "lifecycleState", "definitionVersion");
        assertThat(schemas.get("Resolution").getProperties().keySet()).containsExactlyInAnyOrder(
                "settingId", "resolutionState", "definition", "target", "effectiveValue",
                "effectiveVersion", "provenance", "applicationStatus", "reasonCode",
                "resolvedAt");
        assertThat(schemas.get("ApplicationStatus").getProperties().keySet())
                .contains("state", "desiredVersion", "publishedVersion",
                        "desiredState", "publishAcceptedAt",
                        "uniformlyObservedVersion", "expectedTargetCount",
                        "observedTargetCount", "convergedTargetCount",
                        "failedTargetCount", "driftedTargetCount",
                        "latestObservationAt", "stale");
    }

    @Test
    void springMvcBindsCatalogAndEffectiveReadEndpoints() throws Exception {
        SettingsReadService service = mock(SettingsReadService.class);
        SettingsReadController controller = new SettingsReadController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        SettingsContracts.Definition definition = definition();
        when(service.catalog("approval", "gateway", SettingsContracts.ScopeType.TENANT))
                .thenReturn(List.of(definition));

        mvc.perform(get("/v1/admin/settings")
                        .queryParam("query", "approval")
                        .queryParam("ownerService", "gateway")
                        .queryParam("scopeType", "TENANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].settingId")
                        .value("ux.product-surfaces.approvals.v1"));

        String settingId = "ux.product-surfaces.approvals.v1";
        String tenantId = "10000000-0000-0000-0000-000000000001";
        SettingsContracts.ScopeTarget target = new SettingsContracts.ScopeTarget(
                SettingsContracts.ScopeType.TENANT, tenantId, "production");
        when(service.effective(settingId, target)).thenReturn(new SettingsContracts.Resolution(
                settingId, SettingsContracts.ResolutionState.UNSUPPORTED_SCOPE,
                definition, target, null, null, List.of(), null,
                "SCOPE_NOT_SUPPORTED_BY_OWNER", null));

        mvc.perform(get("/v1/admin/settings/{settingId}/effective", settingId)
                        .queryParam("scopeType", "TENANT")
                        .queryParam("scopeId", tenantId)
                        .queryParam("environment", "production"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settingId").value(settingId))
                .andExpect(jsonPath("$.data.resolutionState").value("UNSUPPORTED_SCOPE"));

        verify(service).effective(settingId, target);
    }

    @Test
    void publishedSnapshotsExposeProviderSettingsReadsAndTruthSchemas() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertReadResponse(
                provider, CATALOG_PATH,
                "#/components/schemas/ApiResponseListDefinition");
        assertReadResponse(
                provider, EFFECTIVE_PATH,
                "#/components/schemas/ApiResponseResolution");
        assertReadResponse(
                gateway, GATEWAY_PREFIX + CATALOG_PATH,
                "#/components/schemas/provider_ApiResponseListDefinition");
        assertReadResponse(
                gateway, GATEWAY_PREFIX + EFFECTIVE_PATH,
                "#/components/schemas/provider_ApiResponseResolution");

        assertThat(provider.path("components").path("schemas").path("Resolution")
                .path("properties").fieldNames())
                .toIterable()
                .contains("resolutionState", "effectiveValue", "provenance",
                        "applicationStatus", "reasonCode", "resolvedAt");
        assertThat(provider.path("components").path("schemas").path("ApplicationStatus")
                .path("properties").fieldNames())
                .toIterable()
                .contains("state", "desiredVersion", "publishedVersion",
                        "observedTargetCount", "convergedTargetCount",
                        "failedTargetCount", "driftedTargetCount",
                        "latestObservationAt", "stale");
        assertThat(gateway.path("components").path("schemas")
                .has("provider_Resolution")).isTrue();
        assertThat(gateway.path("components").path("schemas")
                .has("provider_ApplicationStatus")).isTrue();
    }

    private void assertReadResponse(
            JsonNode contract,
            String path,
            String expectedSchema) {
        JsonNode operation = contract.path("paths").path(path).path("get");
        assertThat(operation.isMissingNode()).isFalse();
        JsonNode content = operation.path("responses").path("200").path("content");
        assertThat(content.isObject()).isTrue();
        assertThat(content.elements().next().path("schema").path("$ref").asText())
                .isEqualTo(expectedSchema);
    }

    private JsonNode openApi(String fileName) throws IOException {
        return objectMapper.readTree(
                repositoryRoot().resolve("contracts/openapi").resolve(fileName).toFile());
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("contracts/openapi/provider.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root was not found.");
    }

    private SettingsContracts.Definition definition() {
        return new SettingsContracts.Definition(
                "ux.product-surfaces.approvals.v1", "Approvals UI", "Description",
                new SettingsContracts.Owner(
                        "gateway", "FEATURE_ROLLOUT", "FEATURE_ROLLOUT_READ",
                        "/provider/feature-rollouts"),
                Set.of(SettingsContracts.ScopeType.TENANT),
                new SettingsContracts.ValidationContract(
                        SettingsContracts.ValueType.BOOLEAN, "v1",
                        JsonMapper.builder().build().createObjectNode(), true),
                SettingsContracts.Sensitivity.INTERNAL,
                new SettingsContracts.ChangeContract(
                        "L2", SettingsContracts.ChangeWorkflow.APPROVE_AND_ACTIVATE,
                        true, true),
                SettingsContracts.LifecycleState.ACTIVE, 1);
    }
}
