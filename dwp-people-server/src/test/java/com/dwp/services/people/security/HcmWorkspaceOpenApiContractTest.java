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
            Map.entry("/v1/workforce/data-operations/hris/connectors/{connectorId}/reconciliations", "post"),
            Map.entry("/v1/hris/performance/cycles/{cycleId}/publish", "post"),
            Map.entry("/v1/workforce/assignment-proposals/{proposalId}/submit", "post"));
    private static final Set<String> STEP_UP_HEADERS = Set.of(
            "X-DWP-Step-Up-Challenge", "Idempotency-Key",
            "X-DWP-Expected-Decision-Revision", "X-DWP-Expected-Object-Version");
    private static final Map<String, String> ASSIGNMENT_OPERATIONS = Map.of(
            "GET /v1/workforce/assignments/{assignmentId}", "getAssignment",
            "GET /v1/workforce/assignments/{assignmentId}/timeline",
            "getAssignmentTimeline",
            "GET /v1/workforce/assignment-proposals/{proposalId}", "getAssignmentProposal",
            "POST /v1/workforce/assignment-proposals", "createAssignmentProposal",
            "POST /v1/workforce/assignment-proposals/{proposalId}/cancel",
            "cancelAssignmentProposal",
            "POST /v1/workforce/assignment-proposals/{proposalId}/submit",
            "submitAssignmentProposal",
            "POST /v1/workforce/assignment-proposals/{proposalId}/validate",
            "validateAssignmentProposal");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void everyStablePeoplePepBindingAndG3AssignmentOperationIsPublished()
            throws Exception {
        JsonNode service = openApi("people.json");
        JsonNode gateway = openApi("gateway-public.json");
        HcmV3PepRegistry registry = new HcmV3PepRegistry(
                new ObjectMapper().findAndRegisterModules());
        assertThat(registry.bindingContracts()).hasSize(94);
        assertThat(registry.bindingContracts().stream()
                .map(value -> value.method() + " " + value.servicePath())
                .distinct()).hasSize(82);
        Set<String> missingServiceOperations = registry.bindingContracts().stream()
                .filter(binding -> !service.path("paths").path(binding.servicePath())
                        .has(binding.method().toLowerCase()))
                .map(binding -> binding.method() + " " + binding.servicePath())
                .collect(Collectors.toSet());
        Set<String> missingGatewayOperations = registry.bindingContracts().stream()
                .filter(binding -> !gateway.path("paths")
                        .path("/api/people" + binding.servicePath())
                        .has(binding.method().toLowerCase()))
                .map(binding -> binding.method() + " " + binding.servicePath())
                .collect(Collectors.toSet());

        assertThat(missingServiceOperations).isEmpty();
        assertThat(missingGatewayOperations).isEmpty();

        ASSIGNMENT_OPERATIONS.forEach((operation, operationId) -> {
            String[] parts = operation.split(" ", 2);
            String method = parts[0].toLowerCase();
            String path = parts[1];
            JsonNode serviceOperation = service.path("paths").path(path).path(method);
            JsonNode gatewayOperation = gateway.path("paths")
                    .path("/api/people" + path).path(method);
            assertThat(serviceOperation.path("operationId").asText())
                    .as(operation).isEqualTo(operationId);
            assertThat(gatewayOperation.path("operationId").asText())
                    .as("gateway " + operation)
                    .isEqualTo("people_" + operationId);
            assertPublishedResponseSchema(service, serviceOperation, operation);
            assertPublishedResponseSchema(gateway, gatewayOperation, "gateway " + operation);
        });
    }

    @Test
    void assignmentProposalCommandProofHeadersAreExactInServiceAndGateway()
            throws Exception {
        JsonNode service = openApi("people.json");
        JsonNode gateway = openApi("gateway-public.json");
        Set<String> mutations = Set.of(
                "/v1/workforce/assignment-proposals",
                "/v1/workforce/assignment-proposals/{proposalId}/validate",
                "/v1/workforce/assignment-proposals/{proposalId}/submit",
                "/v1/workforce/assignment-proposals/{proposalId}/cancel");
        for (String path : mutations) {
            for (JsonNode operation : Set.of(
                    service.path("paths").path(path).path("post"),
                    gateway.path("paths").path("/api/people" + path).path("post"))) {
                assertStringHeader(operation, "Idempotency-Key", 1, 200);
            }
        }

        for (JsonNode submit : Set.of(
                service.path("paths")
                        .path("/v1/workforce/assignment-proposals/{proposalId}/submit")
                        .path("post"),
                gateway.path("paths")
                        .path("/api/people/v1/workforce/assignment-proposals/{proposalId}/submit")
                        .path("post"))) {
            assertStringHeader(submit, "X-DWP-Step-Up-Challenge", 1, null);
            assertStringHeader(
                    submit, "X-DWP-Expected-Decision-Revision", 1, 200);
            JsonNode objectVersion = header(submit, "X-DWP-Expected-Object-Version");
            assertThat(objectVersion.path("required").asBoolean()).isTrue();
            assertThat(objectVersion.path("schema").path("type").asText())
                    .isEqualTo("integer");
            assertThat(objectVersion.path("schema").path("format").asText())
                    .isEqualTo("int64");
            assertThat(objectVersion.path("schema").path("minimum").asLong())
                    .isZero();
        }

        mutations.stream()
                .filter(path -> !path.endsWith("/submit"))
                .forEach(path -> {
                    assertThat(headerNames(service.path("paths").path(path).path("post")))
                            .doesNotContain(
                                    "X-DWP-Step-Up-Challenge",
                                    "X-DWP-Expected-Decision-Revision",
                                    "X-DWP-Expected-Object-Version");
                    assertThat(headerNames(gateway.path("paths")
                            .path("/api/people" + path).path("post")))
                            .doesNotContain(
                                    "X-DWP-Step-Up-Challenge",
                                    "X-DWP-Expected-Object-Version");
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
    void allNinePeopleOwnedHighRiskBindingsPublishTheCommandProofHeaders() throws Exception {
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

    private void assertPublishedResponseSchema(
            JsonNode document, JsonNode operation, String description) {
        String reference = operation.path("responses").path("200")
                .path("content").path("application/json").path("schema")
                .path("$ref").asText();
        assertThat(reference).as(description + " response schema")
                .startsWith("#/components/schemas/");
        assertThat(document.path("components").path("schemas")
                .has(reference.substring("#/components/schemas/".length())))
                .as(description + " response component").isTrue();
    }

    private void assertStringHeader(
            JsonNode operation, String name, int minLength, Integer maxLength) {
        JsonNode parameter = header(operation, name);
        assertThat(parameter.path("required").asBoolean()).as(name + " required").isTrue();
        assertThat(parameter.path("schema").path("type").asText())
                .as(name + " type").isEqualTo("string");
        assertThat(parameter.path("schema").path("minLength").asInt())
                .as(name + " minLength").isEqualTo(minLength);
        if (maxLength != null) {
            assertThat(parameter.path("schema").path("maxLength").asInt())
                    .as(name + " maxLength").isEqualTo(maxLength);
        }
    }

    private JsonNode header(JsonNode operation, String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(parameter -> "header".equals(parameter.path("in").asText()))
                .filter(parameter -> name.equals(parameter.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing header: " + name));
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
