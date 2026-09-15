package com.dwp.services.approval.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.RejectPublishReview;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.RequestPublishReview;
import com.dwp.services.approval.forms.ApprovalFormPublishReviewService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalFormPublishReviewControllerTest {
    private final ApprovalFormPublishReviewService reviews = mock(ApprovalFormPublishReviewService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ApprovalFormPublishReviewController(reviews))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();
    private final UUID formId = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();
    private final UUID personId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @Test
    void allReadsDispatchOnlyCanonicalIdentifiersAndExactQueryParameters() throws Exception {
        mvc.perform(get("/v1/admin/forms/publish-review-candidates")
                        .queryParam("query", "publisher").queryParam("size", "10"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/forms/publish-review-requests").queryParam("size", "50"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/forms/{formId}/publish-review-request", formId))
                .andExpect(status().isOk());

        verify(reviews).candidates("publisher", 10);
        verify(reviews).queue(50);
        verify(reviews).latest(formId);
    }

    @Test
    void requestAndRejectBindExactCasBodiesAndOriginalCommandHeaders() throws Exception {
        var request = new RequestPublishReview(draftId, null, 4L, 2L, "a".repeat(64),
                32L, personId, null, null, "Please independently review this form.");
        mvc.perform(post("/v1/admin/forms/{formId}/publish-review-request", formId)
                        .header("Idempotency-Key", "publish-review-request-1")
                        .header("X-Correlation-ID", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk());
        verify(reviews).request(formId, request, "publish-review-request-1", "correlation-1");

        var reject = new RejectPublishReview(4L, 2L, 0L,
                "The form requires additional compliance controls.");
        mvc.perform(post("/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject",
                        formId, requestId)
                        .header("Idempotency-Key", "publish-review-reject-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(reject)))
                .andExpect(status().isOk());
        verify(reviews).reject(formId, requestId, reject, "publish-review-reject-1", null);
    }

    @Test
    void ambiguousTransportAliasesAndUnknownBodiesStopBeforeTheService() throws Exception {
        mvc.perform(get("/v1/admin/forms/publish-review-candidates")
                        .queryParam("query", "publisher").queryParam("redirect", "https://invalid.test"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/forms/publish-review-requests").queryParam("size", "10", "20"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/forms/{formId}/publish-review-request",
                        formId.toString().toUpperCase(Locale.ROOT)))
                .andExpect(status().isBadRequest());

        var request = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(
                new RequestPublishReview(draftId, null, 4L, 2L, "a".repeat(64),
                        32L, personId, null, null, "Please independently review this form."));
        request.put("unexpectedField", true);
        mvc.perform(post("/v1/admin/forms/{formId}/publish-review-request", formId)
                        .header("Idempotency-Key", "one", "two")
                        .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/{formId}/publish-review-request", formId)
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject",
                        formId, requestId.toString().toUpperCase(Locale.ROOT))
                        .header("Idempotency-Key", "reject-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedFormRevision\":4,\"expectedWorkspaceRevision\":2,"
                                + "\"expectedReviewRequestVersion\":0,\"reason\":\"A valid rejection reason.\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reviews);
    }
}
