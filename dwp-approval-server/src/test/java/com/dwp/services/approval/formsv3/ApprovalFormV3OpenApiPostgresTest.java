package com.dwp.services.approval.formsv3;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.ApprovalServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = ApprovalServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "springdoc.api-docs.enabled=true",
                "dwp.observability.api-history.enabled=false",
                "otel.sdk.disabled=true"
        })
class ApprovalFormV3OpenApiPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper mapper;
    @LocalServerPort private int port;

    @Test
    void runtimeContractExposesOnlyBoundedDraftAndReadOperations() throws Exception {
        var response = rest.getForEntity("http://127.0.0.1:" + port + "/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode paths = mapper.readTree(response.getBody()).path("paths");

        assertOperation(paths, "/v1/admin/forms/templates", "get");
        assertOperation(paths, "/v1/admin/forms/templates/{templateId}", "get");
        assertOperation(paths, "/v1/admin/forms/templates/{templateId}/comparison", "get");
        assertMutation(paths, "/v1/admin/forms/templates/{templateId}/draft", "post");
        assertMutation(paths, "/v1/admin/forms/templates/versions/{templateVersionId}/install", "post");

        assertOperation(paths, "/v1/admin/forms/studio-v3", "get");
        assertOperation(paths, "/v1/admin/forms/studio-v3/{formId}", "get");
        assertOperation(paths, "/v1/admin/forms/studio-v3/{formId}/versions", "get");
        assertOperation(paths, "/v1/admin/forms/studio-v3/validate", "post");
        assertOperation(paths, "/v1/admin/forms/studio-v3/{formId}/review", "post");
        assertOperation(paths, "/v1/admin/forms/studio-v3/{formId}/evaluate", "post");
        assertMutation(paths, "/v1/admin/forms/studio-v3/{sourceFormId}/draft", "post");
        assertMutation(paths, "/v1/admin/forms/studio-v3/{formId}/draft", "put");
        assertMutation(paths, "/v1/admin/forms/studio-v3/{formId}/archive", "post");

        Set<String> unsupported = Set.of("publish", "release", "activate");
        assertThat(paths.properties()).noneMatch(entry -> unsupported.stream()
                .anyMatch(operation -> entry.getKey().matches(
                        "^/v1/admin/forms/(templates|studio-v3)/\\{[^}]+}/" + operation + "$")));
    }

    private void assertOperation(JsonNode paths, String path, String method) {
        assertThat(paths.path(path).has(method)).as(method + " " + path).isTrue();
    }

    private void assertMutation(JsonNode paths, String path, String method) {
        JsonNode operation = paths.path(path).path(method);
        assertThat(operation.isMissingNode()).as(method + " " + path).isFalse();
        assertThat(operation.path("parameters")).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("Idempotency-Key");
            assertThat(parameter.path("required").asBoolean()).isTrue();
        }).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("X-DWP-Expected-Object-Version");
            assertThat(parameter.path("required").asBoolean()).isTrue();
        });
        assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
    }
}
