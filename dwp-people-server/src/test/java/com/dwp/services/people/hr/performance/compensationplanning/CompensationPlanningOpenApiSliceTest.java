package com.dwp.services.people.hr.performance.compensationplanning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = CompensationPlanningController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.dwp\\.services\\.people\\..*Filter"),
        properties = {
                "springdoc.api-docs.enabled=true",
                "dwp.hris.performance.compensation-planning.enabled=true"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class
})
class CompensationPlanningOpenApiSliceTest {

    private static final String BASE =
            "/v1/hris/performance/compensation-planning";
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("GET " + BASE + "/cycles", CompensationPlanningService.QUERY_CYCLES),
            Map.entry("GET " + BASE + "/cycles/{cycleId}",
                    CompensationPlanningService.QUERY_CYCLE),
            Map.entry("POST " + BASE + "/cycles", CompensationPlanningService.CREATE),
            Map.entry("POST " + BASE + "/cycles/{cycleId}/revisions",
                    CompensationPlanningService.REVISE),
            Map.entry("POST " + BASE + "/cycles/{cycleId}/budget-allocations",
                    CompensationPlanningService.BUDGET),
            Map.entry("POST " + BASE + "/cycles/{cycleId}/proposals/{proposalId}",
                    CompensationPlanningService.UPSERT_PROPOSAL),
            Map.entry("POST " + BASE
                            + "/cycles/{cycleId}/proposals/{proposalId}/submit",
                    CompensationPlanningService.SUBMIT_PROPOSAL),
            Map.entry("GET " + BASE + "/cycles/{cycleId}/proposals",
                    CompensationPlanningService.QUERY_PROPOSALS),
            Map.entry("GET " + BASE + "/proposals/{proposalId}",
                    CompensationPlanningService.QUERY_PROPOSAL),
            Map.entry("POST " + BASE + "/cycles/{cycleId}/approve",
                    CompensationPlanningService.APPROVE),
            Map.entry("POST " + BASE + "/cycles/{cycleId}/approved-snapshots",
                    CompensationPlanningService.PUBLISH),
            Map.entry("POST " + BASE + "/cycles/{cycleId}/cancel",
                    CompensationPlanningService.CANCEL));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CompensationPlanningService service;

    @Test
    void publishesTheClosedCompensationPlanningV5PayloadGraph() throws Exception {
        JsonNode document = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(document.path("paths").size()).isEqualTo(11);
        OPERATIONS.forEach((key, operationId) -> {
            String[] parts = key.split(" ", 2);
            JsonNode operation = document.path("paths").path(parts[1])
                    .path(parts[0].toLowerCase());
            assertThat(operation.path("operationId").asText()).as(key)
                    .isEqualTo(operationId);
            assertThat(operation.path("parameters"))
                    .as(key + " parameters")
                    .allSatisfy(parameter -> assertThat(parameter.path("name").asText())
                            .isNotEqualTo("query"));
        });

        JsonNode schemas = document.path("components").path("schemas");
        assertResponseDataRef(document, "get", BASE + "/cycles",
                "ModernCompplanCyclesQueryResponseV5");
        assertResponseDataRef(document, "get", BASE + "/cycles/{cycleId}",
                "ModernCompplanCycleQueryResponseV5");
        assertRequestRef(document, "post", BASE + "/cycles",
                "ModernCompplanCycleCreateRequestV2");
        assertResponseDataRef(document, "post", BASE + "/cycles",
                "PerformanceCommandReceiptV1");

        assertRef(schemas.path("ModernCompplanCyclesQueryResponseV5")
                        .path("properties").path("items").path("items"),
                "ModernCompplanCycleProjectionV5");
        assertRef(schemas.path("ModernCompplanCycleQueryResponseV5")
                        .path("properties").path("item"),
                "ModernCompplanCycleProjectionV5");
        assertRef(schemas.path("ModernCompplanCycleProjectionV5")
                        .path("properties").path("budgetAggregates").path("items"),
                "ModernCompplanBudgetAggregateV1");
        assertThat(schemas.path("ModernCompplanBudgetAggregateV1")
                .path("properties").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "organizationPublicId", "currency", "allocatedAmount",
                "committedAmount", "remainingAmount");
        for (String name : Set.of(
                "ModernCompplanCycleCreateRequestV2",
                "ModernCompplanCycleProjectionV5",
                "ModernCompplanCyclesQueryResponseV5",
                "ModernCompplanCycleQueryResponseV5",
                "ModernCompplanBudgetAggregateV1",
                "PerformanceCommandReceiptV1")) {
            assertThat(schemas.path(name).path("additionalProperties").asBoolean(true))
                    .as(name + " closed object").isFalse();
        }

        assertThat(document.path("paths")
                .path(BASE + "/cycles/{cycleId}/cancel")
                .path("post").has("requestBody")).isFalse();
        assertThat(document.path("paths").path(BASE + "/cycles/{cycleId}")
                .path("get").path("parameters"))
                .anySatisfy(parameter -> {
                    assertThat(parameter.path("name").asText())
                            .isEqualTo(CompensationPlanningController.PROJECTION_VERSION);
                    assertThat(parameter.path("in").asText()).isEqualTo("header");
                    assertThat(parameter.path("required").asBoolean()).isFalse();
                });
    }

    private void assertRequestRef(
            JsonNode document,
            String method,
            String path,
            String expectedSchema) {
        assertRef(document.path("paths").path(path).path(method)
                .path("requestBody").path("content")
                .path("application/json").path("schema"), expectedSchema);
    }

    private void assertResponseDataRef(
            JsonNode document,
            String method,
            String path,
            String expectedSchema) {
        JsonNode wrapper = document.path("paths").path(path).path(method)
                .path("responses").path("200").path("content")
                .path("application/json").path("schema");
        String wrapperName = refName(wrapper);
        JsonNode data = document.path("components").path("schemas")
                .path(wrapperName).path("properties").path("data");
        assertRef(data, expectedSchema);
    }

    private void assertRef(JsonNode schema, String expectedSchema) {
        assertThat(refName(schema)).isEqualTo(expectedSchema);
    }

    private String refName(JsonNode schema) {
        String ref = schema.path("$ref").asText();
        assertThat(ref).startsWith("#/components/schemas/");
        return ref.substring(ref.lastIndexOf('/') + 1);
    }
}
