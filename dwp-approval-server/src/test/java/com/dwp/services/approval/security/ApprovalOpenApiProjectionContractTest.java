package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalOpenApiProjectionContractTest {

    private static final Set<String> HIGH_RESPONSE_CODES =
            Set.of("200", "403", "409", "422", "503");
    private static final Set<String> STEP_UP_HEADERS = Set.of(
            "X-DWP-Step-Up-Challenge", "Idempotency-Key",
            "X-DWP-Expected-Decision-Revision");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fourHighRiskCommandsPublishBoundHeadersAndTypedFailureResponses() throws Exception {
        JsonNode api = approvalApi();
        assertHighCommand(api, "/v1/admin/workflows/{workflowId}/publish", false);
        assertHighCommand(api, "/v1/admin/forms/{formId}/publish", false);
        assertHighCommand(api, "/v1/admin/policies/{policyId}/publish", false);
        assertHighCommand(api, "/v1/admin/operations/events/{outboxId}/retry", true);

        assertThat(api.at("/components/schemas/PublishWorkflowRequest/required"))
                .anySatisfy(value -> assertThat(value.asText()).isEqualTo("expectedVersion"));
        assertThat(api.at("/components/schemas/PublishFormRequest/required"))
                .anySatisfy(value -> assertThat(value.asText()).isEqualTo("expectedVersion"));
        assertThat(api.at("/components/schemas/PublishPolicyRequest/required"))
                .anySatisfy(value -> assertThat(value.asText()).isEqualTo("expectedVersion"));
    }

    @Test
    void retryIsBodylessAndUsesTheExactHeaderVersionRegistryBinding() throws Exception {
        JsonNode retry = approvalApi().path("paths")
                .path("/v1/admin/operations/events/{outboxId}/retry").path("post");
        assertThat(fieldSet(retry)).doesNotContain("requestBody");

        JsonNode route = StreamSupport.stream(
                        approvalPep().path("routes").spliterator(), false)
                .filter(value -> "route.approvals.admin.operations.retry.action".equals(
                        value.path("routeContractKey").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode bindings = route.path("stepUpCommandBindings");
        assertThat(bindings.size()).isEqualTo(1);
        JsonNode binding = bindings.get(0);
        assertThat(fieldSet(binding)).containsExactlyInAnyOrder(
                "audience", "bindingKey", "expectedObjectVersionName",
                "expectedObjectVersionSource", "ownerServiceKey",
                "targetIdPathParameter", "targetType");
        assertThat(binding.path("bindingKey").asText())
                .isEqualTo("route.approvals.admin.operations.retry.action.binding.01");
        assertThat(binding.path("expectedObjectVersionSource").asText())
                .isEqualTo("COMMAND_HEADER");
        assertThat(binding.path("expectedObjectVersionName").asText())
                .isEqualTo("X-DWP-Expected-Object-Version");
        assertThat(binding.path("targetType").asText()).isEqualTo("OUTBOX_EVENT");
        assertThat(binding.path("targetIdPathParameter").asText()).isEqualTo("outboxId");
        assertThat(binding.path("ownerServiceKey").asText()).isEqualTo("approval");
        assertThat(binding.path("audience").asText()).isEqualTo("dwp-approval-server");
    }

    @Test
    void oversightAndAuditorSchemasAreClosedAndContainOnlyRegisteredFields() throws Exception {
        JsonNode schemas = approvalApi().at("/components/schemas");
        assertClosed(schemas, "ApprovalOversightWorkflowV1", Set.of(
                "workflowId", "workflowKey", "nameKo", "nameEn", "category",
                "dataClassification", "lifecycleState", "currentVersion", "slaMinutes",
                "version", "updatedAt"));
        assertClosed(schemas, "ApprovalOversightFormV1", Set.of(
                "formId", "formKey", "categoryId", "categoryKey", "categoryNameKo",
                "categoryNameEn", "nameKo", "nameEn", "formKind", "lifecycleState",
                "currentVersion", "fieldCount", "routeCount", "usageCount", "version",
                "updatedAt"));
        assertClosed(schemas, "ApprovalOversightPolicyV1", Set.of(
                "policyId", "policyKey", "nameKo", "nameEn", "policyType",
                "enforcementMode", "severity", "lifecycleState", "version", "pendingReview",
                "pendingEnforcementMode", "pendingSeverity", "pendingLifecycleState", "pendingAt"));
        assertClosed(schemas, "ApprovalOversightSignatureV1", Set.of(
                "providerId", "providerKey", "displayName", "providerType", "lifecycleState",
                "credentialConfigured", "lastHealthCheckedAt", "version"));
        assertClosed(schemas, "ApprovalFullManagementSignatureV1", Set.of(
                "providerId", "providerKey", "displayName", "providerType", "lifecycleState",
                "credentialConfigured", "lastHealthCheckedAt", "version"));
        assertClosed(schemas, "ApprovalAuditorOperationSignalV1",
                Set.of("key", "state", "count"));
        assertClosed(schemas, "ApprovalAuditorIntegrationDeliveryV1", Set.of(
                "eventType", "status", "attemptCount", "manualRetryCount",
                "availableAt", "publishedAt"));
    }

    @Test
    void generatedFieldMaskBindingsPinCanonicalRawOpenApiSchemaHashes() throws Exception {
        JsonNode apiSchemas = approvalApi().at("/components/schemas");
        JsonNode pep = objectMapper.readTree(repositoryRoot().resolve(
                "dwp-approval-server/src/main/resources/product-authorization/"
                        + "approval-pilot-pep-v2.generated.json").toFile());
        Set<String> seenSchemas = new LinkedHashSet<>();
        int bindingCount = 0;

        for (JsonNode route : pep.path("routes")) {
            for (JsonNode profile : route.path("accessProfiles")) {
                String profileKey = profile.path("profileKey").asText();
                if (!ApprovalProjectionSchemaContract.isFieldMaskProfile(profileKey)) {
                    profile.path("responseProjectionBindings").forEach(binding ->
                            assertThat(fieldSet(binding)).containsExactlyInAnyOrder(
                                    "apiBindingKey", "projectionPolicyKey", "responseSchemaKey"));
                    continue;
                }
                for (JsonNode binding : profile.path("responseProjectionBindings")) {
                    bindingCount++;
                    String schemaKey = binding.path("responseSchemaKey").asText();
                    JsonNode rawSchema = apiSchemas.get(schemaKey);
                    assertThat(rawSchema).as(schemaKey).isNotNull();
                    assertThat(fieldSet(binding)).containsExactlyInAnyOrder(
                            "apiBindingKey", "projectionPolicyKey", "responseSchemaKey",
                            "schemaVersion", "openApiSchemaSha256", "additionalProperties");
                    assertThat(binding.path("schemaVersion").asInt()).isEqualTo(1);
                    assertThat(binding.path("additionalProperties").asBoolean(true)).isFalse();
                    assertThat(rawSchema.path("additionalProperties").asBoolean(true)).isFalse();
                    String hash = sha256(canonical(rawSchema));
                    assertThat(binding.path("openApiSchemaSha256").asText())
                            .isEqualTo(hash)
                            .isEqualTo(ApprovalProjectionSchemaContract.expectedSha256(schemaKey));
                    assertThat(ApprovalProjectionSchemaContract.matches(
                            profileKey, schemaKey, binding.path("schemaVersion").intValue(),
                            hash, binding.path("additionalProperties").booleanValue())).isTrue();
                    seenSchemas.add(schemaKey);
                }
            }
        }

        assertThat(bindingCount).isEqualTo(13);
        assertThat(seenSchemas).containsExactlyInAnyOrderElementsOf(
                ApprovalProjectionSchemaContract.schemaKeys());
    }

    @Test
    void readOperationsExposeOnlyServerSelectedProjectionUnions() throws Exception {
        JsonNode api = approvalApi();
        assertOneOf(api, "/v1/admin/workflows", Set.of(
                "#/components/schemas/ApprovalFullWorkflowListResponse",
                "#/components/schemas/ApprovalOversightWorkflowListResponse"));
        assertOneOf(api, "/v1/admin/operations", Set.of(
                "#/components/schemas/ApprovalFullOperationsResponse",
                "#/components/schemas/ApprovalAuditorOperationsResponse",
                "#/components/schemas/ApprovalOversightOperationsResponse"));
        assertOneOf(api, "/v1/admin/signatures", Set.of(
                "#/components/schemas/ApprovalFullSignatureListResponse",
                "#/components/schemas/ApprovalOversightSignatureListResponse"));
        assertThat(api.at("/components/schemas/ApprovalFullSignatureListResponse")
                .toString()).doesNotContain("SignatureProviderSummary", "capabilities");
    }

    private void assertHighCommand(JsonNode api, String path, boolean headerVersion) {
        JsonNode operation = api.path("paths").path(path).path("post");
        assertThat(fieldSet(operation.path("responses"))).containsAll(HIGH_RESPONSE_CODES);
        List<JsonNode> headerParameters = StreamSupport.stream(
                        operation.path("parameters").spliterator(), false)
                .filter(value -> "header".equals(value.path("in").asText()))
                .toList();
        Set<String> headers = headerParameters.stream()
                .map(value -> value.path("name").asText())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(headers).containsAll(STEP_UP_HEADERS);
        List<JsonNode> conditionalHeaders = headerParameters.stream()
                .filter(value -> STEP_UP_HEADERS.contains(value.path("name").asText()))
                .toList();
        assertThat(conditionalHeaders).hasSize(STEP_UP_HEADERS.size());
        conditionalHeaders.forEach(this::assertConditionalRequired);
        if (headerVersion) {
            assertThat(headers).contains("X-DWP-Expected-Object-Version");
            List<JsonNode> versionHeaders = headerParameters.stream()
                    .filter(value -> "X-DWP-Expected-Object-Version".equals(
                            value.path("name").asText()))
                    .toList();
            assertThat(versionHeaders).singleElement().satisfies(
                    this::assertConditionalRequired);
        }
    }

    private void assertConditionalRequired(JsonNode parameter) {
        assertThat(parameter.path("required").asBoolean()).isFalse();
        assertThat(parameter.path("description").asText())
                .contains("110/111", "000/100", "fail-closed");
        JsonNode extension = parameter.path("x-dwp-conditional-required");
        assertThat(extension.path("rolloutStates"))
                .extracting(JsonNode::asText)
                .containsExactly("110", "111");
        assertThat(extension.path("enforcement").asText()).isEqualTo("FAIL_CLOSED");
    }

    private void assertClosed(JsonNode schemas, String schemaName, Set<String> fields) {
        JsonNode schema = schemas.path(schemaName);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldSet(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(fields);
    }

    private void assertOneOf(JsonNode api, String path, Set<String> references) {
        JsonNode values = api.path("paths").path(path).path("get")
                .path("responses").path("200").path("content").path("*/*")
                .path("schema").path("oneOf");
        assertThat(StreamSupport.stream(values.spliterator(), false)
                .map(value -> value.path("$ref").asText()).toList())
                .containsExactlyInAnyOrderElementsOf(references);
    }

    private Set<String> fieldSet(JsonNode object) {
        return StreamSupport.stream(
                        ((Iterable<String>) () -> object.fieldNames()).spliterator(), false)
                .collect(java.util.stream.Collectors.toSet());
    }

    private String sha256(JsonNode value) throws Exception {
        byte[] payload = objectMapper.writeValueAsBytes(value);
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name ->
                    result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private JsonNode approvalApi() throws Exception {
        return objectMapper.readTree(repositoryRoot()
                .resolve("contracts/openapi/approval.json").toFile());
    }

    private JsonNode approvalPep() throws Exception {
        return objectMapper.readTree(repositoryRoot().resolve(
                "dwp-approval-server/src/main/resources/product-authorization/"
                        + "approval-pilot-pep-v2.generated.json").toFile());
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/approval.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }
}
