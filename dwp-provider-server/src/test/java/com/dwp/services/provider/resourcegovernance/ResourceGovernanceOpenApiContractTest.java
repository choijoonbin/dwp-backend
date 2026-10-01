package com.dwp.services.provider.resourcegovernance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the browser-visible resource/artifact governance surface to the generated contracts. */
class ResourceGovernanceOpenApiContractTest {

    private static final String GATEWAY_PREFIX = "/api/provider";
    private static final List<Operation> PUBLIC_OPERATIONS = List.of(
            new Operation("get", "/v1/admin/resource-governance/commitments"),
            new Operation("get", "/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}/ledger"),
            new Operation("post", "/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}/ledger"),
            new Operation("get", "/v1/admin/resource-governance/commitment-changes"),
            new Operation("post", "/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}/changes"),
            new Operation("post", "/v1/admin/resource-governance/commitment-changes/{changeRequestId}/decision"),
            new Operation("post", "/v1/admin/resource-governance/commitment-changes/{changeRequestId}/publish"),
            new Operation("get", "/v1/admin/resource-governance/lifecycle-requests"),
            new Operation("post", "/v1/admin/resource-governance/tenants/{tenantId}/lifecycle-requests"),
            new Operation("post", "/v1/admin/resource-governance/lifecycle-requests/{requestId}/refresh-hold"),
            new Operation("post", "/v1/admin/resource-governance/lifecycle-requests/{requestId}/submit"),
            new Operation("post", "/v1/admin/resource-governance/lifecycle-requests/{requestId}/decision"),
            new Operation("get", "/v1/admin/artifact-governance/manifests"),
            new Operation("post", "/v1/admin/artifact-governance/manifests"),
            new Operation("post", "/v1/admin/artifact-governance/manifests/{artifactId}/compatibility"),
            new Operation("post", "/v1/admin/artifact-governance/manifests/{artifactId}/submit"),
            new Operation("post", "/v1/admin/artifact-governance/manifests/{artifactId}/review"),
            new Operation("get", "/v1/admin/artifact-governance/rollout-plans"),
            new Operation("post", "/v1/admin/artifact-governance/rollout-plans"),
            new Operation("post", "/v1/admin/artifact-governance/rollout-plans/{planId}/submit"),
            new Operation("post", "/v1/admin/artifact-governance/rollout-plans/{planId}/approval"),
            new Operation("post", "/v1/admin/artifact-governance/rollout-plans/{planId}/ready"),
            new Operation("post", "/v1/admin/artifact-governance/rollout-plans/{planId}/evidence"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerAndGatewayPublishTheSameGovernedControlPlane() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        for (Operation operation : PUBLIC_OPERATIONS) {
            assertThat(provider.path("paths").path(operation.path()).path(operation.method()).isObject())
                    .as("provider %s %s", operation.method(), operation.path()).isTrue();
            assertThat(gateway.path("paths").path(GATEWAY_PREFIX + operation.path())
                    .path(operation.method()).isObject())
                    .as("gateway %s %s", operation.method(), operation.path()).isTrue();
        }

        String directCommitmentPath =
                "/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}";
        assertThat(provider.path("paths").path(directCommitmentPath).path("put").isMissingNode())
                .as("direct commitment mutation must not bypass independent review").isTrue();
        assertThat(gateway.path("paths").path(GATEWAY_PREFIX + directCommitmentPath)
                .path("put").isMissingNode())
                .as("gateway direct commitment mutation must be absent").isTrue();

        List<String> providerPaths = pathNames(provider);
        List<String> gatewayPaths = pathNames(gateway);
        assertThat(providerPaths)
                .noneMatch(path -> path.contains("/internal/")
                        && (path.contains("resource-governance") || path.contains("artifact-governance")));
        assertThat(gatewayPaths)
                .noneMatch(path -> path.contains("/internal/"));
    }

    @Test
    void publicSchemasRetainInternalOnlyAndExternalBoundaryState() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertProperties(provider, "Commitment", Set.of(
                "providerTenantId", "resourceKey", "unit", "quotaLimit", "budgetLimit",
                "currencyCode", "controlMode", "controlScope", "activeOverride", "controlPeriod",
                "internalEvidenceFreshness", "lifecycleState", "sourceSystem", "externalFeedState", "totals",
                "version", "updatedAt", "tenantKey", "tenantDisplayName"));
        assertProperties(gateway, "provider_Commitment", Set.of(
                "providerTenantId", "resourceKey", "unit", "quotaLimit", "budgetLimit",
                "currencyCode", "controlMode", "controlScope", "activeOverride", "controlPeriod",
                "internalEvidenceFreshness", "lifecycleState", "sourceSystem", "externalFeedState", "totals",
                "version", "updatedAt", "tenantKey", "tenantDisplayName"));
        assertProperties(provider, "LedgerTotals", Set.of(
                "allocated", "released", "meteredInternalEvidence", "adjusted", "budgetReserved",
                "budgetReleased", "budgetSpentInternalEvidence", "allocationBalance",
                "budgetReservationBalance", "remainingQuota", "remainingBudget", "quotaControlState",
                "budgetControlState"));
        assertProperties(provider, "TenantLifecycleRequest", Set.of(
                "lifecycleRequestId", "providerTenantId", "tenantKey", "tenantDisplayName",
                "requestedAction", "lifecycleState", "holdEvaluationState", "holdEvidenceRefs",
                "executionState", "justification", "requestedBy", "submittedBy", "approvedBy",
                "submittedAt", "approvedAt", "decisionReason", "version", "createdAt", "updatedAt"));
        assertProperties(provider, "ResourceCommitmentChange", Set.of(
                "changeRequestId", "providerTenantId", "tenantKey", "tenantDisplayName",
                "resourceKey", "changeKind", "baselineCommitmentVersion", "baseline", "proposed",
                "commercialRenewalRevisionId", "overrideExpiresAt", "lifecycleState",
                "reservationState", "justification", "decisionDueAt", "requestedBy", "requestedAt",
                "decidedBy", "decidedAt", "decisionReason", "publishedBy", "publishedAt",
                "version", "updatedAt"));
        assertProperties(gateway, "provider_ResourceCommitmentChange", Set.of(
                "changeRequestId", "providerTenantId", "tenantKey", "tenantDisplayName",
                "resourceKey", "changeKind", "baselineCommitmentVersion", "baseline", "proposed",
                "commercialRenewalRevisionId", "overrideExpiresAt", "lifecycleState",
                "reservationState", "justification", "decisionDueAt", "requestedBy", "requestedAt",
                "decidedBy", "decidedAt", "decisionReason", "publishedBy", "publishedAt",
                "version", "updatedAt"));
        assertProperties(gateway, "provider_TenantLifecycleRequest", Set.of(
                "lifecycleRequestId", "providerTenantId", "tenantKey", "tenantDisplayName",
                "requestedAction", "lifecycleState", "holdEvaluationState", "holdEvidenceRefs",
                "executionState", "justification", "requestedBy", "submittedBy", "approvedBy",
                "submittedAt", "approvedAt", "decisionReason", "version", "createdAt", "updatedAt"));
        assertProperties(provider, "ArtifactManifest", Set.of(
                "artifactId", "productKey", "artifactVersion", "artifactType",
                "manifestSchemaVersion", "manifest", "compatibilityPolicy", "compatibility", "declaredDigest",
                "lifecycleState", "compatibilityState", "compatibilityEvidence", "signatureState",
                "distributionState", "createdBy", "updatedBy", "createdAt", "updatedAt", "version",
                "reviews"));
        assertProperties(gateway, "provider_ArtifactRolloutPlan", Set.of(
                "rolloutPlanId", "artifactId", "productKey", "artifactVersion", "name", "targetScope",
                "stages", "rollbackPlan", "rollbackFeasibility", "lifecycleState", "executorState",
                "reason", "requestedBy", "approvedBy", "submittedAt", "approvedAt", "decisionReason",
                "version", "createdAt", "updatedAt", "rollbackReadiness", "evidence"));
    }

    private void assertProperties(JsonNode contract, String schema, Set<String> expected) {
        JsonNode properties = contract.path("components").path("schemas").path(schema).path("properties");
        assertThat(properties.isObject()).as(schema + " schema exists").isTrue();
        List<String> fields = new ArrayList<>();
        properties.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrderElementsOf(expected);
    }

    private List<String> pathNames(JsonNode contract) {
        List<String> paths = new ArrayList<>();
        contract.path("paths").fieldNames().forEachRemaining(paths::add);
        return paths;
    }

    private JsonNode openApi(String fileName) throws IOException {
        return objectMapper.readTree(
                repositoryRoot().resolve("contracts/openapi").resolve(fileName).toFile());
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("contracts/openapi/provider.json"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root was not found.");
    }

    private record Operation(String method, String path) {
    }
}
