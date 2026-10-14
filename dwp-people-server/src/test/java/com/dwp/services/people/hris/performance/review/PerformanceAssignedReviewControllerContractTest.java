package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = PerformanceAssignedReviewController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.dwp\\.services\\.people\\..*Filter"),
        properties = {
                "springdoc.api-docs.enabled=true",
                "dwp.hris.performance.assigned-review.enabled=true"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class
})
class PerformanceAssignedReviewControllerContractTest {

    private static final String REVIEW_PATH =
            "/v1/hris/performance/reviews/{reviewId}";
    private static final String SUBMIT_PATH = REVIEW_PATH + "/submit";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private PerformanceReviewService service;

    @Test
    void publishesTheThreeSealedOwnerOperationsAndTypedPayloads() throws Exception {
        JsonNode document = mapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        Map.of(
                "get", PerformanceReviewService.GET_OPERATION,
                "patch", PerformanceReviewService.SAVE_OPERATION)
                .forEach((method, operation) -> assertThat(document.path("paths")
                        .path(REVIEW_PATH).path(method).path("operationId").asText())
                        .isEqualTo(operation));
        assertThat(document.path("paths").path(SUBMIT_PATH)
                .path("post").path("operationId").asText())
                .isEqualTo(PerformanceReviewService.SUBMIT_OPERATION);
        assertThat(requestSchema(document, "patch", REVIEW_PATH,
                "application/merge-patch+json"))
                .isEqualTo("ReviewDraftCommandV1");
        assertThat(requestSchema(document, "post", SUBMIT_PATH, "application/json"))
                .isEqualTo("SubmitReviewCommandV1");
        assertThat(responseSchema(document, "get", REVIEW_PATH))
                .isEqualTo("AssignedReviewV1");
        assertThat(responseSchema(document, "post", SUBMIT_PATH))
                .isEqualTo("ReviewSubmissionV1");
        assertThat(refName(document.path("paths").path(REVIEW_PATH).path("get")
                .path("responses").path("404").path("content")
                .path("application/problem+json").path("schema")))
                .isEqualTo("HrisProblemDetailsV1");
    }

    @Test
    void failuresUseSealedProblemDetailsInsteadOfTheGenericApiEnvelope() throws Exception {
        when(service.review(any(), anyString(), anyString())).thenThrow(
                new BaseException(ErrorCode.NOT_FOUND,
                        "The assigned review is not available."));

        mockMvc.perform(get("/v1/hris/performance/reviews/" +
                        PerformanceReviewTestFixtures.REVIEW)
                        .header(PerformanceAssignedReviewController.PURPOSE,
                                HcmPerformanceReviewAuthorityAdapter.PURPOSE)
                        .header(PerformanceAssignedReviewController.POPULATION,
                                "TARGET_POPULATION"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void authorityResolutionFailureUsesASealedRetryableProblemStatus() throws Exception {
        when(service.review(any(), anyString(), anyString())).thenThrow(
                new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Current assigned-review authority is unavailable."));

        mockMvc.perform(get("/v1/hris/performance/reviews/" +
                        PerformanceReviewTestFixtures.REVIEW)
                        .header(PerformanceAssignedReviewController.PURPOSE,
                                HcmPerformanceReviewAuthorityAdapter.PURPOSE)
                        .header(PerformanceAssignedReviewController.POPULATION,
                                "TARGET_POPULATION"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void featureIsFailClosedWhenThePropertyIsMissing() {
        ConditionalOnProperty condition = PerformanceAssignedReviewController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.name()).containsExactly(
                "dwp.hris.performance.assigned-review.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    private String requestSchema(
            JsonNode document,
            String method,
            String path,
            String contentType) {
        return refName(document.path("paths").path(path).path(method)
                .path("requestBody").path("content").path(contentType).path("schema"));
    }

    private String responseSchema(JsonNode document, String method, String path) {
        return refName(document.path("paths").path(path).path(method)
                .path("responses").path("200").path("content")
                .path("application/json").path("schema"));
    }

    private String refName(JsonNode schema) {
        String ref = schema.path("$ref").asText();
        assertThat(ref).startsWith("#/components/schemas/");
        return ref.substring(ref.lastIndexOf('/') + 1);
    }
}
