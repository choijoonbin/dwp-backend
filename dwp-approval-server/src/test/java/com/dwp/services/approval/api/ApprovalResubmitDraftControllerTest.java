package com.dwp.services.approval.api;

import com.dwp.services.approval.domain.ApprovalDraftService;
import com.dwp.services.approval.domain.ApprovalResubmitDraftDtos;
import com.dwp.services.approval.domain.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalResubmitDraftControllerTest {
    private final ApprovalDraftService drafts = mock(ApprovalDraftService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ApprovalController(mock(ApprovalService.class), drafts)).build();

    @Test
    void exactEndpointBindsExpectedVersionAndRequiredIdempotencyHeader() throws Exception {
        UUID source = UUID.randomUUID();
        var body = new ApprovalResubmitDraftDtos.Request(17L);

        mvc.perform(post("/v1/requests/{requestId}/resubmit-draft", source)
                        .header("Idempotency-Key", "resubmit-17")
                        .header("X-Correlation-ID", "corr-17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":17}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(drafts).resubmit(source, body, "resubmit-17", "corr-17");
    }

    @Test
    void missingIdempotencyHeaderAndInvalidVersionAreRejectedBeforeService() throws Exception {
        UUID source = UUID.randomUUID();
        mvc.perform(post("/v1/requests/{requestId}/resubmit-draft", source)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":17}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/requests/{requestId}/resubmit-draft", source)
                        .header("Idempotency-Key", "resubmit-17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void currentSchemaIncompatibilityIsReturnedAs422WithoutChangingTheCommandIdentity() throws Exception {
        UUID source = UUID.randomUUID();
        var body = new ApprovalResubmitDraftDtos.Request(23L);
        when(drafts.resubmit(source, body, "same-original-key", "corr-23"))
                .thenThrow(new ApprovalDraftService.IncompatibleSourcePayload(
                        new IllegalArgumentException("incompatible")));

        mvc.perform(post("/v1/requests/{requestId}/resubmit-draft", source)
                        .header("Idempotency-Key", "same-original-key")
                        .header("X-Correlation-ID", "corr-23")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":23}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("E1001"))
                .andExpect(jsonPath("$.correlationId").value("corr-23"));

        verify(drafts).resubmit(source, body, "same-original-key", "corr-23");
    }
}
