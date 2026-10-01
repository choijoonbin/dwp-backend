package com.dwp.services.provider.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantOwnerProjectionOpenApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerSnapshotPublishesTenantSafeOwnerReads() throws IOException {
        JsonNode provider = objectMapper.readTree(repositoryRoot()
                .resolve("contracts/openapi/provider.json").toFile());

        assertReadResponse(
                provider,
                "/v1/tenant/settings/provider-domains",
                "#/components/schemas/ApiResponseDomainProjection");
        assertReadResponse(
                provider,
                "/v1/tenant/settings/data-governance-observation",
                "#/components/schemas/ApiResponseDataGovernanceProjection");
        assertReadResponse(
                provider,
                "/v1/tenant/settings/plan-eligibility",
                "#/components/schemas/ApiResponsePlanEligibilityProjection");

        List<String> domainFields = fields(provider, "DomainObservation");
        assertThat(domainFields)
                .contains("domainName", "verificationState", "evidenceFreshnessState")
                .doesNotContain(
                        "providerTenantId", "authTenantId", "verificationTokenHash",
                        "verificationRecordValue", "challenge");
        List<String> policyFields = fields(provider, "PolicyObservation");
        assertThat(policyFields)
                .contains("policyType", "coverage", "freshnessState", "evidenceState")
                .doesNotContain(
                        "policyRule", "scopeRef", "justification", "requestedBy", "approvedBy");
        List<String> planFields = fields(provider, "PlanObservation");
        assertThat(planFields)
                .contains("subscriptionState", "planKey", "planVersion")
                .doesNotContain("organizationId", "contractReference", "commercialMetadata");
        List<String> eligibilityFields = fields(provider, "ProductEligibilityObservation");
        assertThat(eligibilityFields)
                .contains("productKey", "entitlementKey", "eligibilityState")
                .doesNotContain("providerTenantId", "entitlementId", "configuration");
    }

    private void assertReadResponse(JsonNode contract, String path, String expectedSchema) {
        JsonNode operation = contract.path("paths").path(path).path("get");
        assertThat(operation.isObject()).as("GET " + path).isTrue();
        assertThat(operation.path("responses").path("200").path("content")
                .elements().next().path("schema").path("$ref").asText())
                .isEqualTo(expectedSchema);
    }

    private List<String> fields(JsonNode contract, String schemaName) {
        List<String> fields = new ArrayList<>();
        contract.path("components").path("schemas").path(schemaName)
                .path("properties").fieldNames().forEachRemaining(fields::add);
        return fields;
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
}
