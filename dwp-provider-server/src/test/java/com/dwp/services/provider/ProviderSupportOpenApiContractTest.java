package com.dwp.services.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSupportOpenApiContractTest {

    private static final String ACCESS_REQUESTS = "/v1/admin/support-access-requests";
    private static final String ACTIVATE_REQUEST =
            "/v1/admin/support-access-requests/{requestId}/activate";
    private static final String POST_REVIEW_EVIDENCE =
            "/v1/admin/support-access-requests/{requestId}/post-review-evidence";
    private static final String SUPPORT_SESSIONS = "/v1/admin/support-sessions";
    private static final String GATEWAY_PREFIX = "/api/provider";

    private static final Set<String> ACCESS_REQUEST_LEDGER_FIELDS = Set.of(
            "supportAccessRequestId", "tenantId", "tenantKey", "tenantName",
            "requesterOwned", "requesterName", "lifecycleState", "accessMode",
            "justification", "scopes", "durationMinutes", "approvalReference",
            "customerApprovalRequired", "riskTier", "requestedAt", "decisionDueAt",
            "supportSessionId", "activatedAt", "completedAt", "postReviewState",
            "version");
    private static final Set<String> SESSION_LEDGER_FIELDS = Set.of(
            "supportSessionId", "supportAccessRequestId", "tenantId", "tenantKey",
            "tenantName", "operatorOwned", "operatorName", "lifecycleState", "scopes",
            "accessMode", "riskTier", "startedAt", "expiresAt", "lastUsedAt",
            "revokedAt", "version");
    private static final Set<String> POST_REVIEW_EVIDENCE_FIELDS = Set.of(
            "supportAccessRequestId", "supportSessionId", "tenantId",
            "sessionLifecycleState", "evidenceFrom", "evidenceThrough", "grantedScopes",
            "observedScopes", "totalEventCount", "actualUseCount", "deniedAttemptCount",
            "evidenceComplete", "displayTruncated", "noUseConfirmed", "readiness",
            "anomalies", "events");
    private static final Set<String> POST_REVIEW_EVENT_FIELDS = Set.of(
            "auditEventId", "occurredAt", "decision", "method", "routeTemplate", "scope",
            "outcome", "reasonCode", "correlationId");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void browserContractOmitsAuthTenantWhileInternalContractRetainsIt() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        JsonNode browser = provider.path("components").path("schemas")
                .path("BrowserSessionContext").path("properties");
        assertThat(browser.has("tenantId")).isTrue();
        assertThat(browser.has("authTenantId")).isFalse();
        assertThat(browser.has("providerTenantId")).isFalse();

        JsonNode internal = provider.path("components").path("schemas")
                .path("VerifiedSessionContext").path("properties");
        assertThat(internal.has("providerTenantId")).isTrue();
        assertThat(internal.has("authTenantId")).isTrue();
        assertThat(provider.path("paths")
                .has("/internal/provider/v1/support-access/resolve")).isTrue();

        JsonNode gatewayBrowser = gateway.path("components").path("schemas")
                .path("provider_BrowserSessionContext").path("properties");
        assertThat(gatewayBrowser.has("authTenantId")).isFalse();
        assertThat(gateway.path("components").path("schemas")
                .has("provider_VerifiedSessionContext")).isFalse();
        List<String> gatewayPaths = new ArrayList<>();
        gateway.path("paths").fieldNames().forEachRemaining(gatewayPaths::add);
        assertThat(gatewayPaths).noneMatch(path -> path.contains("/internal/"));
    }

    @Test
    void publicControlPlaneExposesDisableButNoReenableCommand() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertThat(provider.path("paths")
                .path("/v1/admin/support-activation/disable").has("post")).isTrue();
        assertThat(gateway.path("paths")
                .path("/api/provider/v1/admin/support-activation/disable").has("post")).isTrue();
        assertThat(provider.toString()).doesNotContain("support-activation/enable");
        assertThat(gateway.toString()).doesNotContain("support-activation/enable");
    }

    @Test
    void serviceAndGatewayPublishOnlyMinimalSupportLedgerResponses() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertResponseSchema(
                provider, ACCESS_REQUESTS, "get",
                "ApiResponseListAccessRequestLedgerItem",
                "AccessRequestLedgerItem", true);
        assertResponseSchema(
                gateway, GATEWAY_PREFIX + ACCESS_REQUESTS, "get",
                "provider_ApiResponseListAccessRequestLedgerItem",
                "provider_AccessRequestLedgerItem", true);
        assertResponseSchema(
                provider, SUPPORT_SESSIONS, "get",
                "ApiResponseListSessionLedgerItem",
                "SessionLedgerItem", true);
        assertResponseSchema(
                gateway, GATEWAY_PREFIX + SUPPORT_SESSIONS, "get",
                "provider_ApiResponseListSessionLedgerItem",
                "provider_SessionLedgerItem", true);

        assertSchemaFields(provider, "AccessRequestLedgerItem", ACCESS_REQUEST_LEDGER_FIELDS);
        assertSchemaFields(
                gateway, "provider_AccessRequestLedgerItem", ACCESS_REQUEST_LEDGER_FIELDS);
        assertSchemaFields(provider, "SessionLedgerItem", SESSION_LEDGER_FIELDS);
        assertSchemaFields(gateway, "provider_SessionLedgerItem", SESSION_LEDGER_FIELDS);
    }

    @Test
    void activationReturnsTheExactMinimalAccessRequestProjection() throws IOException {
        assertResponseSchema(
                openApi("provider.json"), ACTIVATE_REQUEST, "post",
                "ApiResponseAccessRequestLedgerItem",
                "AccessRequestLedgerItem", false);
        assertResponseSchema(
                openApi("gateway-public.json"), GATEWAY_PREFIX + ACTIVATE_REQUEST, "post",
                "provider_ApiResponseAccessRequestLedgerItem",
                "provider_AccessRequestLedgerItem", false);
    }

    @Test
    void postReviewEvidenceIsPublicButCarriesNoRawAuditOrActorIdentity() throws IOException {
        JsonNode provider = openApi("provider.json");
        JsonNode gateway = openApi("gateway-public.json");

        assertResponseSchema(
                provider, POST_REVIEW_EVIDENCE, "get",
                "ApiResponseEvidence", "Evidence", false);
        assertResponseSchema(
                gateway, GATEWAY_PREFIX + POST_REVIEW_EVIDENCE, "get",
                "provider_ApiResponseEvidence", "provider_Evidence", false);

        assertSchemaFields(provider, "Evidence", POST_REVIEW_EVIDENCE_FIELDS);
        assertSchemaFields(gateway, "provider_Evidence", POST_REVIEW_EVIDENCE_FIELDS);
        assertSchemaFields(provider, "Event", POST_REVIEW_EVENT_FIELDS);
        assertSchemaFields(gateway, "provider_Event", POST_REVIEW_EVENT_FIELDS);
        assertPropertyReference(provider, "Evidence", "events", "Event", true);
        assertPropertyReference(
                gateway, "provider_Evidence", "events", "provider_Event", true);
    }

    private void assertResponseSchema(
            JsonNode contract,
            String path,
            String method,
            String responseSchema,
            String dataSchema,
            boolean listData) {
        JsonNode operation = contract.path("paths").path(path).path(method);
        assertThat(operation.isObject()).as(method.toUpperCase() + " " + path).isTrue();

        JsonNode response = firstContentSchema(operation.path("responses").path("200"));
        assertThat(response.path("$ref").asText())
                .as(method.toUpperCase() + " " + path + " response")
                .isEqualTo(schemaReference(responseSchema));

        JsonNode data = schema(contract, responseSchema).path("properties").path("data");
        JsonNode projection = listData ? data.path("items") : data;
        assertThat(projection.path("$ref").asText())
                .as(responseSchema + " data projection")
                .isEqualTo(schemaReference(dataSchema));
    }

    private void assertSchemaFields(JsonNode contract, String name, Set<String> expected) {
        JsonNode component = schema(contract, name);
        assertThat(fieldNames(component.path("properties")))
                .as(name + " browser-visible properties")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private void assertPropertyReference(
            JsonNode contract,
            String owner,
            String property,
            String target,
            boolean listProperty) {
        JsonNode value = schema(contract, owner).path("properties").path(property);
        JsonNode projection = listProperty ? value.path("items") : value;
        assertThat(projection.path("$ref").asText())
                .as(owner + "." + property)
                .isEqualTo(schemaReference(target));
    }

    private JsonNode firstContentSchema(JsonNode response) {
        JsonNode content = response.path("content");
        JsonNode applicationJson = content.path("application/json").path("schema");
        if (!applicationJson.isMissingNode()) return applicationJson;
        return StreamSupport.stream(content.spliterator(), false)
                .map(value -> value.path("schema"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("A response schema was not published."));
    }

    private JsonNode schema(JsonNode contract, String name) {
        JsonNode component = contract.path("components").path("schemas").path(name);
        assertThat(component.isObject()).as(name + " schema exists").isTrue();
        return component;
    }

    private Set<String> fieldNames(JsonNode object) {
        return StreamSupport.stream(
                        ((Iterable<String>) () -> object.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
    }

    private String schemaReference(String schema) {
        return "#/components/schemas/" + schema;
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
}
