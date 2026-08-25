package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class HcmWorkspaceOpenApiContractTest {

    private static final Map<String, String> WORKBENCH_OPERATIONS = Map.of(
            "/v1/hr/team", "get",
            "/v1/hr/team/time", "get",
            "/v1/hr/team/absence", "get",
            "/v1/hr/team/time/{cardId}/decision", "post",
            "/v1/hr/team/absence/{requestId}/decision", "post",
            "/v1/workforce/operations/overview", "get",
            "/v1/workforce/organization/candidates", "get");
    private static final Map<String, String> HIGH_RISK_OPERATIONS = Map.ofEntries(
            Map.entry("/v1/workforce/organization/scenarios/{scenarioId}/publish", "post"),
            Map.entry("/v1/workforce/exports", "post"),
            Map.entry("/v1/workforce/exports/{requestId}/retry", "patch"),
            Map.entry("/v1/workforce/data-operations/hris/connectors/{connectorId}/configuration-check", "post"),
            Map.entry("/v1/workforce/data-operations/hris/connectors/{connectorId}/executions", "post"),
            Map.entry("/v1/workforce/data-operations/hris/sync-runs/{syncRunId}/retry", "post"),
            Map.entry("/v1/workforce/data-operations/hris/connectors/{connectorId}/reconciliations", "post"));
    private static final Set<String> STEP_UP_HEADERS = Set.of(
            "X-DWP-Step-Up-Challenge", "Idempotency-Key",
            "X-DWP-Expected-Decision-Revision", "X-DWP-Expected-Object-Version");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void everyGeneratedPeoplePepBindingHasAServiceAndPublicOpenApiOperation()
            throws Exception {
        JsonNode service = openApi("people.json");
        JsonNode gateway = openApi("gateway-public.json");
        HcmV3PepRegistry registry = new HcmV3PepRegistry(
                new ObjectMapper().findAndRegisterModules());
        assertThat(registry.bindingContracts()).hasSize(75);
        assertThat(registry.bindingContracts().stream()
                .map(value -> value.method() + " " + value.servicePath())
                .distinct()).hasSize(67);
        registry.bindingContracts().forEach(binding -> {
            String method = binding.method().toLowerCase();
            assertThat(service.path("paths").path(binding.servicePath()).has(method))
                    .as(binding.method() + " " + binding.servicePath()).isTrue();
            assertThat(gateway.path("paths")
                    .path("/api/people" + binding.servicePath()).has(method))
                    .as(binding.method() + " /api/people" + binding.servicePath()).isTrue();
        });
    }

    @Test
    void serviceAndGatewayPublishTheCompleteTeamAndOperationsWorkbench() throws Exception {
        JsonNode service = openApi("people.json");
        JsonNode gateway = openApi("gateway-public.json");
        WORKBENCH_OPERATIONS.forEach((path, method) -> {
            assertThat(service.path("paths").path(path).has(method)).as(path).isTrue();
            assertThat(gateway.path("paths").path("/api/people" + path).has(method))
                    .as("/api/people" + path).isTrue();
        });

        JsonNode schemas = service.path("components").path("schemas");
        assertThat(fieldNames(schemas.path("TeamMember").path("properties")))
                .containsExactlyInAnyOrder(
                        "personId", "displayName", "businessTitle",
                        "organizationName", "directReportCount");
        assertThat(textValues(schemas.path("TeamWorkspace").path("properties")
                .path("dataBoundary").path("enum")))
                .containsExactlyInAnyOrder(
                        "TEAM", "ORGANIZATION_SET", "TEAM_AND_ORGANIZATION_SET", "TENANT");
        assertThat(fieldNames(schemas.path("OrganizationCandidate").path("properties")))
                .containsExactlyInAnyOrder(
                        "publicId", "displayName", "organization", "position", "eligibility")
                .doesNotContain("email", "roles", "credentials");
        assertThat(textValues(schemas.path("OrganizationCandidate").path("properties")
                .path("eligibility").path("enum")))
                .containsExactlyInAnyOrder("ELIGIBLE", "INELIGIBLE");
    }

    @Test
    void allSevenPeopleOwnedHighRiskBindingsPublishTheCommandProofHeaders() throws Exception {
        JsonNode service = openApi("people.json");
        JsonNode gateway = openApi("gateway-public.json");
        HIGH_RISK_OPERATIONS.forEach((path, method) -> {
            assertThat(headerNames(service.path("paths").path(path).path(method)))
                    .as(path).containsAll(STEP_UP_HEADERS);
            assertThat(headerNames(gateway.path("paths")
                    .path("/api/people" + path).path(method)))
                    .as("/api/people" + path).containsAll(STEP_UP_HEADERS);
        });

        JsonNode create = service.path("components").path("schemas").path("CreateRequest");
        assertThat(fieldNames(create.path("properties")))
                .containsExactlyInAnyOrder(
                        "idempotencyKey", "datasetKey", "selection", "exportFormat",
                        "recipientReference", "purpose", "sourceReference")
                .doesNotContain("population");
        assertThat(fieldNames(service.path("components").path("schemas")
                .path("SyncRun").path("properties"))).contains("version");
    }

    private Set<String> headerNames(JsonNode operation) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(parameter -> "header".equals(parameter.path("in").asText()))
                .map(parameter -> parameter.path("name").asText())
                .collect(Collectors.toSet());
    }

    private Set<String> fieldNames(JsonNode object) {
        return StreamSupport.stream(
                        ((Iterable<String>) () -> object.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
    }

    private Set<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toSet());
    }

    private JsonNode openApi(String file) throws Exception {
        return objectMapper.readTree(repositoryRoot()
                .resolve("contracts/openapi").resolve(file).toFile());
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/people.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }
}
