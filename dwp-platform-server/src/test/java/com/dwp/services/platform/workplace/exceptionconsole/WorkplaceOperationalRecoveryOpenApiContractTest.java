package com.dwp.services.platform.workplace.exceptionconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceOperationalRecoveryOpenApiContractTest {

    private static final List<String> EXCEPTION_SOURCES = List.of(
            "SAFETY", "BOOKING", "CONNECTOR", "DEVICE_COMMAND", "SERVICE_ORDER",
            "SERVICE_PROVIDER", "SERVICE_REFUND", "VISIT_PROVIDER", "ACCESS_PASS");
    private static final List<String> EXCEPTION_STATUSES = List.of(
            "ACTIVE", "RECOVERING", "MANUAL_REVIEW", "RESOLVED");
    private static final List<String> EXCEPTION_ACTIONS = List.of(
            "OPEN_SAFETY", "REVIEW_BOOKING", "REPLAY_CONNECTOR", "REVIEW_DEVICE",
            "REVIEW_SERVICE_ORDER", "REVIEW_SERVICE_PROVIDER", "REVIEW_SERVICE_REFUND",
            "REVIEW_VISIT", "REVIEW_ACCESS_PASS");
    private static final List<String> ATTEMPT_STATES = List.of(
            "QUEUED", "DISPATCHING", "DELIVERED", "DELIVERY_FAILED", "OFFLINE_QUEUED",
            "MANUAL_REVIEW_REQUIRED", "RESULT_UNKNOWN");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serviceAndGatewayExposeTheExactOperationalRecoveryContract() throws Exception {
        Path root = repositoryRoot();
        assertContract(mapper.readTree(root.resolve("contracts/openapi/platform.json").toFile()),
                "", "/v1");
        assertContract(mapper.readTree(
                        root.resolve("contracts/openapi/gateway-public.json").toFile()),
                "platform_", "/api/platform/v1");
    }

    private void assertContract(JsonNode document, String schemaPrefix, String pathPrefix) {
        JsonNode schemas = document.path("components").path("schemas");
        JsonNode item = schemas.path(schemaPrefix + "ExceptionItem").path("properties");
        assertEnum(item.path("source"), EXCEPTION_SOURCES);
        assertEnum(item.path("status"), EXCEPTION_STATUSES);
        assertEnum(item.path("action"), EXCEPTION_ACTIONS);
        assertThat(item.path("resolved").path("type").asText()).isEqualTo("boolean");
        assertThat(item.path("resolvedAt").path("format").asText()).isEqualTo("date-time");
        assertThat(item.path("ageSeconds").path("format").asText()).isEqualTo("int64");

        JsonNode summary = schemas.path(schemaPrefix + "ExceptionSummary").path("properties");
        assertThat(summary.path("unresolvedCount").path("format").asText()).isEqualTo("int64");
        assertThat(summary.path("returnedCount").path("format").asText()).isEqualTo("int32");
        assertThat(summary.path("truncated").path("type").asText()).isEqualTo("boolean");
        JsonNode console = schemas.path(schemaPrefix + "ExceptionConsole").path("properties");
        assertThat(console.path("sourceCoverage").path("type").asText()).isEqualTo("array");
        JsonNode coverage = schemas.path(schemaPrefix + "ExceptionSourceCoverage")
                .path("properties");
        assertEnum(coverage.path("source"), EXCEPTION_SOURCES);
        assertThat(coverage.path("unresolvedCount").path("format").asText()).isEqualTo("int64");
        assertThat(coverage.path("returnedCount").path("format").asText()).isEqualTo("int32");
        assertThat(coverage.path("truncated").path("type").asText()).isEqualTo("boolean");

        JsonNode dispatch = schemas.path(schemaPrefix + "DispatchSummary").path("properties");
        assertEnum(dispatch.path("reviewState"),
                List.of("NONE", "MANUAL_REVIEW_REQUIRED"));
        assertThat(dispatch.path("manualReviewCount").path("format").asText())
                .isEqualTo("int32");
        assertEnum(schemas.path(schemaPrefix + "ResendRequest").path("properties")
                .path("retryStates").path("items"), ATTEMPT_STATES);

        assertRequiredIdempotencyHeader(document, pathPrefix
                + "/admin/workplace/bookings/{bookingId}/force-cancel");
        assertRequiredIdempotencyHeader(document, pathPrefix
                + "/admin/workplace/bookings/{bookingId}/legal-hold");
    }

    private void assertEnum(JsonNode schema, List<String> expected) {
        assertThat(schema.path("enum")).extracting(JsonNode::asText)
                .containsExactlyElementsOf(expected);
    }

    private void assertRequiredIdempotencyHeader(JsonNode document, String path) {
        JsonNode parameters = document.path("paths").path(path).path("put").path("parameters");
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("Idempotency-Key");
            assertThat(parameter.path("in").asText()).isEqualTo("header");
            assertThat(parameter.path("required").asBoolean()).isTrue();
        });
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/platform.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }
}
