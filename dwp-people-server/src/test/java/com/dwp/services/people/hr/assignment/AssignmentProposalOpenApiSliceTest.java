package com.dwp.services.people.hr.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;

@WebMvcTest(
        controllers = AssignmentProposalController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.dwp\\.services\\.people\\..*Filter"),
        properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc(addFilters = false)
@Import({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class
})
class AssignmentProposalOpenApiSliceTest {

    private static final Map<String, String> OPERATIONS = Map.of(
            "GET /v1/workforce/assignments/{assignmentId}", "getAssignment",
            "GET /v1/workforce/assignments/{assignmentId}/timeline",
            "getAssignmentTimeline",
            "GET /v1/workforce/assignment-proposals/{proposalId}",
            "getAssignmentProposal",
            "POST /v1/workforce/assignment-proposals", "createAssignmentProposal",
            "POST /v1/workforce/assignment-proposals/{proposalId}/cancel",
            "cancelAssignmentProposal",
            "POST /v1/workforce/assignment-proposals/{proposalId}/submit",
            "submitAssignmentProposal",
            "POST /v1/workforce/assignment-proposals/{proposalId}/validate",
            "validateAssignmentProposal");

    private static final Set<String> ASSIGNMENT_SCHEMAS = Set.of(
            "AssignmentProposalCreateRequest",
            "AssignmentProposalVersionCommand",
            "AssignmentProposalCancelCommand",
            "AssignmentProposalValidationFinding",
            "AssignmentProposal",
            "AssignmentProposalCommandResult",
            "AssignmentProposalAssignmentDetail",
            "AssignmentProposalTimelineEntry");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AssignmentProposalService service;

    @Test
    void publishesTheSevenAssignmentOperationsWithSchemas() throws Exception {
        String content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(content);

        Path output = Path.of("build/generated/g3-assignment-openapi.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), document);

        assertThat(document.path("paths").size()).isEqualTo(7);
        OPERATIONS.forEach((key, operationId) -> {
            String[] parts = key.split(" ", 2);
            JsonNode operation = document.path("paths").path(parts[1])
                    .path(parts[0].toLowerCase());
            assertThat(operation.path("operationId").asText()).as(key)
                    .isEqualTo(operationId);
            assertThat(operation.path("responses").path("200")
                    .path("content").path("application/json").path("schema").isMissingNode())
                    .as(key + " response schema").isFalse();
        });

        JsonNode schemas = document.path("components").path("schemas");
        assertThat(ASSIGNMENT_SCHEMAS)
                .allSatisfy(name -> assertThat(schemas.has(name)).as(name).isTrue());
        assertRequiredFields(schemas, "AssignmentProposalCreateRequest",
                "changeType", "commandId", "effectiveDate", "expectedAssignmentVersion",
                "proposedChanges", "reasonCode", "targetAssignmentId");
        assertRequiredFields(schemas, "AssignmentProposalVersionCommand",
                "commandId", "expectedVersion");
        assertRequiredFields(schemas, "AssignmentProposalCancelCommand",
                "commandId", "expectedVersion", "reason");

        JsonNode submit = document.path("paths")
                .path("/v1/workforce/assignment-proposals/{proposalId}/submit")
                .path("post");
        assertHeader(submit, "Idempotency-Key", "string", "", 0, 1, 200);
        assertHeader(submit, "X-DWP-Step-Up-Challenge", "string", "", 0, 1, 0);
        assertHeader(submit, "X-DWP-Expected-Decision-Revision",
                "string", "", 0, 1, 200);
        assertHeader(submit, "X-DWP-Expected-Object-Version",
                "integer", "int64", 0, 0, 0);

        for (String path : Set.of(
                "/v1/workforce/assignment-proposals",
                "/v1/workforce/assignment-proposals/{proposalId}/validate",
                "/v1/workforce/assignment-proposals/{proposalId}/cancel")) {
            assertHeader(document.path("paths").path(path).path("post"),
                    "Idempotency-Key", "string", "", 0, 1, 200);
        }
    }

    @Test
    void rejectsCommandWhenExpectedVersionIsOmitted() throws Exception {
        mockMvc.perform(post(
                        "/v1/workforce/assignment-proposals/"
                                + "00000000-0000-0000-0000-000000000001/validate")
                        .header("Idempotency-Key", "g3-missing-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commandId":"00000000-0000-0000-0000-000000000002"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private void assertRequiredFields(
            JsonNode schemas, String schemaName, String... expectedFields) {
        Set<String> actual = new java.util.HashSet<>();
        schemas.path(schemaName).path("required")
                .forEach(field -> actual.add(field.asText()));
        assertThat(actual).as(schemaName + " required")
                .containsExactlyInAnyOrder(expectedFields);
    }

    private void assertHeader(
            JsonNode operation,
            String name,
            String type,
            String format,
            int minimum,
            int minLength,
            int maxLength) {
        JsonNode header = null;
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                header = parameter;
                break;
            }
        }
        assertThat(header).as(name).isNotNull();
        assertThat(header.path("required").asBoolean()).as(name + " required").isTrue();
        JsonNode schema = header.path("schema");
        assertThat(schema.path("type").asText()).as(name + " type").isEqualTo(type);
        if (!format.isEmpty()) {
            assertThat(schema.path("format").asText()).as(name + " format")
                    .isEqualTo(format);
        }
        if (minimum > 0 || "integer".equals(type)) {
            assertThat(schema.path("minimum").asInt()).as(name + " minimum")
                    .isEqualTo(minimum);
        }
        if (minLength > 0) {
            assertThat(schema.path("minLength").asInt()).as(name + " minLength")
                    .isEqualTo(minLength);
        }
        if (maxLength > 0) {
            assertThat(schema.path("maxLength").asInt()).as(name + " maxLength")
                    .isEqualTo(maxLength);
        }
    }
}
