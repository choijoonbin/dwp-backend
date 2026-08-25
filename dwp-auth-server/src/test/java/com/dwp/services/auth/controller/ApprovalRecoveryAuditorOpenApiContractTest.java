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

class ApprovalRecoveryAuditorOpenApiContractTest {

    private static final String PATH =
            "/internal/auth/v1/approval-recovery-auditor/resolve";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authSnapshotPublishesOnlyTheExactInternalRecoveryContract() throws IOException {
        JsonNode contract = objectMapper.readTree(
                repositoryRoot().resolve("contracts/openapi/auth.json").toFile());
        JsonNode operation = contract.path("paths").path(PATH).path("post");

        assertThat(operation.path("operationId").asText())
                .isEqualTo("resolveApprovalRecoveryAuditorInternal");
        assertThat(parameterNames(operation.path("parameters")))
                .containsExactlyInAnyOrder(
                        "X-DWP-Approval-Recovery-Token",
                        "X-DWP-Service-Identity");
        assertThat(operation.path("requestBody").path("content")
                .path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApprovalRecoveryAuditorResolveRequest");
        assertThat(operation.path("responses").path("200").path("content")
                .path("*/*").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApprovalRecoveryAuditorResolveResponse");
        assertThat(contract.path("paths").has(
                "/api/auth/v1/approval-recovery-auditor/resolve")).isFalse();
        assertThat(contract.path("paths").has(
                "/auth/v1/approval-recovery-auditor/resolve")).isFalse();

        JsonNode schemas = contract.path("components").path("schemas");
        assertThat(fieldNames(schemas.path("ApprovalRecoveryAuditorResolveRequest")
                .path("properties")))
                .containsExactlyInAnyOrder(
                        "tenantId", "outboxId", "originatorUserId", "resourceSetKey");
        assertThat(fieldNames(schemas.path("ApprovalRecoveryAuditorResolveResponse")
                .path("properties")))
                .containsExactlyInAnyOrder(
                        "selectedUserId", "resourceSetKey", "assignmentRevision");
    }

    private Set<String> parameterNames(JsonNode parameters) {
        Set<String> result = new HashSet<>();
        parameters.forEach(parameter -> {
            assertThat(parameter.path("in").asText()).isEqualTo("header");
            assertThat(parameter.path("required").asBoolean()).isTrue();
            result.add(parameter.path("name").asText());
        });
        return result;
    }

    private Set<String> fieldNames(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
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
